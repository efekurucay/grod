import Foundation
import XCTest
@testable import GrodCore

final class GrodCoreTests: XCTestCase {
    private func snapshot(_ json: String) throws -> RulesSnapshot {
        try RulesSnapshot(data: Data(json.utf8))
    }

    private func realRules() throws -> Rules {
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try RulesSnapshot(data: Data(contentsOf: repo.appendingPathComponent("rules/grod.rules.json"))).document
    }

    func testValidation() throws {
        XCTAssertNoThrow(try snapshot(#"{"schemaVersion":1,"rules":[]}"#))
        for json in ["invalid", "[]", #"{"schemaVersion":2,"rules":[]}"#,
                     #"{"schemaVersion":1}"#, #"{"schemaVersion":1,"rules":{}}"#,
                     #"{"schemaVersion":1,"rules":null}"#] {
            XCTAssertThrowsError(try snapshot(json), json)
        }
    }

    func testRevision() throws {
        XCTAssertEqual(RulesSnapshot.revision(of: Data("abc".utf8)), "ba7816bf8f01")
        let loaded = try snapshot(#"{"schemaVersion":1,"rules":[],"extra":{"keep":true}}"#)
        let served = try XCTUnwrap(JSONSerialization.jsonObject(with: loaded.servedJSON) as? [String: Any])
        XCTAssertEqual(served["revision"] as? String, loaded.revision)
        XCTAssertNotNil(served["extra"])
    }

    func testHostMatch() {
        XCTAssertTrue(Rules.host("youtube.com", matches: "youtube.com"))
        XCTAssertTrue(Rules.host("m.youtube.com", matches: "youtube.com"))
        XCTAssertFalse(Rules.host("notyoutube.com", matches: "youtube.com"))
    }

    func testRealRules() throws {
        let rules = try realRules()
        XCTAssertEqual(rules.decision(for: URL(string: "https://m.youtube.com/shorts/abcDEF12345?si=x")!),
                       .redirect(URL(string: "https://m.youtube.com/watch?v=abcDEF12345")!))
        XCTAssertEqual(rules.decision(for: URL(string: "https://instagram.com/reels/")!), .block(ruleID: "instagram-reels"))
        XCTAssertEqual(rules.decision(for: URL(string: "https://instagram.com/reel/ABC/")!), .allow)
        XCTAssertEqual(rules.decision(for: URL(string: "https://tiktok.com/")!), .block(ruleID: "tiktok"))
        XCTAssertEqual(rules.decision(for: URL(string: "https://notyoutube.com/shorts/abc")!), .allow)
    }

    func testDisabledRule() throws {
        let rules = try snapshot(#"{"schemaVersion":1,"rules":[{"id":"off","title":"Off","enabled":false,"web":{"hosts":["tiktok.com"],"block":["^/"]}}]}"#).document
        XCTAssertEqual(rules.decision(for: URL(string: "https://tiktok.com/")!), .allow)
    }

    func testOnlyFirstMatchedRangeReplacedAndRedirectPrecedesBlock() throws {
        let rules = try snapshot(#"{"schemaVersion":1,"rules":[{"id":"test","title":"Test","enabled":true,"web":{"hosts":["example.com"],"redirect":[{"from":"old/([a-z]+)","to":"new/$1"}],"block":["^/"]}}]}"#).document
        XCTAssertEqual(rules.decision(for: URL(string: "https://example.com/prefix/old/abc/old/def?q=1")!),
                       .redirect(URL(string: "https://example.com/prefix/new/abc/old/def?q=1")!))
    }
}
