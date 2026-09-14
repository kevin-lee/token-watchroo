import AppKit

/// The panel from `.ai/docs/design/ui/Main.dc.html` as an `NSMenu`: a header item, one card per agent or the
/// empty-state item, then the actions. While the menu is open, `tick` keeps that body in step with each snapshot.
@MainActor
final class MenuBuilder {

    static let panelWidth: CGFloat = 348
    private static let sidePadding: CGFloat = 10
    static let waitingTitle = "Waiting for the first refresh…"
    static let noAgentTitle = "No agent detected. Run claude or codex once."
    /// The header is item 0.
    private static let bodyIndex = 1

    /// What the menu shows between the header and the actions.
    private enum Body {
        case waiting, noAgents, cards([AgentCardView])
    }

    private(set) var menu = NSMenu()
    private var header: HeaderView?
    private var body: Body = .waiting
    private var bodyItems: [NSMenuItem] = []

    /// Rebuilds the whole menu for a snapshot. `target` receives the action selectors.
    func rebuild(snapshot: Snapshot?, receivedAt: Date?, target: AnyObject) -> NSMenu {
        let menu = NSMenu()
        menu.autoenablesItems = false

        let header = HeaderView(receivedAt: receivedAt, target: target)
        let headerItem = NSMenuItem()
        headerItem.view = header
        menu.addItem(headerItem)
        self.header = header

        let next = MenuBuilder.makeBody(snapshot: snapshot, now: Date())
        next.items.forEach(menu.addItem(_:))
        bodyItems = next.items
        body = next.body

        menu.addItem(.separator())

        let refresh = NSMenuItem(title: "Refresh now", action: #selector(StatusItemController.refreshNow(_:)), keyEquivalent: "r")
        refresh.target = target
        menu.addItem(refresh)

        let launch = NSMenuItem(title: "Launch at Login", action: #selector(StatusItemController.toggleLaunchAtLogin(_:)), keyEquivalent: "")
        launch.target = target
        launch.isEnabled = LaunchAtLogin.isAvailable
        launch.state = LaunchAtLogin.isEnabled ? .on : .off
        menu.addItem(launch)

        menu.addItem(.separator())

        let quit = NSMenuItem(title: "Quit Token Watchroo", action: #selector(StatusItemController.quit(_:)), keyEquivalent: "q")
        quit.target = target
        menu.addItem(quit)

        self.menu = menu
        return menu
    }

    /// Called by the 1 s timer while the menu is open and for every snapshot that arrives while it is open. Cards are
    /// updated in place only when they show the same agents in the same order at the same heights. Otherwise the
    /// cards or the empty-state item are replaced in the open menu, so an agent that signs out or in never leaves a
    /// stale, duplicate, or clipped card.
    func tick(snapshot: Snapshot?, receivedAt: Date?) {
        header?.update(receivedAt: receivedAt)
        guard let snapshot else { return }
        let now = Date()
        switch body {
        case .cards(let cards) where MenuBuilder.fits(cards: cards, agents: snapshot.agents):
            for (card, agent) in zip(cards, snapshot.agents) {
                card.update(agent: agent, now: now)
            }
        case .noAgents where snapshot.agents.isEmpty:
            break
        case .waiting, .noAgents, .cards:
            replaceBody(snapshot: snapshot, now: now)
        }
    }

    /// One card item per agent, or one disabled item saying why there is none.
    private static func makeBody(snapshot: Snapshot?, now: Date) -> (items: [NSMenuItem], body: Body) {
        guard let snapshot, !snapshot.agents.isEmpty else {
            let empty = NSMenuItem(title: snapshot == nil ? MenuBuilder.waitingTitle : MenuBuilder.noAgentTitle,
                                   action: nil, keyEquivalent: "")
            empty.isEnabled = false
            return ([empty], snapshot == nil ? .waiting : .noAgents)
        }
        let pairs = snapshot.agents.map { agent -> (NSMenuItem, AgentCardView) in
            let card = AgentCardView(agent: agent, now: now)
            let container = NSView(frame: NSRect(x: 0, y: 0, width: MenuBuilder.panelWidth, height: card.frame.height + 8))
            card.frame.origin = NSPoint(x: MenuBuilder.sidePadding, y: 4)
            container.addSubview(card)
            let item = NSMenuItem()
            item.view = container
            return (item, card)
        }
        return (pairs.map(\.0), .cards(pairs.map(\.1)))
    }

    /// Same agent ids in the same order, and every card already at the height its agent needs.
    private static func fits(cards: [AgentCardView], agents: [AgentSnapshot]) -> Bool {
        cards.map(\.agentId) == agents.map(\.id)
            && zip(cards, agents).allSatisfy { card, agent in card.frame.height == AgentCardView.height(for: agent) }
    }

    private func replaceBody(snapshot: Snapshot, now: Date) {
        bodyItems.forEach(menu.removeItem(_:))
        let next = MenuBuilder.makeBody(snapshot: snapshot, now: now)
        for (offset, item) in next.items.enumerated() {
            menu.insertItem(item, at: MenuBuilder.bodyIndex + offset)
        }
        bodyItems = next.items
        body = next.body
    }
}

/// "Token Watchroo" with "Updated N s ago" and a refresh glyph, per the panel header of
/// `.ai/docs/design/ui/Main.dc.html`.
final class HeaderView: NSView {

    private let updated = NSTextField(labelWithString: "")

    init(receivedAt: Date?, target: AnyObject) {
        super.init(frame: NSRect(x: 0, y: 0, width: MenuBuilder.panelWidth, height: 30))

        let title = NSTextField(labelWithString: "Token Watchroo")
        title.font = .systemFont(ofSize: 13, weight: .semibold)
        title.textColor = Palette.primaryText
        title.frame = NSRect(x: 14, y: 8, width: 160, height: 16)
        addSubview(title)

        updated.font = .systemFont(ofSize: 11)
        updated.textColor = Palette.secondaryText
        updated.alignment = .right
        updated.frame = NSRect(x: 150, y: 9, width: 160, height: 14)
        addSubview(updated)

        let button = NSButton(image: NSImage(systemSymbolName: "arrow.clockwise", accessibilityDescription: "Refresh") ?? NSImage(),
                              target: target, action: #selector(StatusItemController.refreshNow(_:)))
        button.isBordered = false
        button.frame = NSRect(x: MenuBuilder.panelWidth - 14 - 18, y: 6, width: 18, height: 18)
        addSubview(button)

        update(receivedAt: receivedAt)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(receivedAt: Date?) {
        updated.stringValue = Formatting.updatedAgo(since: receivedAt)
    }
}
