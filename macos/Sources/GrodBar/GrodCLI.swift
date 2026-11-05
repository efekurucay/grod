import Foundation

/// Every rules edit goes through the grod CLI, so the file keeps one writer that validates, saves atomically
/// and pushes to the phone. Paths come from Info.plist because launchd starts the app without a PATH.
struct GrodCLI: Sendable {
    let rulesURL: URL

    struct Failure: LocalizedError {
        let errorDescription: String?
    }

    /// The repository holding the rules file (`<repo>/rules/grod.rules.json`).
    var repository: URL { rulesURL.deletingLastPathComponent().deletingLastPathComponent() }

    /// Runs `node grod.js <arguments>` off the main thread; returns its output or throws with it.
    @discardableResult
    func run(_ arguments: [String]) async throws -> String {
        guard let node = Self.info("GrodNodePath"), let cli = Self.info("GrodCLIPath") else {
            throw Failure(errorDescription: "grod CLI path not configured; rebuild with node on PATH.")
        }
        var environment = ProcessInfo.processInfo.environment
        // The CLI writes the file this app reads, and finds adb to push it to the phone.
        environment["GROD_HOME"] = repository.path
        let tools = [Self.info("GrodAdbPath"), node].compactMap { $0.map { URL(fileURLWithPath: $0).deletingLastPathComponent().path } }
        environment["PATH"] = (tools + [environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin"]).joined(separator: ":")
        return try await Self.capture(node, [cli] + arguments, environment: environment)
    }

    /// Runs a program without a shell and returns stdout and stderr together; a non-zero exit throws with that text.
    @discardableResult
    static func capture(_ executable: String, _ arguments: [String], environment: [String: String]? = nil) async throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        if let environment { process.environment = environment }
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        return try await withCheckedThrowingContinuation { continuation in
            process.terminationHandler = { finished in
                let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if finished.terminationStatus == 0 {
                    continuation.resume(returning: text)
                } else {
                    let name = URL(fileURLWithPath: executable).lastPathComponent
                    continuation.resume(throwing: Failure(errorDescription: text.isEmpty ? "\(name) exited with \(finished.terminationStatus)." : text))
                }
            }
            do { try process.run() } catch { continuation.resume(throwing: error) }
        }
    }

    static func info(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }
}
