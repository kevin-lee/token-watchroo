import AppKit

/// The Add-agent sheet from `.ai/docs/design/ui/AddAgent.dc.html` (#8): agent, billing, plan, and the Alert-at control
/// (#9): one row per window alert level with a number field, a chip with the level's colour and title, and a "−"
/// button, and a "+" button under the rows. Levels that break the rules mark their fields, show the rule in red and
/// disable Add or Save. The API variant has no budget row (m5) and no Alert-at row, because an API entry gives an
/// unavailable card that never alerts.
@MainActor
final class AddAgentSheet: NSViewController, NSTextFieldDelegate {

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
    static let alertAtHint = "A window alerts when its usage reaches a level. Up to 5 levels, at least 5 apart."
    static let noLevelsHint = "No window alerts for this agent."
    static let maxLevelsHint = "5 levels is the maximum."
    static let errorColor = NSColor(srgbRed: 0xC4 / 255, green: 0x29 / 255, blue: 0x1C / 255, alpha: 1)

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
    private let levelRows = NSStackView()
    private var levelFields: [NSTextField] = []
    private var levelChipLabels: [NSTextField] = []
    private var levelChipDots: [NSView] = []
    private let noLevels = AddAgentSheet.label("No levels", size: 12, weight: .regular, color: Palette.secondaryText)
    private let addLevelButton = NSButton(title: "+", target: nil, action: nil)
    private let alertAtHintLabel = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.secondaryText)
    private var confirmButton = NSButton()

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
    var levelTexts: [String] { levelFields.map(\.stringValue) }
    var levelChips: [String] { levelChipLabels.map(\.stringValue) }
    var isAddLevelEnabled: Bool { addLevelButton.isEnabled }
    var isConfirmEnabled: Bool { confirmButton.isEnabled }
    var alertAtHintText: String { alertAtHintLabel.stringValue }
    var isNoLevelsVisible: Bool { !noLevels.isHidden }

    func isLevelMarked(at index: Int) -> Bool {
        levelFields.indices.contains(index) && levelFields[index].layer?.borderWidth ?? 0 > 0
    }

    /// Types `text` into the row and ends the editing, as a user does.
    func setLevelText(_ text: String, at index: Int) {
        guard levelFields.indices.contains(index) else { return }
        levelFields[index].stringValue = text
        updateLevels()
        commitLevels()
    }

    func addLevel() {
        addLevel(nil)
    }

    func removeLevel(at index: Int) {
        guard levelFields.indices.contains(index) else { return }
        var texts = levelTexts
        texts.remove(at: index)
        setLevelRows(texts)
    }

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

        levelRows.orientation = .vertical
        levelRows.alignment = .leading
        levelRows.spacing = 6
        addLevelButton.target = self
        addLevelButton.action = #selector(addLevel(_:))
        addLevelButton.bezelStyle = .smallSquare
        addLevelButton.setAccessibilityLabel("Add a level")
        addLevelButton.widthAnchor.constraint(equalToConstant: 28).isActive = true
        addLevelButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        let levels = NSStackView(views: [levelRows, noLevels, addLevelButton])
        levels.orientation = .vertical
        levels.alignment = .leading
        levels.spacing = 6
        alertAtRow = AddAgentSheet.field("Alert at", levels, alertAtHintLabel)

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
        confirmButton = confirm
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
        [agentPopUp, billingControl, planPopUp].forEach {
            $0.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        }
        view = container

        switch mode {
        case .add:
            setLevelRows(AlertLevelRules.defaults.map(String.init))
            selectBilling(.subscription)
        case .edit(let agent):
            setLevelRows(agent.alertLevels.map(String.init))
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
        updateLevels()
        fitWindow()
    }

    /// The sheet keeps its height unless it is told, which left a gap above the buttons (#8 manual check).
    private func fitWindow() {
        if let window = view.window {
            view.layoutSubtreeIfNeeded()
            window.setContentSize(view.fittingSize)
        }
    }

    // MARK: alert levels (#9)

    /// Replaces the level rows with one row per text.
    private func setLevelRows(_ texts: [String]) {
        levelRows.arrangedSubviews.forEach { $0.removeFromSuperview() }
        levelFields = []
        levelChipLabels = []
        levelChipDots = []
        for (index, text) in texts.enumerated() {
            levelRows.addArrangedSubview(levelRow(text, index: index))
        }
        updateLevels()
        fitWindow()
    }

    private func levelRow(_ text: String, index: Int) -> NSStackView {
        let field = NSTextField(string: text)
        field.font = .systemFont(ofSize: 13)
        field.alignment = .right
        field.delegate = self
        field.wantsLayer = true
        field.layer?.cornerRadius = 4
        field.setAccessibilityLabel("Alert level \(index + 1), percent")
        field.widthAnchor.constraint(equalToConstant: 72).isActive = true
        let percent = AddAgentSheet.label("%", size: 13, weight: .regular, color: Palette.secondaryText)

        let dot = NSView()
        dot.wantsLayer = true
        dot.layer?.cornerRadius = 4
        dot.widthAnchor.constraint(equalToConstant: 8).isActive = true
        dot.heightAnchor.constraint(equalToConstant: 8).isActive = true
        let chip = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.secondaryText)
        chip.lineBreakMode = .byTruncatingTail
        chip.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let remove = NSButton(title: "−", target: self, action: #selector(removeLevelRow(_:)))
        remove.tag = index
        remove.bezelStyle = .smallSquare
        remove.setAccessibilityLabel("Remove this level")
        remove.widthAnchor.constraint(equalToConstant: 28).isActive = true
        remove.heightAnchor.constraint(equalToConstant: 28).isActive = true

        let row = NSStackView(views: [field, percent, dot, chip, spacer, remove])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = 6
        row.setCustomSpacing(12, after: percent)
        row.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        levelFields.append(field)
        levelChipLabels.append(chip)
        levelChipDots.append(dot)
        return row
    }

    /// Marks the fields that break a rule, and updates the chips, the hint, "+" and Add or Save.
    private func updateLevels() {
        let texts = levelTexts
        let marked = AlertLevelRules.marked(texts)
        let problem = AlertLevelRules.problem(texts)
        for (index, field) in levelFields.enumerated() {
            field.layer?.borderWidth = marked.contains(index) ? 1.5 : 0
            field.layer?.borderColor = AddAgentSheet.errorColor.cgColor
            let level = AlertLevelRules.level(texts[index])
            levelChipLabels[index].stringValue = level.map(AlertLevelRules.chip(for:)) ?? ""
            let dot = levelChipDots[index].layer
            let color = level.flatMap { RingIcon.color(for: UsageBand.of(percent: Double($0))) }
            dot?.backgroundColor = level == nil ? nil : (color ?? .white).cgColor
            dot?.borderWidth = level != nil && color == nil ? 1 : 0
            dot?.borderColor = Palette.secondaryText.cgColor
        }
        noLevels.isHidden = !texts.isEmpty
        let levels = texts.compactMap(AlertLevelRules.level)
        addLevelButton.isEnabled = texts.count < AlertLevelRules.maxCount && problem == nil
            && AlertLevelRules.nextLevel(after: levels) != nil
        if let problem {
            alertAtHintLabel.stringValue = problem
            alertAtHintLabel.textColor = AddAgentSheet.errorColor
        } else {
            alertAtHintLabel.stringValue = texts.isEmpty ? AddAgentSheet.noLevelsHint
                : texts.count >= AlertLevelRules.maxCount ? AddAgentSheet.maxLevelsHint : AddAgentSheet.alertAtHint
            alertAtHintLabel.textColor = Palette.secondaryText
        }
        confirmButton.isEnabled = selectedBilling == .api || problem == nil
    }

    /// Sorts the rows when every field is a whole number from 1 to 100.
    private func commitLevels() {
        let texts = levelTexts
        let levels = texts.compactMap(AlertLevelRules.level)
        guard levels.count == texts.count else { return }
        let sorted = levels.sorted().map(String.init)
        if sorted != texts {
            setLevelRows(sorted)
        } else {
            updateLevels()
        }
    }

    func controlTextDidChange(_ obj: Notification) {
        updateLevels()
    }

    func controlTextDidEndEditing(_ obj: Notification) {
        commitLevels()
    }

    @objc private func addLevel(_ sender: Any?) {
        updateLevels()
        guard addLevelButton.isEnabled else { return }
        let levels = levelTexts.compactMap(AlertLevelRules.level)
        guard let next = AlertLevelRules.nextLevel(after: levels) else { return }
        setLevelRows((levels + [next]).sorted().map(String.init))
    }

    @objc private func removeLevelRow(_ sender: NSButton) {
        removeLevel(at: sender.tag)
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
        return AgentConfig(id: id, billing: selectedBilling, plan: selectedPlan, enabled: enabled, alertLevels: alertLevels)
    }

    /// A subscription gets the levels of the rows, sorted. An API entry has no Alert-at row, so it keeps the edited
    /// entry's levels, or the defaults when it is added.
    private var alertLevels: [Int] {
        switch (selectedBilling, mode) {
        case (.subscription, _): return levelTexts.compactMap(AlertLevelRules.level).sorted()
        case (.api, .add): return AlertLevelRules.defaults
        case (.api, .edit(let agent)): return agent.alertLevels
        }
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
