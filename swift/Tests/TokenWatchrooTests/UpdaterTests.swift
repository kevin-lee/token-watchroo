import AppKit
import Sparkle
import XCTest
@testable import TokenWatchroo

final class UpdaterTests: XCTestCase {

    private static let feed = "https://example.com/appcast-arm64.xml"
    private static let key = "iv5/vKHASdo08/ztl7Jkz2UkODe+k1t1G4J2aQu0EX0="

    @MainActor
    func testIsConfiguredNeedsBothAFeedAndAKey() {
        XCTAssertTrue(Updater.isConfigured(infoDictionary: ["SUFeedURL": Self.feed, "SUPublicEDKey": Self.key]))
        XCTAssertFalse(Updater.isConfigured(infoDictionary: ["SUFeedURL": Self.feed]))
        XCTAssertFalse(Updater.isConfigured(infoDictionary: ["SUPublicEDKey": Self.key]))
        XCTAssertFalse(Updater.isConfigured(infoDictionary: ["SUFeedURL": "", "SUPublicEDKey": Self.key]))
        XCTAssertFalse(Updater.isConfigured(infoDictionary: ["SUFeedURL": Self.feed, "SUPublicEDKey": ""]))
        XCTAssertFalse(Updater.isConfigured(infoDictionary: nil))
    }

    @MainActor
    func testAnUpdaterThatDidNotStartCannotCheck() {
        XCTAssertFalse(Updater().canCheckForUpdates)
    }

    func testNoUpdateAndACancelledInstallAreExpectedEnds() {
        func sparkle(_ error: SUError) -> NSError {
            NSError(domain: SUSparkleErrorDomain, code: Int(error.rawValue))
        }
        XCTAssertTrue(Updater.isExpectedEnd(sparkle(.noUpdateError)))
        XCTAssertTrue(Updater.isExpectedEnd(sparkle(.installationCanceledError)))
        XCTAssertFalse(Updater.isExpectedEnd(sparkle(.downloadError)))
        XCTAssertFalse(Updater.isExpectedEnd(NSError(domain: NSURLErrorDomain, code: Int(SUError.noUpdateError.rawValue))))
    }

    @MainActor
    func testTheItemIsDisabledWithoutAnUpdaterOnEveryRebuildAndOnOpening() throws {
        let controller = StatusItemController(core: .shared, strings: TestStrings.en)
        let menu = try XCTUnwrap(controller.statusMenu)
        let item = try XCTUnwrap(menu.items.first { $0.title == MenuBuilder.checkForUpdatesTitle(TestStrings.en) })
        XCTAssertFalse(item.isEnabled)

        controller.menuWillOpen(menu)
        defer { controller.menuDidClose(menu) }
        XCTAssertFalse(item.isEnabled)
    }

    @MainActor
    func testTheBuilderSetsTheItemsEnabledState() throws {
        let builder = MenuBuilder(strings: TestStrings.en)
        let target = NSObject()
        let enabled = builder.rebuild(snapshot: nil, receivedAt: nil, target: target, canCheckForUpdates: true)
        let item = try XCTUnwrap(enabled.items.first { $0.title == MenuBuilder.checkForUpdatesTitle(TestStrings.en) })
        XCTAssertTrue(item.isEnabled)

        builder.setCanCheckForUpdates(false)
        XCTAssertFalse(item.isEnabled)
        builder.setCanCheckForUpdates(true)
        XCTAssertTrue(item.isEnabled)

        let disabled = builder.rebuild(snapshot: nil, receivedAt: nil, target: target, canCheckForUpdates: false)
        XCTAssertFalse(try XCTUnwrap(disabled.items.first { $0.title == MenuBuilder.checkForUpdatesTitle(TestStrings.en) }).isEnabled)
    }
}
