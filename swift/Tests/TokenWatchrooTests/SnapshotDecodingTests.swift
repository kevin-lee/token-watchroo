import AppKit
import XCTest
@testable import TokenWatchroo

final class SnapshotDecodingTests: XCTestCase {

    /// Captured with lldb from the library callback when no agent was detected (issue #32).
    private static let zeroAgentEnvelope =
        #"{"version":1,"type":"snapshot","seq":35,"data":{"updatedAt":1789358264,"menubar":{"kind":"unavailable"}}}"#

    private static let unavailableAgentEnvelope =
        #"{"version":1,"type":"snapshot","seq":36,"data":{"updatedAt":1789358315,"agents":[{"id":"codex","status":"unavailable","fetchedAt":1789358315,"error":"Not signed in. Run codex once."}],"menubar":{"kind":"unavailable"}}}"#

    /// The shape the library writes for the verified Claude Enterprise response (issue #33).
    private static let spendOnlyEnvelope =
        #"{"version":1,"type":"snapshot","seq":37,"data":{"updatedAt":1789367347,"agents":[{"id":"claude-code","planLabel":"Enterprise","status":"ok","windows":[],"spend":{"currency":"USD","spent":"0.05","limit":"200.00","usedPercent":0.025,"resetsAt":1790812800},"source":"api","fetchedAt":1789367347}],"menubar":{"kind":"normal","usedPercent":0.025}}}"#

    private static let oneWindowEnvelope =
        #"{"version":1,"type":"snapshot","seq":38,"data":{"updatedAt":1789367347,"agents":[{"id":"codex","status":"ok","windows":[{"id":"session","usedPercent":10.0,"resetsAt":1789370000,"windowLength":18000}],"source":"api","fetchedAt":1789367347}],"menubar":{"kind":"normal","usedPercent":10.0}}}"#

    private static let badAmountEnvelope =
        #"{"version":1,"type":"snapshot","seq":39,"data":{"updatedAt":1789367347,"agents":[{"id":"claude-code","status":"ok","windows":[],"spend":{"currency":"USD","spent":"lots","limit":"200.00","usedPercent":0.0},"source":"api","fetchedAt":1789367347}],"menubar":{"kind":"normal","usedPercent":0.0}}}"#

    func testASpendOnlyAgentEnvelopeDecodes() throws {
        let agent = try XCTUnwrap(try Self.snapshot(from: Self.spendOnlyEnvelope).agents.first)
        let spend = try XCTUnwrap(agent.spend)
        XCTAssertTrue(agent.windows.isEmpty)
        XCTAssertEqual(spend.currency, "USD")
        XCTAssertEqual(spend.spent, Decimal(string: "0.05"))
        XCTAssertEqual(spend.limit, Decimal(string: "200.00"))
        XCTAssertEqual(spend.usedPercent, 0.025)
        XCTAssertEqual(spend.resetsAt, 1790812800)
        XCTAssertFalse(spend.isCredits)
    }

    func testAnAgentWithoutSpendDecodesSpendAsNil() throws {
        let agent = try XCTUnwrap(try Self.snapshot(from: Self.oneWindowEnvelope).agents.first)
        XCTAssertNil(agent.spend)
        XCTAssertNil(try XCTUnwrap(try Self.snapshot(from: Self.unavailableAgentEnvelope).agents.first).spend)
    }

    func testANonNumericSpendAmountFailsWithTheCodingPath() {
        switch EnvelopeDecoder.decode(Data(Self.badAmountEnvelope.utf8), after: 0) {
        case .failure(.undecodableBody(let type, let seq, let path, _)):
            XCTAssertEqual(type, "snapshot")
            XCTAssertEqual(seq, 39)
            XCTAssertEqual(path, "data.agents[0].spend.spent")
        case let other:
            XCTFail("expected an undecodable body, got \(other)")
        }
    }

    @MainActor
    func testASpendOnlyCardHasOneRowHeight() throws {
        let spendOnly = try XCTUnwrap(try Self.snapshot(from: Self.spendOnlyEnvelope).agents.first)
        let oneWindow = try XCTUnwrap(try Self.snapshot(from: Self.oneWindowEnvelope).agents.first)
        XCTAssertEqual(AgentCardView.height(for: spendOnly), AgentCardView.height(for: oneWindow))
    }

    private static func snapshot(from json: String) throws -> Snapshot {
        try JSONDecoder().decode(SnapshotEnvelope.self, from: Data(json.utf8)).data
    }

    func testTheZeroAgentEnvelopeFromTheLibraryDecodes() throws {
        let data = Data(Self.zeroAgentEnvelope.utf8)
        let head = try JSONDecoder().decode(EnvelopeHead.self, from: data)
        XCTAssertEqual(head.type, "snapshot")
        XCTAssertEqual(head.seq, 35)
        let snapshot = try JSONDecoder().decode(SnapshotEnvelope.self, from: data).data
        XCTAssertTrue(snapshot.agents.isEmpty)
        XCTAssertEqual(snapshot.menubar.kind, .unavailable)
    }

    func testAnUnavailableAgentWithoutWindowsDecodes() throws {
        let snapshot = try Self.snapshot(from: Self.unavailableAgentEnvelope)
        XCTAssertEqual(snapshot.agents.count, 1)
        let agent = try XCTUnwrap(snapshot.agents.first)
        XCTAssertEqual(agent.id, .codex)
        XCTAssertEqual(agent.status, .unavailable)
        XCTAssertTrue(agent.windows.isEmpty)
        XCTAssertEqual(agent.error, "Not signed in. Run codex once.")
        XCTAssertNil(agent.source)
    }

    @MainActor
    func testTheDecodedZeroAgentSnapshotEmptiesTheOpenMenu() throws {
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: try Self.snapshot(from: Self.unavailableAgentEnvelope))
        let menu = try XCTUnwrap(controller.statusMenu)
        controller.menuWillOpen(menu)
        defer { controller.menuDidClose(menu) }

        controller.apply(snapshot: try Self.snapshot(from: Self.zeroAgentEnvelope))
        XCTAssertEqual(menu.items[1].title, MenuBuilder.noAgentTitle)
        XCTAssertFalse(menu.items.contains { $0.view?.subviews.first is AgentCardView })
        XCTAssertEqual(controller.menubarLabel, " --")
    }
}
