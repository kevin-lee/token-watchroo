import XCTest
@testable import TokenWatchroo

final class WindowFrontTests: XCTestCase {

    func testTheOneAddedWindowIsNew() {
        XCTAssertEqual(WindowFront.newWindow(before: [1, 2], after: [1, 2, 3]), 3)
    }

    func testTheAddedWindowIsFoundAnywhereInTheList() {
        XCTAssertEqual(WindowFront.newWindow(before: [1, 2], after: [3, 1, 2]), 3)
    }

    func testNoAddedWindowIsNil() {
        XCTAssertNil(WindowFront.newWindow(before: [1, 2], after: [1, 2]))
    }

    func testAClosedWindowIsNotNew() {
        XCTAssertNil(WindowFront.newWindow(before: [1, 2], after: [1]))
        XCTAssertEqual(WindowFront.newWindow(before: [1, 2], after: [1, 3]), 3)
    }

    func testTwoAddedWindowsAreNil() {
        XCTAssertNil(WindowFront.newWindow(before: [1], after: [1, 2, 3]))
    }
}
