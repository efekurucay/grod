import AppKit
import GrodCore
import UserNotifications

/// docs/rules.md "Weekly review": when a review is due and the phone shows up on adb, save the week's
/// snapshot and offer the review with Claude.
@MainActor
final class ReviewWatcher {
    private(set) var lastReview: Date?
    var changed: (() -> Void)?
    var failed: ((String) -> Void)?
    private let cli: GrodCLI
    private let folder: URL
    private var timer: Timer?
    private var phonePresent = false
    private var busy = false
    private var authorized: Bool?

    init(cli: GrodCLI) {
        self.cli = cli
        // Same folder the CLI writes snapshots to.
        folder = ProcessInfo.processInfo.environment["GROD_REVIEWS"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/grod/reviews")
        lastReview = WeeklyReview.lastReview(in: folder)
        guard GrodCLI.info("GrodAdbPath") != nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
        poll()
    }

    /// adb runs only while a review is due. The snapshot is taken when a ready phone appears,
    /// so a failed one waits for the next connection instead of retrying every 15 s.
    private func poll() {
        let previous = lastReview
        lastReview = WeeklyReview.lastReview(in: folder)
        if lastReview != previous { changed?() }
        guard !busy, let adb = GrodCLI.info("GrodAdbPath") else { return }
        guard WeeklyReview.isDue(lastReview: lastReview, now: Date()) else {
            phonePresent = false
            return
        }
        busy = true
        Task {
            defer { busy = false }
            let output = (try? await GrodCLI.capture(adb, ["devices"])) ?? ""
            let present = !WeeklyReview.readyDevices(adbOutput: output).isEmpty
            let appeared = present && !phonePresent
            phonePresent = present
            guard appeared else { return }
            do {
                try await cli.run(["review", "snapshot"])
                lastReview = WeeklyReview.lastReview(in: folder)
                changed?()
                await notify()
            } catch {
                failed?("Weekly review: \(error.localizedDescription)")
            }
        }
    }

    private func notify() async {
        // A bare executable (swift run) has no bundle, and the notification center needs one.
        guard Bundle.main.bundleIdentifier != nil else { return }
        let center = UNUserNotificationCenter.current()
        if authorized == nil {
            authorized = (try? await center.requestAuthorization(options: [.alert])) ?? false
        }
        guard authorized == true else { return }
        let content = UNMutableNotificationContent()
        content.title = "Weekly review ready"
        content.body = "Click to review with Claude."
        try? await center.add(UNNotificationRequest(identifier: "weekly-review", content: content, trigger: nil))
    }

    /// Terminal in the repository, on the grod-review skill.
    func openReview() {
        let script = WeeklyReview.terminalScript(repository: cli.repository.path)
        Task {
            do { try await GrodCLI.capture("/usr/bin/osascript", ["-e", script]) } catch {
                failed?("Review with Claude: \(error.localizedDescription)")
            }
        }
    }
}
