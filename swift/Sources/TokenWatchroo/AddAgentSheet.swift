import AppKit

/// The Add-agent sheet from `.ai/docs/design/ui/AddAgent.dc.html` (#8): agent, billing, plan, and the Alert-at row as
/// fixed text. #9 replaces the fixed text with a control. The API variant has no budget row (m5) and no Alert-at row,
/// because an API entry gives an unavailable card that never alerts.
@MainActor
final class AddAgentSheet: NSViewController {

    enum Mode {
        case add(addable: [AgentId])
        case edit(AgentConfig)
    }

    static let width: CGFloat = 360
    static let addTitle = "Add agent"
    static let editTitle = "Edit agent"
    static let subscriptionSubtitle = "Subscription plan, individual, team or enterprise"
    static let apiSubtitle = "API, enterprise plan"
    static let subscriptionHint = "Individual, Team or Enterprise. Windows and spend limits come from the account."
    static let apiHint = "API budget agents are not supported yet."
    static let alertAtText = "Over 70% and over 85% of a window"

    private static let padding: CGFloat = 16
    private static let fieldWidth = width - 2 * padding

    private let mode: Mode
    private let completion: (AgentConfig?) -> Void

    private let subtitle = AddAgentSheet.label("", size: 12, weight: .regular, color: Palette.badgeText)
    private let agentPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let billingControl = NSSegmentedControl(labels: Billing.allCases.map(\.displayName),
                                                    trackingMode: .selectOne, target: nil, action: nil)
    private let planPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let hint = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.secondaryText)
    private var alertAtRow = NSStackView()

    /// `completion` gets the entry for "Add" or "Save", and nil for "Cancel".
    init(mode: Mode, completion: @escaping (AgentConfig?) -> Void) {
        self.mode = mode
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    // MARK: state, also for tests

    var selectedAgent: AgentId? {
        switch mode {
        case .add(let addable):
            let index = agentPopUp.indexOfSelectedItem
            return addable.indices.contains(index) ? addable[index] : nil
        case .edit(let agent):
            return agent.id
        }
    }

    var selectedBilling: Billing {
        Billing.allCases.indices.contains(billingControl.selectedSegment)
            ? Billing.allCases[billingControl.selectedSegment] : .subscription
    }

    var selectedPlan: PlanKind {
        let choices = selectedBilling.planChoices
        let index = planPopUp.indexOfSelectedItem
        return choices.indices.contains(index) ? choices[index] : choices[0]
    }

    var planTitles: [String] { planPopUp.itemTitles }
    var subtitleText: String { subtitle.stringValue }
    var hintText: String { hint.stringValue }
    var isAlertAtVisible: Bool { !alertAtRow.isHidden }

    func selectBilling(_ billing: Billing) {
        billingControl.selectedSegment = Billing.allCases.firstIndex(of: billing) ?? 0
        billingChanged(nil)
    }

    // MARK: view

    override func loadView() {
        let title: String
        let confirmTitle: String
        switch mode {
        case .add(let addable):
            title = AddAgentSheet.addTitle
            confirmTitle = "Add"
            agentPopUp.addItems(withTitles: addable.map(\.displayName))
        case .edit(let agent):
            title = AddAgentSheet.editTitle
            confirmTitle = "Save"
            agentPopUp.addItems(withTitles: [agent.id.displayName])
            agentPopUp.isEnabled = false
        }

        let header = NSStackView(views: [AddAgentSheet.label(title, size: 15, weight: .semibold,
                                                             color: Palette.primaryText), subtitle])
        header.orientation = .vertical
        header.alignment = .leading
        header.spacing = 2

        billingControl.target = self
        billingControl.action = #selector(billingChanged(_:))

        let alertAt = NSTextField(string: AddAgentSheet.alertAtText)
        alertAt.isEditable = false
        alertAt.isSelectable = false
        alertAt.isBezeled = true
        alertAt.font = .systemFont(ofSize: 13)
        alertAt.textColor = Palette.primaryText
        alertAtRow = AddAgentSheet.field("Alert at", alertAt)

        let fields = NSStackView(views: [
            AddAgentSheet.field("Agent", agentPopUp),
            AddAgentSheet.field("Billing", billingControl),
            AddAgentSheet.field("Plan", planPopUp, hint),
            alertAtRow,
        ])
        fields.orientation = .vertical
        fields.alignment = .leading
        fields.spacing = 12

        let cancel = NSButton(title: "Cancel", target: self, action: #selector(cancel(_:)))
        cancel.keyEquivalent = "\u{1b}"
        let confirm = NSButton(title: confirmTitle, target: self, action: #selector(confirm(_:)))
        confirm.keyEquivalent = "\r"
        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let buttons = NSStackView(views: [spacer, cancel, confirm])
        buttons.orientation = .horizontal
        buttons.spacing = 8

        let stack = NSStackView(views: [header, fields, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: AddAgentSheet.padding, left: AddAgentSheet.padding,
                                        bottom: AddAgentSheet.padding, right: AddAgentSheet.padding)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let container = NSView()
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            container.widthAnchor.constraint(equalToConstant: AddAgentSheet.width),
            buttons.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth),
        ])
        [agentPopUp, billingControl, planPopUp, alertAt].forEach {
            $0.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        }
        view = container

        switch mode {
        case .add:
            selectBilling(.subscription)
        case .edit(let agent):
            selectBilling(agent.billing)
            planPopUp.selectItem(at: agent.billing.planChoices.firstIndex(of: agent.plan) ?? 0)
        }
    }

    /// A new billing gives its own plan list with the first plan selected, its subtitle and hint, and the Alert-at row
    /// only for a subscription.
    @objc private func billingChanged(_ sender: Any?) {
        let billing = selectedBilling
        planPopUp.removeAllItems()
        planPopUp.addItems(withTitles: billing.planChoices.map(\.displayName))
        planPopUp.selectItem(at: 0)
        switch billing {
        case .subscription:
            subtitle.stringValue = AddAgentSheet.subscriptionSubtitle
            hint.stringValue = AddAgentSheet.subscriptionHint
            alertAtRow.isHidden = false
        case .api:
            subtitle.stringValue = AddAgentSheet.apiSubtitle
            hint.stringValue = AddAgentSheet.apiHint
            alertAtRow.isHidden = true
        }
        // The sheet keeps its height unless it is told, which left a gap above the buttons (#8 manual check).
        if let window = view.window {
            view.layoutSubtreeIfNeeded()
            window.setContentSize(view.fittingSize)
        }
    }

    /// The height the sheet needs for its content, for tests.
    var fittingHeight: CGFloat {
        view.layoutSubtreeIfNeeded()
        return view.fittingSize.height
    }

    /// The entry for the current selection. An edited entry keeps its "Enabled" state, a new one is enabled.
    var result: AgentConfig? {
        guard let id = selectedAgent else { return nil }
        let enabled: Enabled
        switch mode {
        case .add: enabled = .on
        case .edit(let agent): enabled = agent.enabled
        }
        return AgentConfig(id: id, billing: selectedBilling, plan: selectedPlan, enabled: enabled)
    }

    @objc private func cancel(_ sender: Any?) {
        finish(nil)
    }

    @objc private func confirm(_ sender: Any?) {
        finish(result)
    }

    private func finish(_ agent: AgentConfig?) {
        if let sheet = view.window, let parent = sheet.sheetParent {
            parent.endSheet(sheet)
        }
        completion(agent)
    }

    // MARK: building blocks

    private static func label(_ text: String, size: CGFloat, weight: NSFont.Weight, color: NSColor) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: size, weight: weight)
        label.textColor = color
        label.lineBreakMode = .byWordWrapping
        label.preferredMaxLayoutWidth = fieldWidth
        return label
    }

    /// A field label above its control, 4 pt apart, with an optional hint under the control.
    private static func field(_ title: String, _ control: NSView, _ hint: NSTextField? = nil) -> NSStackView {
        let views = [label(title, size: 11, weight: .medium, color: Palette.badgeText), control] + (hint.map { [$0] } ?? [])
        let stack = NSStackView(views: views)
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        return stack
    }
}
