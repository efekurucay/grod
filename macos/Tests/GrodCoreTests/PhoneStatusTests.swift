import Foundation
import XCTest
@testable import GrodCore

final class PhoneStatusTests: XCTestCase {
    func testLinkFromSerial() {
        XCTAssertEqual(PhoneStatus.Link(serial: "R58M12ABCDE"), .usb)
        XCTAssertEqual(PhoneStatus.Link(serial: "192.168.1.5:5555"), .wifi)
        XCTAssertEqual(PhoneStatus.Link(serial: "adb-R58M1-abc._adb-tls-connect._tcp"), .usb)
    }

    func testPhoneRevision() {
        XCTAssertEqual(PhoneStatus.revision(ofPhoneRules: Data(#"{"schemaVersion":1,"rules":[],"revision":"abc123def456"}"#.utf8)), "abc123def456")
        XCTAssertNil(PhoneStatus.revision(ofPhoneRules: Data(#"{"schemaVersion":1,"rules":[]}"#.utf8)))
        XCTAssertNil(PhoneStatus.revision(ofPhoneRules: Data("cat: No such file or directory".utf8)))
    }

    func testText() {
        XCTAssertEqual(PhoneStatus.connected(.usb, .upToDate).title, "Phone connected over USB · up to date")
        XCTAssertEqual(PhoneStatus.connected(.wifi, .syncing).title, "Phone connected over Wi-Fi · syncing…")
        XCTAssertEqual(PhoneStatus.notConnected(waiting: true).short, "Waiting to sync")
        XCTAssertEqual(PhoneStatus.notConnected(waiting: false).detail, "Changes reach it the next time you connect it (USB or Wi-Fi).")
    }
}
