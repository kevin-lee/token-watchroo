import AppKit
import XCTest
@testable import TokenWatchroo

final class SettingsWindowControllerTests: XCTestCase {

    @MainActor
    private func controller(_ agents: [AgentConfig], interval: Int = Config.defaultRefreshIntervalSeconds)
        -> SettingsWindowController {
        var config = Config.defaults(stateDir: "/state")
        config.agents = agents
        config.refreshIntervalSeconds = interval
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
    func testTheButtons() {
        XCTAssertEqual(controller([AgentConfig.defaults(.codex)]).buttonTitles,
                       ["Add Agent...", "Edit...", "Move Up", "Move Down"])
    }

    @MainActor
    func testMoveButtonsFollowTheSelection() {
        let controller = controller([AgentConfig.defaults(.claudeCode), AgentConfig.defaults(.codex)])
        XCTAssertFalse(controller.isMoveUpEnabled)
        XCTAssertFalse(controller.isMoveDownEnabled)
        controller.selectRow(0)
        XCTAssertFalse(controller.isMoveUpEnabled)
        XCTAssertTrue(controller.isMoveDownEnabled)
        controller.selectRow(1)
        XCTAssertTrue(controller.isMoveUpEnabled)
        XCTAssertFalse(controller.isMoveDownEnabled)
        controller.selectRow(0)
        controller.moveSelectedDown(nil)
        XCTAssertEqual(controller.agents.map(\.id), [.codex, .claudeCode])
        XCTAssertTrue(controller.isMoveUpEnabled)
        XCTAssertFalse(controller.isMoveDownEnabled)
    }

    @MainActor
    func testTheIntervalPopUp() {
        let controller = controller([])
        XCTAssertEqual(controller.intervalTitles, RefreshInterval.presets.map(RefreshInterval.title))
        XCTAssertEqual(controller.selectedIntervalTitle, "1 minute")
        controller.chooseInterval(at: 2)
        XCTAssertEqual(controller.selectedIntervalTitle, "5 minutes")
        XCTAssertEqual(self.controller([], interval: 45).selectedIntervalTitle, "45 seconds")
    }
}
