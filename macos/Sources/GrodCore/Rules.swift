import CryptoKit
import Foundation

public struct Rules: Codable, Sendable {
    public let schemaVersion: Int
    public let rules: [Rule]
    public let scroll: ScrollSettings?
    public let opening: OpeningSettings?
    public let notifications: NotificationSettings?
    public let review: ReviewSettings?
    public let launcher: LauncherSettings?
}

public struct Rule: Codable, Sendable {
    public let id: String
    public let title: String
    public let why: String?
    public let enabled: Bool
    public let web: WebRules?
    public let android: AndroidRules?
    public let macos: MacRules?
}

public struct WebRules: Codable, Sendable {
    public let hosts: [String]
    public let redirect: [Redirect]?
    public let block: [String]?
}

public struct Redirect: Codable, Sendable {
    public let from: String
    public let to: String
}

/// Only what the Mac shows; the phone reads the rest of the section.
public struct AndroidRules: Codable, Sendable {
    public let packages: [String]
}

public struct LauncherSettings: Codable, Sendable, Equatable {
    public let distracting: [String]?
    public let hidden: [String]?
}

public struct MacRules: Codable, Sendable {
    public let quitApps: [String]?
}

/// docs/rules.md "Scroll pause". A missing field takes its default, as on every platform.
public struct ScrollSettings: Codable, Sendable, Equatable {
    public var freeSeconds = 60
    public var firstPauseSeconds = 10
    public var growth = 2.0
    public var maxPauseSeconds = 300
    public var resetMinutes = 15

    public init() {}

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        freeSeconds = try container.decodeIfPresent(Int.self, forKey: .freeSeconds) ?? freeSeconds
        firstPauseSeconds = try container.decodeIfPresent(Int.self, forKey: .firstPauseSeconds) ?? firstPauseSeconds
        growth = try container.decodeIfPresent(Double.self, forKey: .growth) ?? growth
        maxPauseSeconds = try container.decodeIfPresent(Int.self, forKey: .maxPauseSeconds) ?? maxPauseSeconds
        resetMinutes = try container.decodeIfPresent(Int.self, forKey: .resetMinutes) ?? resetMinutes
    }
}

/// docs/rules.md "Opening check (Android)". A missing field takes its default, as on every platform.
public struct OpeningSettings: Codable, Sendable, Equatable {
    public var awayMinutes = 5
    public var quickMinutes = 2

    public init() {}

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        awayMinutes = try container.decodeIfPresent(Int.self, forKey: .awayMinutes) ?? awayMinutes
        quickMinutes = try container.decodeIfPresent(Int.self, forKey: .quickMinutes) ?? quickMinutes
    }
}

public struct NotificationSettings: Codable, Sendable, Equatable {
    public let enabled: Bool
    public let deliverEvery: Int
    public let deliverAt: [String]?
    public let allow: [NotificationAllow]?
    public let keepDays: Int?
}

public struct NotificationAllow: Codable, Sendable, Hashable {
    public let package: String?
    public let category: String?
    public let titleContains: String?

    public init(package: String? = nil, category: String? = nil, titleContains: String? = nil) {
        self.package = package
        self.category = category
        self.titleContains = titleContains
    }
}

public struct ReviewSettings: Codable, Sendable, Equatable {
    public let weekday: Int
    public let time: String
}

public struct RulesSnapshot: Sendable {
    public let document: Rules
    public let revision: String
    public let servedJSON: Data

    public init(data: Data) throws {
        let document = try JSONDecoder().decode(Rules.self, from: data)
        guard document.schemaVersion == 1 else { throw RulesError.unsupportedVersion }
        self.document = document
        revision = Self.revision(of: data)
        // Preserve fields belonging to other platforms and hash only the original bytes.
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RulesError.invalidDocument
        }
        object["revision"] = revision
        servedJSON = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    public static func revision(of data: Data) -> String {
        SHA256.hash(data: data).prefix(6).map { String(format: "%02x", $0) }.joined()
    }
}

public enum RulesError: LocalizedError {
    case unsupportedVersion
    case invalidDocument

    public var errorDescription: String? {
        switch self {
        case .unsupportedVersion: "Rules schemaVersion must be 1."
        case .invalidDocument: "Rules must be a JSON object."
        }
    }
}
