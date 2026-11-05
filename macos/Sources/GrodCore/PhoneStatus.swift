import Foundation

/// Whether the phone runs the rules this Mac has. Phone settings reach it only over adb.
public enum PhoneStatus: Equatable, Sendable {
    public enum Link: Equatable, Sendable {
        case usb, wifi

        /// Wi-Fi adb serials are `host:port`.
        public init(serial: String) { self = serial.contains(":") ? .wifi : .usb }
    }

    public enum Sync: Equatable, Sendable { case upToDate, syncing, failed }

    case checking
    case adbMissing
    case notConnected(waiting: Bool)
    case connected(Link, Sync)

    /// Where the phone app keeps the rules `grod sync` pushes (tools/src/sync.ts).
    public static let phoneRulesPath = "/sdcard/Android/data/dev.grod/files/rules.json"

    /// Top-level `revision` of the copy on the phone; nil when it is missing or unreadable.
    public static func revision(ofPhoneRules data: Data) -> String? {
        (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["revision"] as? String
    }

    public var title: String {
        switch self {
        case .checking: "Checking the phone…"
        case .adbMissing: "Phone status unavailable"
        case .notConnected(let waiting): waiting ? "Phone not connected · waiting to sync" : "Phone not connected"
        case .connected(let link, let sync):
            "Phone connected over \(link == .wifi ? "Wi-Fi" : "USB") · \(sync == .upToDate ? "up to date" : sync == .syncing ? "syncing…" : "could not sync")"
        }
    }

    public var detail: String? {
        switch self {
        case .adbMissing: "adb was not found when the app was built."
        case .notConnected: "Changes reach it the next time you connect it (USB or Wi-Fi)."
        case .connected(_, .failed): "Run grod sync in a terminal to see why."
        default: nil
        }
    }

    /// For a section header.
    public var short: String {
        switch self {
        case .checking: "Checking…"
        case .adbMissing: "Status unavailable"
        case .notConnected(let waiting): waiting ? "Waiting to sync" : "Not connected"
        case .connected(_, .upToDate): "Up to date"
        case .connected(_, .syncing): "Syncing…"
        case .connected(_, .failed): "Could not sync"
        }
    }
}
