import AppKit

/// The settings window (#8): the agent list from `config.json` with Add, Edit and an "Enabled" checkbox per row.
/// There is no artboard for the window. Every change goes through `SettingsStore`, which writes the file and pushes the
/// config to the library. A detected agent without an entry is added to the list (#100).
@MainActor
final class SettingsWindowController: NSObject, NSTableViewDataSource, NSTableViewDelegate {

    static let title = "Token Watchroo Settings"
    static let footnote = "Detected agents are added to this list. Clear Enabled to hide an agent."
    static let contentSize = NSSize(width: 420, height: 300)

    private enum Column: String, CaseIterable {
        case agent = "Agent", billing = "Billing", plan = "Plan", enabled = "Enabled"

        var width: CGFloat {
            switch self {
            case .agent: return 88
            case .billing: return 118
            case .plan: return 70
            case .enabled: return 52
            }
        }

        /// The longest text the column shows: a cell text at 13 pt, or for Enabled the header title at 11 pt.
        var longestText: String {
            switch self {
            case .agent: return AgentId.allCases.map(\.displayName).max { $0.count < $1.count } ?? ""
            case .billing: return Billing.allCases.map(\.displayName).max { $0.count < $1.count } ?? ""
            case .plan: return "Enterprise"
            case .enabled: return rawValue
            }
        }

        /// Plan takes the width that is left, so the columns always fit, also with a scroll bar that is always shown.
        var resizes: Bool {
            switch self {
            case .plan: return true
            case .agent, .billing, .enabled: return false
            }
        }
    }

    private let store: SettingsStore
    private let table = NSTableView()
    private let addButton = NSButton(title: "Add Agent...", target: nil, action: nil)
    private let editButton = NSButton(title: "Edit...", target: nil, action: nil)
    private var shownOnce = false

    private(set) lazy var window: NSWindow = makeWindow()

    init(store: SettingsStore) {
        self.store = store
        super.init()
        store.onChange = { [weak self] in self?.reload() }
    }

    func show() {
        let window = self.window
        if !shownOnce {
            window.center()
            shownOnce = true
        }
        WindowFront.show { window }
    }

    // MARK: test access

    var agents: [AgentConfig] { store.config.agents }
    var isAddEnabled: Bool { _ = window; return addButton.isEnabled }
    var isEditEnabled: Bool { _ = window; return editButton.isEnabled }
    var buttonTitles: [String] {
        _ = window
        return window.contentView?.subviews.compactMap { ($0 as? NSButton)?.title } ?? []
    }

    /// The column titles whose whole width is inside the visible part of the table.
    var fullyVisibleColumnTitles: [String] {
        _ = window
        guard let clip = table.enclosingScrollView?.contentView else { return [] }
        clip.layoutSubtreeIfNeeded()
        return table.tableColumns.indices
            .filter { table.rect(ofColumn: $0).maxX <= clip.bounds.maxX }
            .map { table.tableColumns[$0].title }
    }

    // MARK: window

    private func makeWindow() -> NSWindow {
        let window = NSWindow(contentRect: NSRect(origin: .zero, size: SettingsWindowController.contentSize),
                              styleMask: [.titled, .closable], backing: .buffered, defer: true)
        window.title = SettingsWindowController.title
        window.isReleasedWhenClosed = false

        let content = NSView(frame: NSRect(origin: .zero, size: SettingsWindowController.contentSize))
        let inset: CGFloat = 20
        let width = SettingsWindowController.contentSize.width - 2 * inset

        let heading = NSTextField(labelWithString: "Agents")
        heading.font = .systemFont(ofSize: 13, weight: .semibold)
        heading.textColor = Palette.primaryText
        heading.frame = NSRect(x: inset, y: 268, width: width, height: 16)
        content.addSubview(heading)

        // The default puts 17 pt between columns, which pushed Enabled out of view (#8 manual check).
        table.style = .plain
        table.intercellSpacing = NSSize(width: SettingsWindowController.columnSpacing, height: 0)
        let scrollFrame = SettingsWindowController.tableFrame
        let widths = SettingsWindowController.columnWidths(scrollerStyle: NSScroller.preferredScrollerStyle)
        Column.allCases.forEach { column in
            let tableColumn = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(column.rawValue))
            tableColumn.title = column.rawValue
            tableColumn.width = widths[column.rawValue] ?? column.width
            tableColumn.resizingMask = []
            table.addTableColumn(tableColumn)
        }
        table.columnAutoresizingStyle = .noColumnAutoresizing
        table.dataSource = self
        table.delegate = self
        table.allowsMultipleSelection = false
        table.usesAlternatingRowBackgroundColors = true
        table.rowHeight = 22
        table.target = self
        table.doubleAction = #selector(editSelected(_:))

        let scroll = NSScrollView(frame: scrollFrame)
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.borderType = .bezelBorder
        content.addSubview(scroll)

        var x = inset
        for (button, action) in [(addButton, #selector(addAgent(_:))), (editButton, #selector(editSelected(_:)))] {
            button.target = self
            button.action = action
            button.bezelStyle = .rounded
            button.controlSize = .small
            button.font = .systemFont(ofSize: 12)
            button.sizeToFit()
            button.frame.origin = NSPoint(x: x, y: 76)
            x += button.frame.width + 8
            content.addSubview(button)
        }

        let footnote = NSTextField(wrappingLabelWithString: SettingsWindowController.footnote)
        footnote.font = .systemFont(ofSize: 11)
        footnote.textColor = Palette.secondaryText
        footnote.frame = NSRect(x: inset, y: 36, width: width, height: 30)
        content.addSubview(footnote)

        window.contentView = content
        reload()
        return window
    }

    private func reload() {
        let selected = table.selectedRow
        table.reloadData()
        if selected >= 0 && selected < agents.count {
            table.selectRowIndexes(IndexSet(integer: selected), byExtendingSelection: false)
        }
        updateButtons()
    }

    private func updateButtons() {
        let hasSelection = table.selectedRow >= 0 && table.selectedRow < agents.count
        addButton.isEnabled = !agents.addable.isEmpty
        editButton.isEnabled = hasSelection
    }

    // MARK: table

    func numberOfRows(in tableView: NSTableView) -> Int { agents.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard let tableColumn, let column = Column(rawValue: tableColumn.identifier.rawValue),
              agents.indices.contains(row) else { return nil }
        let agent = agents[row]
        switch column {
        case .agent: return SettingsWindowController.cell(agent.id.displayName)
        case .billing: return SettingsWindowController.cell(agent.billing.displayName)
        case .plan: return SettingsWindowController.cell(agent.plan.displayName)
        case .enabled:
            let checkbox = NSButton(checkboxWithTitle: "", target: self, action: #selector(toggleEnabled(_:)))
            checkbox.state = agent.enabled.isOn ? .on : .off
            checkbox.tag = row
            checkbox.setAccessibilityLabel("\(agent.id.displayName) enabled")
            return checkbox
        }
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        updateButtons()
    }

    static let tableFrame = NSRect(x: 20, y: 108, width: 380, height: 150)
    static let columnSpacing: CGFloat = 6

    /// The column widths by title. Plan gets the width inside the border and next to a vertical scroll bar of the
    /// given style: a legacy scroll bar ("Show scroll bars: Always") takes about 15 pt, an overlay one none.
    static func columnWidths(scrollerStyle: NSScroller.Style) -> [String: CGFloat] {
        let visible = NSScrollView.contentSize(forFrameSize: tableFrame.size, horizontalScrollerClass: nil,
                                               verticalScrollerClass: NSScroller.self, borderType: .bezelBorder,
                                               controlSize: .regular, scrollerStyle: scrollerStyle).width
        let fixed = Column.allCases.filter { !$0.resizes }.map(\.width).reduce(0, +)
        let planWidth = max(50, visible - fixed - columnSpacing * CGFloat(Column.allCases.count))
        return Dictionary(uniqueKeysWithValues: Column.allCases.map { ($0.rawValue, $0.resizes ? planWidth : $0.width) })
    }

    /// The width each column needs for its longest text, with 6 pt for the cell padding. For tests.
    static var neededColumnWidths: [String: CGFloat] {
        Dictionary(uniqueKeysWithValues: Column.allCases.map { column in
            let size: CGFloat = column == .enabled ? NSFont.smallSystemFontSize : 13
            let text = column.longestText as NSString
            return (column.rawValue, ceil(text.size(withAttributes: [.font: NSFont.systemFont(ofSize: size)]).width) + 6)
        })
    }

    private static func cell(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 13)
        label.lineBreakMode = .byTruncatingTail
        return label
    }

    // MARK: actions

    @objc func addAgent(_ sender: Any?) {
        let addable = agents.addable
        guard !addable.isEmpty else { return }
        presentSheet(.add(addable: addable))
    }

    @objc func editSelected(_ sender: Any?) {
        let row = table.selectedRow
        guard agents.indices.contains(row) else { return }
        presentSheet(.edit(agents[row]))
    }

    @objc private func toggleEnabled(_ sender: NSButton) {
        guard agents.indices.contains(sender.tag) else { return }
        store.setAgents(agents.settingEnabled(Enabled(isOn: sender.state == .on), for: agents[sender.tag].id))
    }

    /// "Add" and "Save" replace the entry with the same agent in place, or append a new one. "Cancel" changes nothing.
    private func presentSheet(_ mode: AddAgentSheet.Mode) {
        let sheet = AddAgentSheet(mode: mode) { [weak self] agent in
            guard let self, let agent else { return }
            self.store.setAgents(self.agents.adding(agent))
        }
        window.beginSheet(NSWindow(contentViewController: sheet))
    }
}
