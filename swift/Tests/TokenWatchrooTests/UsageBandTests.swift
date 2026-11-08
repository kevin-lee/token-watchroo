import AppKit
import XCTest
@testable import TokenWatchroo

final class UsageBandTests: XCTestCase {

    func testBandsAtTheBoundariesOfTheShownPercent() {
        let cases: [(Double, UsageBand)] = [
            (0, .normal), (49.4, .normal), (49.5, .elevated), (50, .elevated), (69.4, .elevated), (69.5, .warning),
            (70, .warning), (84.4, .warning), (84.5, .critical), (85, .critical), (100, .critical),
        ]
        for (percent, band) in cases {
            XCTAssertEqual(UsageBand.of(percent: percent), band, "\(percent)")
        }
    }

    func testThePaletteFollowsTheBands() {
        XCTAssertEqual(Palette.percentText(for: 40), Palette.primaryText)
        XCTAssertEqual(Palette.percentText(for: 90), Palette.red)
        XCTAssertNotEqual(Palette.percentText(for: 60), Palette.primaryText)
        XCTAssertNotEqual(Palette.percentText(for: 60), Palette.percentText(for: 80))
        XCTAssertEqual(Palette.barFill(percent: 40, normal: Palette.sessionFill), Palette.sessionFill)
        XCTAssertEqual(Palette.barFill(percent: 60, normal: Palette.sessionFill), Palette.blue)
        XCTAssertEqual(Palette.barFill(percent: 80, normal: Palette.sessionFill), Palette.amber)
        XCTAssertEqual(Palette.barFill(percent: 90, normal: Palette.sessionFill), Palette.red)
    }

    func testTheElevatedStatusAndRing() {
        XCTAssertEqual(Palette.statusPill(for: .elevated, strings: TestStrings.en).0, "Over half")
        XCTAssertEqual(RingIcon.tint(for: .elevated), RingIcon.elevatedColor)
        XCTAssertNil(RingIcon.color(for: .normal))
        XCTAssertEqual(RingIcon.color(for: .elevated), RingIcon.elevatedColor)
        XCTAssertEqual(RingIcon.color(for: .warning), RingIcon.warningColor)
        XCTAssertEqual(RingIcon.color(for: .critical), RingIcon.criticalColor)
    }
}
