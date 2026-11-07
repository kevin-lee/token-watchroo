import AppKit
import XCTest
@testable import TokenWatchroo

final class UsageBandTests: XCTestCase {

    func testBandsAtTheBoundariesOfTheShownPercent() {
        let cases: [(Double, UsageBand)] = [
            (0, .normal), (50, .normal), (50.4, .normal), (50.5, .elevated), (70, .elevated),
            (70.5, .warning), (85, .warning), (85.5, .critical), (100, .critical),
        ]
        for (percent, band) in cases {
            XCTAssertEqual(UsageBand.of(percent: percent), band, "\(percent)")
        }
    }

    func testThePaletteFollowsTheBands() {
        XCTAssertEqual(Palette.percentText(for: 50), Palette.primaryText)
        XCTAssertEqual(Palette.percentText(for: 90), Palette.red)
        XCTAssertNotEqual(Palette.percentText(for: 60), Palette.primaryText)
        XCTAssertNotEqual(Palette.percentText(for: 60), Palette.percentText(for: 80))
        XCTAssertEqual(Palette.barFill(percent: 50, normal: Palette.sessionFill), Palette.sessionFill)
        XCTAssertEqual(Palette.barFill(percent: 60, normal: Palette.sessionFill), Palette.blue)
        XCTAssertEqual(Palette.barFill(percent: 80, normal: Palette.sessionFill), Palette.amber)
        XCTAssertEqual(Palette.barFill(percent: 90, normal: Palette.sessionFill), Palette.red)
    }

    func testTheElevatedStatusAndRing() {
        XCTAssertEqual(Palette.statusPill(for: .elevated).0, "Over half")
        XCTAssertEqual(RingIcon.tint(for: .elevated), RingIcon.elevatedColor)
        XCTAssertNil(RingIcon.color(for: .normal))
        XCTAssertEqual(RingIcon.color(for: .elevated), RingIcon.elevatedColor)
        XCTAssertEqual(RingIcon.color(for: .warning), RingIcon.warningColor)
        XCTAssertEqual(RingIcon.color(for: .critical), RingIcon.criticalColor)
    }
}
