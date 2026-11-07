import XCTest
@testable import TokenWatchroo

final class RefreshIntervalTests: XCTestCase {

    func testTitles() {
        XCTAssertEqual([15, 60, 120, 3600, 7200, 45].map(RefreshInterval.title),
                       ["15 seconds", "1 minute", "2 minutes", "1 hour", "2 hours", "45 seconds"])
    }

    func testChoicesKeepAnUnknownValueAndClampOutOfRangeValues() {
        XCTAssertEqual(RefreshInterval.choices(current: 60), RefreshInterval.presets)
        XCTAssertEqual(RefreshInterval.choices(current: 45), [45, 60, 120, 300, 600, 1800, 3600])
        XCTAssertEqual(RefreshInterval.choices(current: 5), [15, 60, 120, 300, 600, 1800, 3600])
        XCTAssertEqual(RefreshInterval.choices(current: 15), [15, 60, 120, 300, 600, 1800, 3600])
        XCTAssertEqual(RefreshInterval.choices(current: 9999), RefreshInterval.presets)
    }
}
