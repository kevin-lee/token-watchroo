import AppKit
import XCTest
@testable import TokenWatchroo

final class SnapshotDecodingTests: XCTestCase {

    /// Captured with lldb from the library callback when no agent was detected (issue #32).
    private static let zeroAgentEnvelope =
        #"{"version":1,"type":"snapshot","seq":35,"data":{"updatedAt":1789358264,"menubar":{"kind":"unavailable"}}}"#

    private static let unavailableAgentEnvelope =
        #"{"version":1,"type":"snapshot","seq":36,"data":{"updatedAt":1789358315,"agents":[{"id":"codex","status":"unavailable","fetchedAt":1789358315,"error":"Not signed in. Run codex once."}],"menubar":{"kind":"unavailable"}}}"#

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
