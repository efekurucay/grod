import Foundation

extension Rules {
    /// Blocked channels across every rule, once per handle; `grod channel remove <@handle>` removes them all.
    public var youtubeChannels: [YouTubeChannel] {
        var seen = Set<String>()
        return rules.flatMap { $0.youtubeChannels ?? [] }.filter { seen.insert($0.handle.lowercased()).inserted }
    }
}

extension Rules {
    /// Packages the launcher treats as distracting because an enabled rule covers them (docs/rules.md "Launcher").
    public var launcherRuleApps: [(rule: String, package: String)] {
        rules.filter(\.enabled).flatMap { rule in (rule.android?.packages ?? []).map { (rule.title, $0) } }
    }
}

extension NotificationAllow {
    /// The argument `grod notifications allow|disallow` takes. Nil for an entry that combines fields:
    /// the CLI matches whole entries, so the app can take out only single-field ones.
    public var cliArgument: String? {
        switch (package, category, titleContains) {
        case let (value?, nil, nil): value
        case let (nil, value?, nil): "category:" + value
        case let (nil, nil, value?): "title:" + value
        default: nil
        }
    }
}

/// Local "HH:MM" times as the rules file stores them.
public enum ClockTime {
    public static func parse(_ text: String) -> (hour: Int, minute: Int)? {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts.allSatisfy({ $0.count == 2 }),
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return (hour, minute)
    }

    public static func format(hour: Int, minute: Int) -> String {
        String(format: "%02d:%02d", hour, minute)
    }
}
