import AppKit
import XCTest
@testable import TokenWatchroo

final class AddAgentSheetTests: XCTestCase {

    @MainActor
    func testPlanChoicesFollowTheArtboard() {
        XCTAssertEqual(Billing.subscription.planChoices, [.individual, .team, .enterprise])
        XCTAssertEqual(Billing.api.planChoices, [.enterprise])
    }

    @MainActor
    private func sheet(_ mode: AddAgentSheet.Mode) -> AddAgentSheet {
        let sheet = AddAgentSheet(mode: mode) { _ in }
        _ = sheet.view
        return sheet
    }

    @MainActor
    func testTheSubscriptionVariantShowsTheAlertAtRow() {
        let sheet = sheet(.add(addable: [.codex]))
        XCTAssertEqual(sheet.selectedBilling, .subscription)
        XCTAssertEqual(sheet.planTitles, ["Individual", "Team", "Enterprise"])
        XCTAssertEqual(sheet.subtitleText, AddAgentSheet.subscriptionSubtitle)
        XCTAssertEqual(sheet.hintText, AddAgentSheet.subscriptionHint)
        XCTAssertTrue(sheet.isAlertAtVisible)
        XCTAssertEqual(sheet.result, AgentConfig.defaults(.codex))
    }

    @MainActor
    func testTheApiVariantHidesTheAlertAtRow() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.selectBilling(.api)
        XCTAssertEqual(sheet.planTitles, ["Enterprise"])
        XCTAssertEqual(sheet.subtitleText, AddAgentSheet.apiSubtitle)
        XCTAssertEqual(sheet.hintText, AddAgentSheet.apiHint)
        XCTAssertFalse(sheet.isAlertAtVisible)
        XCTAssertEqual(sheet.result, AgentConfig(id: .codex, billing: .api, plan: .enterprise, enabled: .on))
    }

    /// Without the resize, the API variant kept the height of the subscription variant (#8 manual check).
    @MainActor
    func testTheSheetFitsItsContentAfterABillingChange() {
        let sheet = sheet(.add(addable: [.codex]))
        let window = NSWindow(contentViewController: sheet)
        let subscriptionHeight = sheet.fittingHeight
        sheet.selectBilling(.api)
        XCTAssertLessThan(sheet.fittingHeight, subscriptionHeight)
        XCTAssertEqual(window.contentLayoutRect.height, sheet.fittingHeight, accuracy: 1)
        sheet.selectBilling(.subscription)
        XCTAssertEqual(window.contentLayoutRect.height, subscriptionHeight, accuracy: 1)
    }

    @MainActor
    func testEditKeepsTheAgentThePlanAndTheEnabledState() {
        let entry = AgentConfig(id: .claudeCode, billing: .subscription, plan: .team, enabled: .off)
        let sheet = sheet(.edit(entry))
        XCTAssertEqual(sheet.selectedAgent, .claudeCode)
        XCTAssertEqual(sheet.result, entry)
    }

    // MARK: alert levels (#9)

    @MainActor
    func testANewSheetShowsTheDefaultLevelsWithTheirChips() {
        let sheet = sheet(.add(addable: [.codex]))
        XCTAssertEqual(sheet.levelTexts, ["70", "85"])
        XCTAssertEqual(sheet.levelChips, ["Amber · is near its limit", "Red · is almost out"])
        XCTAssertEqual(sheet.alertAtHintText, AddAgentSheet.alertAtHint)
        XCTAssertTrue(sheet.isAddLevelEnabled)
        XCTAssertTrue(sheet.isConfirmEnabled)
        XCTAssertFalse(sheet.isNoLevelsVisible)
    }

    @MainActor
    func testEditShowsTheEntrysLevelsAndKeepsThem() {
        var entry = AgentConfig.defaults(.claudeCode)
        entry.alertLevels = [50, 90]
        let sheet = sheet(.edit(entry))
        XCTAssertEqual(sheet.levelTexts, ["50", "90"])
        XCTAssertEqual(sheet.levelChips, ["Blue · is near its limit", "Red · is almost out"])
        XCTAssertEqual(sheet.result, entry)
    }

    @MainActor
    func testPlusAddsTheNextLevelAndMinusRemovesARow() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.addLevel()
        XCTAssertEqual(sheet.levelTexts, ["70", "85", "90"])
        sheet.removeLevel(at: 0)
        XCTAssertEqual(sheet.levelTexts, ["85", "90"])
        XCTAssertEqual(sheet.result?.alertLevels, [85, 90])
    }

    @MainActor
    func testNoLevelsShowsTheHintAndGivesAnEmptyList() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.removeLevel(at: 1)
        sheet.removeLevel(at: 0)
        XCTAssertEqual(sheet.levelTexts, [])
        XCTAssertTrue(sheet.isNoLevelsVisible)
        XCTAssertEqual(sheet.alertAtHintText, AddAgentSheet.noLevelsHint)
        XCTAssertTrue(sheet.isConfirmEnabled)
        XCTAssertEqual(sheet.result?.alertLevels, [])
    }

    @MainActor
    func testLevelsLessThanFiveApartAreMarkedAndDisableSave() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.setLevelText("72", at: 1)
        XCTAssertEqual(sheet.levelTexts, ["70", "72"])
        XCTAssertTrue(sheet.isLevelMarked(at: 0))
        XCTAssertTrue(sheet.isLevelMarked(at: 1))
        XCTAssertEqual(sheet.alertAtHintText, AlertLevelRules.gapProblem)
        XCTAssertFalse(sheet.isConfirmEnabled)
        XCTAssertFalse(sheet.isAddLevelEnabled)
        sheet.setLevelText("75", at: 1)
        XCTAssertFalse(sheet.isLevelMarked(at: 0))
        XCTAssertTrue(sheet.isConfirmEnabled)
    }

    @MainActor
    func testATextThatIsNotALevelGivesTheNumberHint() {
        let sheet = sheet(.add(addable: [.codex]))
        for text in ["abc", "0", "101", "7.5", ""] {
            sheet.setLevelText(text, at: 0)
            XCTAssertEqual(sheet.alertAtHintText, AlertLevelRules.numberProblem, text)
            XCTAssertTrue(sheet.isLevelMarked(at: 0), text)
            XCTAssertFalse(sheet.isConfirmEnabled, text)
        }
    }

    @MainActor
    func testACommitSortsTheRows() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.setLevelText("90", at: 0)
        XCTAssertEqual(sheet.levelTexts, ["85", "90"])
        sheet.setLevelText("60", at: 1)
        XCTAssertEqual(sheet.levelTexts, ["60", "85"])
    }

    @MainActor
    func testFiveRowsDisablePlusWithTheMaximumHint() {
        let sheet = sheet(.add(addable: [.codex]))
        sheet.addLevel()
        sheet.addLevel()
        sheet.addLevel()
        XCTAssertEqual(sheet.levelTexts, ["70", "85", "90", "95", "100"])
        XCTAssertFalse(sheet.isAddLevelEnabled)
        XCTAssertEqual(sheet.alertAtHintText, AddAgentSheet.maxLevelsHint)
        XCTAssertTrue(sheet.isConfirmEnabled)
    }

    @MainActor
    func testAnApiResultKeepsTheEntrysLevels() {
        var entry = AgentConfig.defaults(.codex)
        entry.alertLevels = [40]
        let sheet = sheet(.edit(entry))
        sheet.selectBilling(.api)
        XCTAssertEqual(sheet.result?.alertLevels, [40])
        XCTAssertTrue(sheet.isConfirmEnabled)
    }

    func testNextLevelFollowsTheDesign() {
        XCTAssertEqual(AlertLevelRules.nextLevel(after: []), 70)
        XCTAssertEqual(AlertLevelRules.nextLevel(after: [70, 85]), 90)
        XCTAssertEqual(AlertLevelRules.nextLevel(after: [70, 85, 90, 95]), 100)
        XCTAssertEqual(AlertLevelRules.nextLevel(after: [70, 85, 90, 95, 100]), 80)
        XCTAssertEqual(AlertLevelRules.nextLevel(after: [100]), 95)
        XCTAssertNil(AlertLevelRules.nextLevel(after: Array(stride(from: 1, through: 100, by: 5))))
    }
}
