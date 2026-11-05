import Foundation
import GrodCore

/// Polls adb every 5 s while the Settings window is open and syncs a phone that is behind.
@MainActor
final class PhoneMonitor {
    private(set) var status = PhoneStatus.checking { didSet { if status != oldValue { changed?(status) } } }
    var changed: ((PhoneStatus) -> Void)?
    private let store: RulesStore
    private let cli: GrodCLI
    private var timer: Timer?
    private var busy = false
    /// The revision a sync was already tried for, so a failing sync runs once, not every 5 s.
    private var syncedFor: String?
    /// The revision last seen on the phone; kept so "waiting to sync" survives a restart.
    private var phoneRevision: String? {
        get { UserDefaults.standard.string(forKey: "PhoneRevision") }
        set { UserDefaults.standard.set(newValue, forKey: "PhoneRevision") }
    }

    init(store: RulesStore, cli: GrodCLI) {
        self.store = store
        self.cli = cli
    }

    func start() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
        poll()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func poll() {
        guard !busy else { return }
        guard let adb = GrodCLI.info("GrodAdbPath") else { status = .adbMissing; return }
        busy = true
        Task {
            defer { busy = false }
            let devices = WeeklyReview.readyDevices(adbOutput: (try? await GrodCLI.capture(adb, ["devices"])) ?? "")
            let current = store.snapshot?.revision
            guard let serial = devices.first else {
                status = .notConnected(waiting: phoneRevision != nil && phoneRevision != current)
                return
            }
            let link = PhoneStatus.Link(serial: serial)
            let raw = try? await GrodCLI.capture(adb, ["-s", serial, "exec-out", "cat", PhoneStatus.phoneRulesPath])
            let onPhone = raw.flatMap { PhoneStatus.revision(ofPhoneRules: Data($0.utf8)) }
            if let onPhone { phoneRevision = onPhone }
            guard let current, onPhone != current else {
                status = .connected(link, .upToDate)
                return
            }
            guard syncedFor != current else {
                status = .connected(link, .failed)
                return
            }
            syncedFor = current
            status = .connected(link, .syncing)
            _ = try? await cli.run(["sync"])
            store.reload()
            // The next poll reads the phone again and shows whether the push landed.
        }
    }
}
