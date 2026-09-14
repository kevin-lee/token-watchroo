import AppKit
import XCTest
@testable import TokenWatchroo

final class StatusItemControllerTests: XCTestCase {

    private static func window(_ id: WindowId, _ percent: Double) -> UsageWindow {
        UsageWindow(id: id, usedPercent: percent, resetsAt: 1_789_189_920, windowLength: 18_000)
    }

    private static func claude() -> AgentSnapshot {
        claude(session: 72, weekly: 38, opus: 82)
    }

    private static func claudeLater() -> AgentSnapshot {
        claude(session: 74, weekly: 39, opus: 83)
    }

    private static func claude(session: Double, weekly: Double, opus: Double) -> AgentSnapshot {
        AgentSnapshot(id: .claudeCode, planLabel: "Max 5x", status: .ok,
                      windows: [window(.session, session), window(.weekly, weekly), window(.model("Opus"), opus)],
                      spend: nil, source: .api, fetchedAt: 1, error: nil)
    }

    private static func codex() -> AgentSnapshot {
        AgentSnapshot(id: .codex, planLabel: "Team", status: .ok,
                      windows: [window(.session, 20), window(.weekly, 10)],
                      spend: nil, source: .localLog, fetchedAt: 1, error: "Network error: curl 6")
    }

    private static func snapshot(_ agents: [AgentSnapshot]) -> Snapshot {
        guard !agents.isEmpty else {
            return Snapshot(updatedAt: 1, agents: [], menubar: MenubarState(kind: .unavailable, usedPercent: nil, resetsAt: nil))
        }
        let highest = agents.flatMap(\.windows).map(\.usedPercent).max()
        return Snapshot(updatedAt: 1, agents: agents, menubar: MenubarState(kind: .normal, usedPercent: highest, resetsAt: nil))
    }

    @MainActor
    private func open(_ controller: StatusItemController) throws -> NSMenu {
        let menu = try XCTUnwrap(controller.statusMenu)
        controller.menuWillOpen(menu)
        return menu
    }

    /// The card items sit right after the header, one per agent in order, each card and its container at the height
    /// the agent needs.
    @MainActor
    private func assertCards(in menu: NSMenu, match agents: [AgentSnapshot],
                             file: StaticString = #filePath, line: UInt = #line) {
        let items = menu.items.filter { $0.view?.subviews.first is AgentCardView }
        let cards = items.compactMap { $0.view?.subviews.first as? AgentCardView }
        XCTAssertEqual(cards.map(\.agentId), agents.map(\.id), file: file, line: line)
        if !agents.isEmpty {
            XCTAssertEqual(items.map { menu.index(of: $0) }, Array(1...agents.count), file: file, line: line)
        }
        for ((item, card), agent) in zip(zip(items, cards), agents) {
            let height = AgentCardView.height(for: agent)
            XCTAssertEqual(card.frame.height, height, file: file, line: line)
            XCTAssertEqual(item.view?.frame.height, height + 8, file: file, line: line)
        }
    }

    @MainActor
    func testOneAgentSignedOutWhileOpenLeavesOnlyTheOtherCardAtItsHeight() throws {
        let claude = Self.claude()
        let codex = Self.codex()
        XCTAssertNotEqual(AgentCardView.height(for: claude), AgentCardView.height(for: codex))
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: Self.snapshot([claude, codex]))
        let menu = try open(controller)
        defer { controller.menuDidClose(menu) }

        controller.apply(snapshot: Self.snapshot([codex]))
        XCTAssertIdentical(controller.statusMenu, menu)
        assertCards(in: menu, match: [codex])
        XCTAssertEqual(menu.items.count, 7)

        controller.apply(snapshot: Self.snapshot([claude, codex]))
        assertCards(in: menu, match: [claude, codex])
        XCTAssertEqual(menu.items.count, 8)

        controller.apply(snapshot: Self.snapshot([claude]))
        assertCards(in: menu, match: [claude])
        XCTAssertEqual(menu.items.count, 7)
    }

    @MainActor
    func testAllAgentsSignedOutWhileOpenShowsOnlyTheEmptyItemAndDashes() throws {
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: Self.snapshot([Self.claude(), Self.codex()]))
        let menu = try open(controller)
        defer { controller.menuDidClose(menu) }

        controller.apply(snapshot: Self.snapshot([]))
        XCTAssertIdentical(controller.statusMenu, menu)
        assertCards(in: menu, match: [])
        XCTAssertEqual(menu.items[1].title, MenuBuilder.noAgentTitle)
        XCTAssertFalse(menu.items[1].isEnabled)
        XCTAssertEqual(menu.items.count, 7)
        XCTAssertEqual(controller.menubarLabel, " --")

        controller.apply(snapshot: Self.snapshot([]))
        XCTAssertEqual(menu.items.filter { $0.title == MenuBuilder.noAgentTitle }.count, 1)
        XCTAssertEqual(menu.items.count, 7)
    }

    @MainActor
    func testSigningInWhileOpenAddsTheCardsToTheSameMenu() throws {
        let claude = Self.claude()
        let codex = Self.codex()
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: Self.snapshot([]))
        let menu = try open(controller)
        defer { controller.menuDidClose(menu) }

        controller.apply(snapshot: Self.snapshot([codex]))
        XCTAssertIdentical(controller.statusMenu, menu)
        assertCards(in: menu, match: [codex])
        XCTAssertFalse(menu.items.contains { $0.title == MenuBuilder.noAgentTitle })

        controller.apply(snapshot: Self.snapshot([claude, codex]))
        assertCards(in: menu, match: [claude, codex])
        XCTAssertEqual(menu.items.count, 8)
    }

    @MainActor
    func testUnchangedAgentsKeepTheirCardViews() throws {
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: Self.snapshot([Self.claude(), Self.codex()]))
        let menu = try open(controller)
        defer { controller.menuDidClose(menu) }
        let before = menu.items.compactMap { $0.view?.subviews.first as? AgentCardView }

        controller.apply(snapshot: Self.snapshot([Self.claudeLater(), Self.codex()]))
        let after = menu.items.compactMap { $0.view?.subviews.first as? AgentCardView }
        XCTAssertEqual(before.count, 2)
        XCTAssertEqual(after.count, before.count)
        XCTAssertTrue(zip(before, after).allSatisfy { $0 === $1 })
    }

    @MainActor
    func testClosingTheMenuAfterASignOutRebuildsIt() throws {
        let codex = Self.codex()
        let controller = StatusItemController(core: .shared)
        controller.apply(snapshot: Self.snapshot([Self.claude(), codex]))
        let menu = try open(controller)
        controller.apply(snapshot: Self.snapshot([codex]))

        controller.menuDidClose(menu)
        let rebuilt = expectation(description: "the menu is rebuilt after it closes")
        DispatchQueue.main.async { rebuilt.fulfill() }
        wait(for: [rebuilt], timeout: 2)

        let after = try XCTUnwrap(controller.statusMenu)
        XCTAssertNotIdentical(after, menu)
        assertCards(in: after, match: [codex])
        XCTAssertEqual(after.items.count, 7)
    }
}
