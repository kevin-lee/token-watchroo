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

    /// Level alerts other than 70 and 85 (#9): the band colour of the percent, white under 50 or without a percent.
    func testALevelAlertHasTheColourOfItsBand() {
        XCTAssertEqual(AlertThumbnail.style(for: "level60", percent: 60)?.color, RingIcon.elevatedColor)
        XCTAssertEqual(AlertThumbnail.style(for: "level30", percent: 30)?.color, .white)
        XCTAssertEqual(AlertThumbnail.style(for: "level60", percent: nil)?.color, .white)
        XCTAssertEqual(AlertThumbnail.style(for: "level60", percent: 60)?.drawsArc, true)
        XCTAssertEqual(AlertThumbnail.style(for: "threshold80", percent: 30)?.color, RingIcon.warningColor)
        XCTAssertNil(AlertThumbnail.style(for: "level0", percent: 60))
        XCTAssertNil(AlertThumbnail.style(for: "levelx", percent: 60))
    }

    /// Spend alerts (#105): the band colour of the spend's percent, white under 50, and the reset without an arc.
    func testASpendAlertHasTheColourOfItsBand() {
        XCTAssertEqual(AlertThumbnail.style(for: "spend-pct70", percent: 72)?.color, RingIcon.warningColor)
        XCTAssertEqual(AlertThumbnail.style(for: "spend-amt150", percent: 90)?.color, RingIcon.criticalColor)
        XCTAssertEqual(AlertThumbnail.style(for: "spend-amt150", percent: 30)?.color, .white)
        XCTAssertEqual(AlertThumbnail.style(for: "spend-amt150", percent: 30)?.drawsArc, true)
        let reset = AlertThumbnail.style(for: "spend-reset", percent: 5)
        XCTAssertEqual(reset?.color, .white)
        XCTAssertEqual(reset?.drawsArc, false)
        XCTAssertNil(AlertThumbnail.style(for: "spend-pct0", percent: 60))
        XCTAssertNil(AlertThumbnail.style(for: "spend-pct101", percent: 60))
        XCTAssertNil(AlertThumbnail.style(for: "spend-amt0", percent: 60))
        XCTAssertNil(AlertThumbnail.style(for: "spend-x", percent: 60))
    }
}
