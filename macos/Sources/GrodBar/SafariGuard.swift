import AppKit
import GrodCore

private actor SafariAutomation {
    func readURL() throws -> String? {
        try execute("tell application \"Safari\" to get URL of front document")
    }

    func navigate(from oldURL: String, to newURL: String) throws {
        _ = try execute("""
        tell application "Safari"
            if frontmost and (count of documents) > 0 then
                if URL of front document is \(literal(oldURL)) then
                    set URL of front document to \(literal(newURL))
                end if
            end if
        end tell
        """)
    }

    private func literal(_ value: String) -> String {
        "\"" + value.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }

    // NSAppleScript is main-thread only; a child osascript keeps a hung Safari from freezing the menu,
    // and Automation consent is still asked for Grod, the responsible process.
    private func execute(_ source: String) throws -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", source]
        let output = Pipe()
        let errors = Pipe()
        process.standardOutput = output
        process.standardError = errors
        try process.run()
        let deadline = Date().addingTimeInterval(5)
        while process.isRunning && Date() < deadline { usleep(20_000) }
        if process.isRunning {
            process.terminate()
            throw NSError(domain: "Safari guard", code: -1, userInfo: [NSLocalizedDescriptionKey: "Safari did not answer."])
        }
        let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard process.terminationStatus == 0 else {
            let message = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            // -1728: no document, an empty Safari window is normal.
            if message.contains("-1728") { return nil }
            throw NSError(domain: "Safari guard", code: Int(process.terminationStatus), userInfo: [
                NSLocalizedDescriptionKey: message.trimmingCharacters(in: .whitespacesAndNewlines)
            ])
        }
        return text.isEmpty ? nil : text
    }
}

@MainActor
final class SafariGuard {
    var enabled: Bool {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "SafariGuardEnabled")
            updateTimer()
        }
    }
    var failed: ((String) -> Void)?
    private let store: RulesStore
    private let automation = SafariAutomation()
    private var timer: Timer?
    private var busy = false
    private var generation = 0

    init(store: RulesStore) {
        self.store = store
        UserDefaults.standard.register(defaults: ["SafariGuardEnabled": true])
        enabled = UserDefaults.standard.bool(forKey: "SafariGuardEnabled")
    }

    func updateTimer() {
        generation += 1
        timer?.invalidate()
        timer = nil
        guard enabled, NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.Safari" else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.check() }
        }
    }

    private func check() {
        guard !busy, enabled, NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.Safari" else { return }
        busy = true
        let generation = generation
        Task {
            defer { busy = false }
            do {
                guard let rawURL = try await automation.readURL(), let url = URL(string: rawURL),
                      self.generation == generation, enabled,
                      NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.Safari",
                      let snapshot = store.snapshot else { return }
                let destination: URL
                switch snapshot.document.decision(for: url) {
                case .allow: return
                case .redirect(let url): destination = url
                case .block(let ruleID):
                    var components = URLComponents(string: "http://127.0.0.1:47770/blocked")!
                    components.queryItems = [URLQueryItem(name: "rule", value: ruleID)]
                    destination = components.url!
                }
                try await automation.navigate(from: rawURL, to: destination.absoluteString)
            } catch {
                failed?("Safari guard: \(error.localizedDescription)")
            }
        }
    }
}
