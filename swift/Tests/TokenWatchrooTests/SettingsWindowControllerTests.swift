import AppKit
import XCTest
@testable import TokenWatchroo

final class SettingsWindowControllerTests: XCTestCase {

    @MainActor
    private func controller(_ agents: [AgentConfig]) -> SettingsWindowController {
        var config = Config.defaults(stateDir: "/state")
        config.agents = agents
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("SettingsWindowControllerTests.json")
        return SettingsWindowController(store: SettingsStore(config: config, fileURL: url, push: { _ in }, pushDelay: 60))
    }

    /// The default 17 pt column spacing pushed the Enabled column out of view (found in the #8 manual check).
    @MainActor
    func testEveryColumnIsFullyVisible() {
        let controller = controller([AgentConfig.defaults(.codex)])
        XCTAssertEqual(controller.fullyVisibleColumnTitles, ["Agent", "Billing", "Plan", "Enabled"])
    }

    /// With an always-visible scroll bar, Plan showed "Enterpri…" (found in the #8 manual check).
    @MainActor
    func testEveryColumnIsWideEnoughForItsLongestTextWithBothScrollBarStyles() {
        let needed = SettingsWindowController.neededColumnWidths
        for style in [NSScroller.Style.legacy, .overlay] {
            let widths = SettingsWindowController.columnWidths(scrollerStyle: style)
            for (title, width) in needed {
                XCTAssertGreaterThanOrEqual(widths[title] ?? 0, width, "\(title) with scroller style \(style.rawValue)")
            }
        }
    }

    @MainActor
    func testButtonsFollowTheListAndTheSelection() {
        let empty = controller([])
        XCTAssertTrue(empty.isAddEnabled)
        XCTAssertFalse(empty.isEditEnabled)
        let full = controller([AgentConfig.defaults(.claudeCode), AgentConfig.defaults(.codex)])
        XCTAssertFalse(full.isAddEnabled)
    }

    @MainActor
    func testThereIsNoRemoveButton() {
        XCTAssertEqual(controller([AgentConfig.defaults(.codex)]).buttonTitles, ["Add Agent...", "Edit..."])
    }
}
