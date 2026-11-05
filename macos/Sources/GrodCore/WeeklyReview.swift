import Foundation

/// docs/rules.md "Weekly review", the macOS part.
public enum WeeklyReview {
    /// A review is due when no file in the reviews folder is newer than this.
    public static let interval: TimeInterval = 6 * 24 * 60 * 60

    public static func isDue(lastReview: Date?, now: Date) -> Bool {
        guard let lastReview else { return true }
        return now.timeIntervalSince(lastReview) >= interval
    }

    /// Newest modification date among the snapshots (`.json`) and notes (`.md`).
    public static func lastReview(in folder: URL, fileManager: FileManager = .default) -> Date? {
        let files = (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files.filter { ["json", "md"].contains($0.pathExtension) }
            .compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
            .max()
    }

    /// Serials `adb devices` lists as ready; `unauthorized` and `offline` phones cannot be read yet.
    public static func readyDevices(adbOutput: String) -> [String] {
        adbOutput.split(whereSeparator: \.isNewline).dropFirst().compactMap { line in
            let fields = line.split(separator: "\t")
            return fields.count == 2 && fields[1] == "device" ? String(fields[0]) : nil
        }
    }

    /// AppleScript that opens Terminal in the repository on the grod-review skill.
    public static func terminalScript(repository: String) -> String {
        let shellPath = "'" + repository.replacingOccurrences(of: "'", with: "'\\''") + "'"
        let command = "cd \(shellPath) && claude '/grod-review'"
        let literal = "\"" + command.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
        return """
        tell application "Terminal"
            activate
            do script \(literal)
        end tell
        """
    }
}
