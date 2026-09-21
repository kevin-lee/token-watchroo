import XCTest
@testable import TokenWatchroo

final class GarbageCollectorSettingsTests: XCTestCase {

    private let variable = "GC_MAXIMUM_HEAP_SIZE"

    func testTheHeapCapIsSetWhenTheEnvironmentHasNone() {
        unsetenv(variable)
        defer { unsetenv(variable) }

        GarbageCollectorSettings.apply()

        XCTAssertEqual(GarbageCollectorSettings.current(variable), "512M")
    }

    func testAnExplicitValueIsKept() {
        setenv(variable, "1G", 1)
        defer { unsetenv(variable) }

        GarbageCollectorSettings.apply()

        XCTAssertEqual(GarbageCollectorSettings.current(variable), "1G")
    }
}
