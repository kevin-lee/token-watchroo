import AppKit
import XCTest
@testable import TokenWatchroo

final class AlertThumbnailTests: XCTestCase {

    func testTheTileHasTheColourOfTheRingAtTheAlertsPercent() {
        XCTAssertEqual(AlertThumbnail.style(for: "threshold80", percent: 72)?.color, RingIcon.warningColor)
        XCTAssertEqual(AlertThumbnail.style(for: "threshold80", percent: 90)?.color, RingIcon.criticalColor)
        XCTAssertEqual(AlertThumbnail.style(for: "threshold95", percent: 90)?.color, RingIcon.criticalColor)
        XCTAssertEqual(AlertThumbnail.style(for: "threshold80", percent: 72)?.drawsArc, true)
    }

    func testWithoutAPercentTheKindGivesTheColour() {
        XCTAssertEqual(AlertThumbnail.style(for: "threshold80", percent: nil)?.color, RingIcon.warningColor)
        XCTAssertEqual(AlertThumbnail.style(for: "threshold95", percent: nil)?.color, RingIcon.criticalColor)
    }

    func testResetIsWhiteWithoutAnArcAndAnUnknownKindHasNoTile() {
        let reset = AlertThumbnail.style(for: "reset", percent: 40)
        XCTAssertEqual(reset?.color, .white)
        XCTAssertEqual(reset?.drawsArc, false)
        XCTAssertNil(AlertThumbnail.style(for: "unknown", percent: 90))
    }
}
