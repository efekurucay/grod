import Foundation
import XCTest
@testable import GrodCore

final class SettingsTests: XCTestCase {
    func testSettingsDecode() throws {
        let json = #"""
        {"schemaVersion":1,"rules":[
          {"id":"a","title":"A","enabled":true,"web":{"hosts":["a.com"]},"android":{"packages":["com.a"]}}],
         "scroll":{"freeSeconds":120,"growth":1.5},
         "notifications":{"enabled":true,"deliverEvery":60,"deliverAt":["09:00"],"allow":[{"category":"event"}],"keepDays":30},
         "review":{"weekday":7,"time":"20:00"}}
        """#
        let rules = try RulesSnapshot(data: Data(json.utf8)).document
        XCTAssertEqual(rules.scroll?.freeSeconds, 120)
        XCTAssertEqual(rules.scroll?.growth, 1.5)
        XCTAssertEqual(rules.scroll?.firstPauseSeconds, 10)
        XCTAssertEqual(rules.scroll?.maxPauseSeconds, 300)
        XCTAssertEqual(rules.scroll?.resetMinutes, 15)
        XCTAssertEqual(rules.notifications?.deliverAt, ["09:00"])
        XCTAssertEqual(rules.notifications?.allow, [NotificationAllow(category: "event")])
        XCTAssertEqual(rules.review, ReviewSettings(weekday: 7, time: "20:00"))
    }

    func testScrollDefaultsWhenMissing() throws {
        let rules = try RulesSnapshot(data: Data(#"{"schemaVersion":1,"rules":[],"scroll":{}}"#.utf8)).document
        XCTAssertEqual(rules.scroll, ScrollSettings())
        XCTAssertNil(try RulesSnapshot(data: Data(#"{"schemaVersion":1,"rules":[]}"#.utf8)).document.scroll)
    }

    func testOpeningDefaultsWhenMissing() throws {
        let partial = try RulesSnapshot(data: Data(#"{"schemaVersion":1,"rules":[],"opening":{"awayMinutes":15}}"#.utf8)).document
        XCTAssertEqual(partial.opening?.awayMinutes, 15)
        XCTAssertEqual(partial.opening?.quickMinutes, 2)
        let empty = try RulesSnapshot(data: Data(#"{"schemaVersion":1,"rules":[],"opening":{}}"#.utf8)).document
        XCTAssertEqual(empty.opening, OpeningSettings())
        XCTAssertEqual(OpeningSettings().awayMinutes, 5)
        XCTAssertNil(try RulesSnapshot(data: Data(#"{"schemaVersion":1,"rules":[]}"#.utf8)).document.opening)
    }

    func testAllowArgument() {
        XCTAssertEqual(NotificationAllow(package: "com.whatsapp").cliArgument, "com.whatsapp")
        XCTAssertEqual(NotificationAllow(category: "reminder").cliArgument, "category:reminder")
        XCTAssertEqual(NotificationAllow(titleContains: "Anne").cliArgument, "title:Anne")
        XCTAssertNil(NotificationAllow(package: "com.whatsapp", titleContains: "Anne").cliArgument)
    }

    func testClockTime() {
        XCTAssertEqual(ClockTime.parse("09:05")?.hour, 9)
        XCTAssertEqual(ClockTime.parse("23:59")?.minute, 59)
        for bad in ["24:00", "9:00", "09:60", "0900", "aa:bb", ""] { XCTAssertNil(ClockTime.parse(bad), bad) }
        XCTAssertEqual(ClockTime.format(hour: 7, minute: 5), "07:05")
    }
}

final class WeeklyReviewTests: XCTestCase {
    func testDue() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let day: TimeInterval = 24 * 60 * 60
        XCTAssertTrue(WeeklyReview.isDue(lastReview: nil, now: now))
        XCTAssertFalse(WeeklyReview.isDue(lastReview: now.addingTimeInterval(-5.9 * day), now: now))
        XCTAssertTrue(WeeklyReview.isDue(lastReview: now.addingTimeInterval(-6 * day), now: now))
    }

    func testLastReviewIsNewestSnapshotOrNote() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        XCTAssertNil(WeeklyReview.lastReview(in: folder))
        XCTAssertNil(WeeklyReview.lastReview(in: folder.appendingPathComponent("missing")))
        let dates = ["2026-W44.json": 1_000.0, "2026-W44.md": 2_000.0, ".DS_Store": 9_000.0]
        for (name, time) in dates {
            let url = folder.appendingPathComponent(name)
            try Data().write(to: url)
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: time)], ofItemAtPath: url.path)
        }
        XCTAssertEqual(WeeklyReview.lastReview(in: folder), Date(timeIntervalSince1970: 2_000))
    }

    func testReadyDevices() {
        let output = "List of devices attached\n6KK7N19314002478\tdevice\n192.168.1.5:5555\toffline\nABC\tunauthorized\n10.0.0.2:5555\tdevice\n\n"
        XCTAssertEqual(WeeklyReview.readyDevices(adbOutput: output), ["6KK7N19314002478", "10.0.0.2:5555"])
        XCTAssertEqual(WeeklyReview.readyDevices(adbOutput: "List of devices attached\n\n"), [])
        XCTAssertEqual(WeeklyReview.readyDevices(adbOutput: "* daemon started successfully\nList of devices attached\nX\tdevice\n").count, 1)
    }

    func testTerminalScriptQuotesThePath() throws {
        let script = WeeklyReview.terminalScript(repository: #"/Users/me/it's "grod""#)
        XCTAssertTrue(script.contains(#"do script "cd '/Users/me/it'\\''s \"grod\"' && claude '/grod-review'""#), script)
        // The AppleScript compiles; the shell command inside is what Terminal runs.
        let check = Process()
        check.executableURL = URL(fileURLWithPath: "/usr/bin/osacompile")
        check.arguments = ["-o", FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".scpt").path, "-e", script]
        try check.run()
        check.waitUntilExit()
        XCTAssertEqual(check.terminationStatus, 0)
    }
}

final class LauncherTests: XCTestCase {
    func testLauncherDecodeAndRuleApps() throws {
        let json = #"""
        {"schemaVersion":1,"rules":[
          {"id":"ig","title":"Instagram","enabled":true,"android":{"packages":["com.instagram.android"]}},
          {"id":"tt","title":"TikTok","enabled":false,"android":{"packages":["com.zhiliaoapp.musically"]}},
          {"id":"fb","title":"Facebook","enabled":true,"web":{"hosts":["facebook.com"]}}],
         "launcher":{"distracting":["com.twitter.android"],"hidden":[]}}
        """#
        let rules = try RulesSnapshot(data: Data(json.utf8)).document
        XCTAssertEqual(rules.launcher, LauncherSettings(distracting: ["com.twitter.android"], hidden: []))
        XCTAssertEqual(rules.launcherRuleApps.map { "\($0.rule): \($0.package)" }, ["Instagram: com.instagram.android"])
    }
}
