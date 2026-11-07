import Foundation
import GrodCore
import Observation

/// What the Settings window shows. Edits show at once and go to the grod CLI in order;
/// once the queue is empty the window shows the file again, so a refused edit snaps back.
@MainActor
@Observable
final class SettingsModel {
    enum Section: Hashable { case rules, scroll, notifications, launcher, review }

    struct RuleRow: Identifiable, Equatable {
        let id: String
        let title: String
        var enabled: Bool
    }

    static let defaultTimes = ["09:00", "13:00", "18:00"]

    private(set) var rules: [RuleRow] = []
    private(set) var scroll = ScrollSettings()
    private(set) var notificationsEnabled = false
    private(set) var deliverEvery = 60
    /// Nil while delivering every `deliverEvery` minutes.
    private(set) var deliverAt: [String]?
    private(set) var allow: [NotificationAllow] = []
    private(set) var keepDays = 30
    private(set) var pauseSeconds = 10
    private(set) var distracting: [String] = []
    private(set) var hidden: [String] = []
    private(set) var ruleApps: [(rule: String, package: String)] = []
    private(set) var reviewWeekday = 7
    private(set) var reviewTime = "20:00"
    private(set) var errors: [Section: String] = [:]
    /// The CLI's last line after a save ("…, phone updated."), shown for a few seconds.
    private(set) var notes: [Section: String] = [:]
    var phone = PhoneStatus.checking
    var lastReview: Date?
    var safariGuard: Bool {
        get { safariEnabled }
        set { safariEnabled = newValue; setSafariGuard(newValue) }
    }

    @ObservationIgnored private let store: RulesStore
    @ObservationIgnored private let cli: GrodCLI
    @ObservationIgnored private let setSafariGuard: (Bool) -> Void
    @ObservationIgnored private var queue: Task<Void, Never>?
    @ObservationIgnored private var pending = 0
    @ObservationIgnored private var pauseWrite: Task<Void, Never>?
    private var safariEnabled: Bool

    init(store: RulesStore, cli: GrodCLI, safariGuard: Bool, setSafariGuard: @escaping (Bool) -> Void) {
        self.store = store
        self.cli = cli
        safariEnabled = safariGuard
        self.setSafariGuard = setSafariGuard
        refresh()
    }

    /// Shows the rules file, unless edits are still on their way to it.
    func refresh() {
        guard pending == 0, let document = store.snapshot?.document else { return }
        rules = document.rules.map { RuleRow(id: $0.id, title: $0.title, enabled: $0.enabled) }
        scroll = document.scroll ?? ScrollSettings()
        // The CLI fills a missing `notifications` with the same defaults on its first edit.
        let notifications = document.notifications
        notificationsEnabled = notifications?.enabled ?? false
        deliverEvery = notifications?.deliverEvery ?? 60
        deliverAt = notifications?.deliverAt
        allow = notifications?.allow ?? (notifications == nil ? [.init(category: "event"), .init(category: "reminder")] : [])
        keepDays = notifications?.keepDays ?? 30
        pauseSeconds = document.launcher?.pauseSeconds ?? 10
        distracting = document.launcher?.distracting ?? []
        hidden = document.launcher?.hidden ?? []
        ruleApps = document.launcherRuleApps
        reviewWeekday = document.review?.weekday ?? 7
        reviewTime = document.review?.time ?? "20:00"
    }

    func syncSafariGuard(_ enabled: Bool) { safariEnabled = enabled }

    func setRule(_ id: String, enabled: Bool) {
        guard let index = rules.firstIndex(where: { $0.id == id }) else { return }
        rules[index].enabled = enabled
        run([enabled ? "enable" : "disable", id], in: .rules)
    }

    func setFreeSeconds(_ seconds: Int) {
        scroll.freeSeconds = seconds
        run(["scroll", "free", String(seconds)], in: .scroll)
    }

    func setFirstPause(_ seconds: Int) {
        scroll.firstPauseSeconds = seconds
        run(["scroll", "first", String(seconds)], in: .scroll)
    }

    func setGrowth(_ growth: Double) {
        scroll.growth = growth
        run(["scroll", "growth", String(growth)], in: .scroll)
    }

    func setMaxPause(_ seconds: Int) {
        scroll.maxPauseSeconds = seconds
        run(["scroll", "max", String(seconds)], in: .scroll)
    }

    func setResetMinutes(_ minutes: Int) {
        scroll.resetMinutes = minutes
        run(["scroll", "reset", String(minutes)], in: .scroll)
    }

    func setNotifications(_ enabled: Bool) {
        notificationsEnabled = enabled
        run(["notifications", enabled ? "on" : "off"], in: .notifications)
    }

    func setFixedTimes(_ fixed: Bool) {
        guard fixed != (deliverAt != nil) else { return }
        deliverAt = fixed ? Self.defaultTimes : nil
        run(["notifications", "at", fixed ? Self.defaultTimes.joined(separator: ",") : "off"], in: .notifications)
    }

    func setDeliverEvery(_ minutes: Int) {
        deliverEvery = minutes
        run(["notifications", "every", String(minutes)], in: .notifications)
    }

    func addTime(_ time: String) {
        guard let times = deliverAt, !times.contains(time) else { return }
        setTimes((times + [time]).sorted())
    }

    func removeTime(_ time: String) {
        guard let times = deliverAt, times.count > 1 else { return }
        setTimes(times.filter { $0 != time })
    }

    private func setTimes(_ times: [String]) {
        deliverAt = times
        run(["notifications", "at", times.joined(separator: ",")], in: .notifications)
    }

    func setKeepDays(_ days: Int) {
        keepDays = days
        run(["notifications", "keep", String(days)], in: .notifications)
    }

    func addAllow(_ entry: NotificationAllow) {
        guard let argument = entry.cliArgument else { return }
        allow = allow.filter { $0 != entry } + [entry]
        run(["notifications", "allow", argument], in: .notifications)
    }

    func removeAllow(_ entry: NotificationAllow) {
        guard let argument = entry.cliArgument else { return }
        allow.removeAll { $0 == entry }
        run(["notifications", "disallow", argument], in: .notifications)
    }

    /// Stepper clicks settle first, so one CLI run (and one phone push) carries the final value.
    func setPauseSeconds(_ seconds: Int) {
        pauseSeconds = seconds
        pauseWrite?.cancel()
        pauseWrite = Task {
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled else { return }
            run(["launcher", "pause", String(seconds)], in: .launcher)
        }
    }

    func addDistracting(_ input: String) {
        let package = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !package.isEmpty, !distracting.contains(package) else { return }
        distracting.append(package)
        run(["launcher", "distracting", package], in: .launcher)
    }

    func removeDistracting(_ package: String) {
        distracting.removeAll { $0 == package }
        run(["launcher", "calm", package], in: .launcher)
    }

    func showApp(_ package: String) {
        hidden.removeAll { $0 == package }
        run(["launcher", "show", package], in: .launcher)
    }

    func setReview(weekday: Int, time: String) {
        reviewWeekday = weekday
        reviewTime = time
        run(["review", "set", String(weekday), time], in: .review)
    }

    private func showNote(_ note: String, in section: Section) {
        notes[section] = note
        Task {
            try? await Task.sleep(for: .seconds(5))
            if notes[section] == note { notes[section] = nil }
        }
    }

    private func run(_ arguments: [String], in section: Section) {
        errors[section] = nil
        pending += 1
        let previous = queue
        let cli = cli
        queue = Task {
            await previous?.value
            do {
                let output = try await cli.run(arguments)
                if let line = output.split(whereSeparator: \.isNewline).last.map(String.init) { showNote(line, in: section) }
            } catch {
                errors[section] = error.localizedDescription
            }
            pending -= 1
            store.reload()
            refresh()
        }
    }
}
