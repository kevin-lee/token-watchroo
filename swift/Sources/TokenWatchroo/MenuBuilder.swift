import AppKit

/// The panel from `design/Main.dc.html` as an `NSMenu`: a header item, one card per agent, then the actions.
@MainActor
final class MenuBuilder {

    static let panelWidth: CGFloat = 348
    private static let sidePadding: CGFloat = 10

    private(set) var menu = NSMenu()
    private var header: HeaderView?
    private var cards: [AgentCardView] = []

    /// Rebuilds the whole menu for a snapshot. `target` receives the action selectors.
    func rebuild(snapshot: Snapshot?, receivedAt: Date?, target: AnyObject) -> NSMenu {
        let menu = NSMenu()
        menu.autoenablesItems = false
        cards = []

        let header = HeaderView(receivedAt: receivedAt, target: target)
        let headerItem = NSMenuItem()
        headerItem.view = header
        menu.addItem(headerItem)
        self.header = header

        let now = Date()
        if let snapshot, !snapshot.agents.isEmpty {
            for agent in snapshot.agents {
                let card = AgentCardView(agent: agent, now: now)
                let container = NSView(frame: NSRect(x: 0, y: 0, width: MenuBuilder.panelWidth, height: card.frame.height + 8))
                card.frame.origin = NSPoint(x: MenuBuilder.sidePadding, y: 4)
                container.addSubview(card)
                let item = NSMenuItem()
                item.view = container
                menu.addItem(item)
                cards.append(card)
            }
        } else {
            let empty = NSMenuItem(title: snapshot == nil ? "Waiting for the first refresh…" : "No agent detected. Run claude or codex once.",
                                   action: nil, keyEquivalent: "")
            empty.isEnabled = false
            menu.addItem(empty)
        }

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

    /// Called by the 1 s timer while the menu is open: refreshes "Updated N s ago" and every countdown.
    func tick(snapshot: Snapshot?, receivedAt: Date?) {
        header?.update(receivedAt: receivedAt)
        guard let snapshot else { return }
        let now = Date()
        for (card, agent) in zip(cards, snapshot.agents) {
            card.update(agent: agent, now: now)
        }
    }
}

/// "Token Watchroo" with "Updated N s ago" and a refresh glyph, per the panel header of `design/Main.dc.html`.
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
