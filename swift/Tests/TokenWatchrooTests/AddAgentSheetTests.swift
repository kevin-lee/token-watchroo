import AppKit
import XCTest
@testable import TokenWatchroo

final class AddAgentSheetTests: XCTestCase {

    @MainActor
    func testPlanChoicesFollowTheArtboard() {
        XCTAssertEqual(Billing.subscription.planChoices, [.individual, .team])
        XCTAssertEqual(Billing.api.planChoices, [.enterprise])
        XCTAssertEqual(AddAgentSheet.alertAtText, "80% and 95% of a window")
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
        XCTAssertEqual(sheet.planTitles, ["Individual", "Team"])
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
}
