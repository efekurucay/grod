import CryptoKit
import Foundation

public struct Rules: Codable, Sendable {
    public let schemaVersion: Int
    public let rules: [Rule]
    public let notifications: NotificationSettings?
    public let review: ReviewSettings?
}

public struct Rule: Codable, Sendable {
    public let id: String
    public let title: String
    public let why: String?
    public let enabled: Bool
    public let youtubeChannels: [YouTubeChannel]?
    public let web: WebRules?
    public let macos: MacRules?
}

public struct WebRules: Codable, Sendable {
    public let hosts: [String]
    public let hide: [String]?
    public let redirect: [Redirect]?
    public let block: [String]?
}

public struct Redirect: Codable, Sendable {
    public let from: String
    public let to: String
}

public struct MacRules: Codable, Sendable {
    public let quitApps: [String]?
}

public struct YouTubeChannel: Codable, Sendable, Hashable {
    public let name: String
    public let handle: String
    public let id: String?
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
