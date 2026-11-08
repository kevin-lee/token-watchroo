import AppKit

/// The Add-agent sheet from `.ai/docs/design/ui/AddAgent.dc.html` (#8): agent, billing, plan, and the Alert-at control
/// (#9): one row per window alert level with a number field, a chip with the level's colour and title, and a "−"
/// button, and a "+" button under the rows. Levels that break the rules mark their fields, show the rule in red and
/// disable Add or Save. The API variant has no budget row (m5) and no Alert-at row, because an API entry gives an
/// unavailable card that never alerts.
///
/// The "Spend alerts" row (#105) is shown for a subscription whose last snapshot had a spend: a pop-up for the default
/// levels (70% and 85% of the limit) or amounts, and for amounts one row per amount with the unit, a chip with the
/// amount's colour and percent of the limit, and a "−" button, and a "+" button under the rows. Before the first
/// snapshot the row is read-only, because the unit and the limit are known only after a fetch. The row is hidden when
/// the last snapshot had no spend.
///
/// The "Config folder" row (#91) is shown for Claude Code on a subscription: a text field for the text of
/// `CLAUDE_CONFIG_DIR` and a "Choose…" button. Claude Code hashes that text as written, so the chooser only fills the
/// field and the user can still change it. A text that is not an absolute path marks the field and disables Add or Save.
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
    static let spendAlertsTitle = "Spend alerts"
    static let spendDefaultTitle = "70% and 85% of the limit"
    static let spendAmountsTitle = "Amounts"
    static let spendHint = "A spend alert comes when the spent amount reaches a level. Up to 5 levels."
    static let noSpendLevelsHint = "No spend alerts for this agent."
    static let spendUnknownHint = "Amounts can be set after the first refresh reads the limit."
    static func unitMismatchHint(stored: String, meter: String) -> String {
        "Set for \(stored). The limit is now in \(meter)."
    }
    static let configDirTitle = "Config folder"
    static let configDirPlaceholder = "Default (~/.claude)"
    static let configDirHint = "Optional. Use the same text as CLAUDE_CONFIG_DIR, or CLAUDE_SECURESTORAGE_CONFIG_DIR "
        + "when you set it. Leave empty for the default sign-in."
    static let chooseTitle = "Choose…"
    static let choosePanelMessage = "Choose the Claude Code config folder."

    /// Shows a folder chooser over `window`, starting at `start`, and calls `done` with the chosen folder or nil.
    typealias FolderChooser = @MainActor (_ window: NSWindow?, _ start: URL?, _ done: @escaping (URL?) -> Void) -> Void

    /// How the spend levels are chosen in the pop-up.
    enum SpendMode {
        case defaults
        case amounts
    }

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

    private let meters: [AgentId: SpendMeterInfo]
    private var spendAlertsRow = NSStackView()
    private let spendModePopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let spendDetail = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.primaryText)
    private let spendRows = NSStackView()
    private var spendFields: [NSTextField] = []
    private var spendUnitLabels: [NSTextField] = []
    private var spendChipLabels: [NSTextField] = []
    private var spendChipDots: [NSView] = []
    private let noSpendLevels = AddAgentSheet.label("No levels", size: 12, weight: .regular,
                                                    color: Palette.secondaryText)
    private let addSpendButton = NSButton(title: "+", target: nil, action: nil)
    private let spendHintLabel = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.secondaryText)

    private let chooseFolder: FolderChooser
    private var configDirRow = NSStackView()
    private let configDirField = NSTextField(string: "")
    private let chooseButton = NSButton(title: AddAgentSheet.chooseTitle, target: nil, action: nil)
    private let configDirHintLabel = AddAgentSheet.label("", size: 11, weight: .regular, color: Palette.secondaryText)

    /// `completion` gets the entry for "Add" or "Save", and nil for "Cancel". `meters` are the spend meters of the
    /// agents from their last snapshots (#105). `chooseFolder` shows the folder chooser (#91), a fake in tests.
    init(mode: Mode, meters: [AgentId: SpendMeterInfo] = [:],
         chooseFolder: @escaping FolderChooser = AddAgentSheet.openPanel,
         completion: @escaping (AgentConfig?) -> Void) {
        self.mode = mode
        self.meters = meters
        self.chooseFolder = chooseFolder
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

    /// Chooses an agent in add mode, as a user does.
    func selectAgent(_ id: AgentId) {
        guard case .add(let addable) = mode, let index = addable.firstIndex(of: id) else { return }
        agentPopUp.selectItem(at: index)
        agentChanged(nil)
    }

    var isSpendAlertsVisible: Bool { !spendAlertsRow.isHidden }
    var isSpendModeEnabled: Bool { spendModePopUp.isEnabled }
    var spendModeTitle: String { spendModePopUp.titleOfSelectedItem ?? "" }
    var spendDetailText: String { spendDetail.isHidden ? "" : spendDetail.stringValue }
    var spendTexts: [String] { spendRows.isHidden ? [] : spendFields.map(\.stringValue) }
    var spendChips: [String] { spendChipLabels.map(\.stringValue) }
    var spendUnitText: String { spendUnitLabels.first?.stringValue ?? "" }
    var spendHintText: String { spendHintLabel.isHidden ? "" : spendHintLabel.stringValue }
    var isAddSpendLevelEnabled: Bool { addSpendButton.isEnabled }

    func isSpendMarked(at index: Int) -> Bool {
        spendFields.indices.contains(index) && spendFields[index].layer?.borderWidth ?? 0 > 0
    }

    func selectSpendMode(_ spendMode: SpendMode) {
        spendModePopUp.selectItem(at: spendMode == .defaults ? 0 : 1)
        spendModeChanged(nil)
    }

    /// Types `text` into the amount row and ends the editing, as a user does.
    func setSpendText(_ text: String, at index: Int) {
        guard spendFields.indices.contains(index) else { return }
        spendFields[index].stringValue = text
        updateSpend()
        commitSpend()
    }

    func addSpendLevel() {
        addSpendLevel(nil)
    }

    func removeSpendLevel(at index: Int) {
        guard spendFields.indices.contains(index) else { return }
        var texts = spendFields.map(\.stringValue)
        texts.remove(at: index)
        setSpendRows(texts)
    }

    var isConfigDirVisible: Bool { !configDirRow.isHidden }
    var configDirText: String { configDirField.stringValue }
    var isConfigDirMarked: Bool { configDirField.layer?.borderWidth ?? 0 > 0 }
    var configDirHintText: String { configDirHintLabel.stringValue }

    /// Types `text` into the Config folder field, as a user does.
    func setConfigDirText(_ text: String) {
        configDirField.stringValue = text
        updateConfigDir()
    }

    /// Clicks "Choose…".
    func chooseConfigDir() {
        chooseConfigDir(nil)
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
            agentPopUp.target = self
            agentPopUp.action = #selector(agentChanged(_:))
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

        spendModePopUp.addItems(withTitles: [AddAgentSheet.spendDefaultTitle, AddAgentSheet.spendAmountsTitle])
        spendModePopUp.target = self
        spendModePopUp.action = #selector(spendModeChanged(_:))
        spendRows.orientation = .vertical
        spendRows.alignment = .leading
        spendRows.spacing = 6
        addSpendButton.target = self
        addSpendButton.action = #selector(addSpendLevel(_:))
        addSpendButton.bezelStyle = .smallSquare
        addSpendButton.setAccessibilityLabel("Add a spend level")
        addSpendButton.widthAnchor.constraint(equalToConstant: 28).isActive = true
        addSpendButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        let spend = NSStackView(views: [spendModePopUp, spendDetail, spendRows, noSpendLevels, addSpendButton])
        spend.orientation = .vertical
        spend.alignment = .leading
        spend.spacing = 6
        spendAlertsRow = AddAgentSheet.field(AddAgentSheet.spendAlertsTitle, spend, spendHintLabel)

        configDirField.font = .systemFont(ofSize: 13)
        configDirField.placeholderString = AddAgentSheet.configDirPlaceholder
        configDirField.lineBreakMode = .byTruncatingMiddle
        configDirField.usesSingleLineMode = true
        configDirField.delegate = self
        configDirField.wantsLayer = true
        configDirField.layer?.cornerRadius = 4
        configDirField.setAccessibilityLabel(AddAgentSheet.configDirTitle)
        configDirField.setContentHuggingPriority(.defaultLow, for: .horizontal)
        chooseButton.target = self
        chooseButton.action = #selector(chooseConfigDir(_:))
        chooseButton.setContentHuggingPriority(.required, for: .horizontal)
        let configDir = NSStackView(views: [configDirField, chooseButton])
        configDir.orientation = .horizontal
        configDir.spacing = 8
        configDir.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        configDirRow = AddAgentSheet.field(AddAgentSheet.configDirTitle, configDir, configDirHintLabel)

        let fields = NSStackView(views: [
            AddAgentSheet.field("Agent", agentPopUp),
            AddAgentSheet.field("Billing", billingControl),
            AddAgentSheet.field("Plan", planPopUp, hint),
            configDirRow,
            alertAtRow,
            spendAlertsRow,
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
        [agentPopUp, billingControl, planPopUp, spendModePopUp].forEach {
            $0.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        }
        view = container

        switch mode {
        case .add:
            setLevelRows(AlertLevelRules.defaults.map(String.init))
            selectBilling(.subscription)
        case .edit(let agent):
            configDirField.stringValue = agent.claudeConfigDir ?? ""
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
        updateConfigDir()
        updateLevels()
        configureSpend()
        fitWindow()
    }

    /// In add mode the Spend alerts row (#105) and the Config folder row (#91) follow the chosen agent.
    @objc private func agentChanged(_ sender: Any?) {
        updateConfigDir()
        configureSpend()
        fitWindow()
    }

    // MARK: config folder (#91)

    /// The row is only for Claude Code on a subscription.
    private var isConfigDirActive: Bool {
        selectedAgent == .claudeCode && selectedBilling == .subscription
    }

    /// Shows or hides the row, and marks the field and shows the problem when its text is not an absolute path.
    private func updateConfigDir() {
        configDirRow.isHidden = !isConfigDirActive
        if let problem = ClaudeConfigDirRules.problem(configDirField.stringValue) {
            configDirField.layer?.borderWidth = 1.5
            configDirHintLabel.stringValue = problem
            configDirHintLabel.textColor = AddAgentSheet.errorColor
        } else {
            configDirField.layer?.borderWidth = 0
            configDirHintLabel.stringValue = AddAgentSheet.configDirHint
            configDirHintLabel.textColor = Palette.secondaryText
        }
        configDirField.layer?.borderColor = AddAgentSheet.errorColor.cgColor
        updateConfirm()
    }

    @objc private func chooseConfigDir(_ sender: Any?) {
        let start = ClaudeConfigDirRules.value(configDirField.stringValue).map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser
        chooseFolder(view.window, start) { [weak self] url in
            guard let self, let url else { return }
            self.configDirField.stringValue = AddAgentSheet.pathText(for: url)
            self.updateConfigDir()
        }
    }

    /// The text for a chosen folder: its path without a trailing slash.
    static func pathText(for url: URL) -> String {
        url.path
    }

    /// The standard folder chooser, shown as a sheet over the Add-agent sheet. Hidden folders are shown, because the
    /// Claude Code folders start with a dot.
    static func openPanel(_ window: NSWindow?, _ start: URL?, _ done: @escaping (URL?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        panel.canCreateDirectories = false
        panel.message = choosePanelMessage
        panel.directoryURL = start
        if let window {
            panel.beginSheetModal(for: window) { response in
                done(response == .OK ? panel.url : nil)
            }
        } else {
            done(panel.runModal() == .OK ? panel.url : nil)
        }
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
        updateConfirm()
    }

    /// Add or Save is disabled while a level row, an amount row or the Config folder breaks a rule. An API entry has
    /// none of these rows.
    private func updateConfirm() {
        let levelProblem = AlertLevelRules.problem(levelTexts)
        let spendProblem = isAmountsActive ? SpendAlertLevelRules.problem(spendFields.map(\.stringValue)) : nil
        let configDirProblem = isConfigDirActive ? ClaudeConfigDirRules.problem(configDirField.stringValue) : nil
        confirmButton.isEnabled = selectedBilling == .api
            || (levelProblem == nil && spendProblem == nil && configDirProblem == nil)
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
        if isConfigDirField(obj.object) {
            updateConfigDir()
        } else if isSpendField(obj.object) {
            updateSpend()
        } else {
            updateLevels()
        }
    }

    func controlTextDidEndEditing(_ obj: Notification) {
        if isConfigDirField(obj.object) {
            updateConfigDir()
        } else if isSpendField(obj.object) {
            commitSpend()
        } else {
            commitLevels()
        }
    }

    private func isConfigDirField(_ object: Any?) -> Bool {
        (object as? NSTextField) === configDirField
    }

    private func isSpendField(_ object: Any?) -> Bool {
        guard let field = object as? NSTextField else { return false }
        return spendFields.contains { $0 === field }
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

    // MARK: spend alerts (#105)

    private var currentMeter: SpendMeterInfo? {
        selectedAgent.flatMap { meters[$0] }
    }

    /// The spend levels of the edited entry, nil for a new one or the defaults.
    private var storedSpend: SpendAlertLevels? {
        switch mode {
        case .add: return nil
        case .edit(let agent): return agent.spendAlertLevels
        }
    }

    /// The amount rows are in use: a subscription with a known spend meter and "Amounts" chosen.
    private var isAmountsActive: Bool {
        guard selectedBilling == .subscription, case .spend? = currentMeter else { return false }
        return spendModePopUp.indexOfSelectedItem == 1
    }

    /// Shows the row for the chosen agent and billing, with the stored levels.
    private func configureSpend() {
        switch (selectedBilling, currentMeter) {
        case (.api, _), (_, .noSpend?):
            spendAlertsRow.isHidden = true
        case (.subscription, nil):
            spendAlertsRow.isHidden = false
            spendModePopUp.isEnabled = false
            spendModePopUp.selectItem(at: storedSpend == nil ? 0 : 1)
            setSpendRows([])
        case (.subscription, .spend(let currency, _)?):
            spendAlertsRow.isHidden = false
            spendModePopUp.isEnabled = true
            if let stored = storedSpend, stored.amounts.isEmpty || stored.unit == currency {
                spendModePopUp.selectItem(at: 1)
                setSpendRows(stored.amounts.map(String.init))
            } else {
                spendModePopUp.selectItem(at: 0)
                setSpendRows([])
            }
        }
        updateSpend()
    }

    /// "Amounts" starts from the stored amounts in the meter's unit, else from the default amounts.
    @objc private func spendModeChanged(_ sender: Any?) {
        if spendModePopUp.indexOfSelectedItem == 1, case .spend(let currency, let limit)? = currentMeter {
            if let stored = storedSpend, stored.unit == currency || stored.amounts.isEmpty {
                setSpendRows(stored.amounts.map(String.init))
            } else {
                setSpendRows(SpendAlertLevelRules.defaultWholeAmounts(limit: limit).map(String.init))
            }
        } else {
            setSpendRows([])
        }
        fitWindow()
    }

    private func setSpendRows(_ texts: [String]) {
        spendRows.arrangedSubviews.forEach { $0.removeFromSuperview() }
        spendFields = []
        spendUnitLabels = []
        spendChipLabels = []
        spendChipDots = []
        for (index, text) in texts.enumerated() {
            spendRows.addArrangedSubview(spendRow(text, index: index))
        }
        updateSpend()
        fitWindow()
    }

    private func spendRow(_ text: String, index: Int) -> NSStackView {
        let field = NSTextField(string: text)
        field.font = .systemFont(ofSize: 13)
        field.alignment = .right
        field.delegate = self
        field.wantsLayer = true
        field.layer?.cornerRadius = 4
        field.setAccessibilityLabel("Spend alert level \(index + 1), amount")
        field.widthAnchor.constraint(equalToConstant: 96).isActive = true
        let unit = AddAgentSheet.label(unitText, size: 12, weight: .regular, color: Palette.secondaryText)

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
        let remove = NSButton(title: "−", target: self, action: #selector(removeSpendRow(_:)))
        remove.tag = index
        remove.bezelStyle = .smallSquare
        remove.setAccessibilityLabel("Remove this spend level")
        remove.widthAnchor.constraint(equalToConstant: 28).isActive = true
        remove.heightAnchor.constraint(equalToConstant: 28).isActive = true

        let row = NSStackView(views: [field, unit, dot, chip, spacer, remove])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = 6
        row.setCustomSpacing(10, after: unit)
        row.widthAnchor.constraint(equalToConstant: AddAgentSheet.fieldWidth).isActive = true
        spendFields.append(field)
        spendUnitLabels.append(unit)
        spendChipLabels.append(chip)
        spendChipDots.append(dot)
        return row
    }

    /// "USD" for an ISO currency, "credits" for credits.
    private var unitText: String {
        if case .spend(let currency, _)? = currentMeter { return currency }
        return ""
    }

    /// The pop-up, the detail, the rows, "+" and the hint for the current state, then Add or Save.
    private func updateSpend() {
        let amounts = isAmountsActive
        spendDetail.isHidden = amounts
        spendRows.isHidden = !amounts
        addSpendButton.isHidden = !amounts
        noSpendLevels.isHidden = !(amounts && spendFields.isEmpty)
        spendDetail.stringValue = spendDetailFor()

        if amounts, case .spend(_, let limit)? = currentMeter {
            let texts = spendFields.map(\.stringValue)
            let marked = SpendAlertLevelRules.marked(texts)
            let problem = SpendAlertLevelRules.problem(texts)
            for (index, field) in spendFields.enumerated() {
                field.layer?.borderWidth = marked.contains(index) ? 1.5 : 0
                field.layer?.borderColor = AddAgentSheet.errorColor.cgColor
                let amount = SpendAlertLevelRules.amount(texts[index])
                spendChipLabels[index].stringValue = amount.map { SpendAlertLevelRules.chip(amount: $0, limit: limit) }
                    ?? ""
                let percent = amount.flatMap { SpendAlertLevelRules.percent(of: $0, limit: limit) }
                let color = percent.flatMap { $0 <= 100 ? RingIcon.color(for: UsageBand.of(percent: $0)) : nil }
                let dot = spendChipDots[index].layer
                dot?.backgroundColor = amount == nil ? nil : (color ?? .white).cgColor
                dot?.borderWidth = amount != nil && color == nil ? 1 : 0
                dot?.borderColor = Palette.secondaryText.cgColor
            }
            addSpendButton.isEnabled = texts.count < SpendAlertLevelRules.maxCount && problem == nil
            if let problem {
                setSpendHint(problem, color: AddAgentSheet.errorColor)
            } else {
                setSpendHint(texts.isEmpty ? AddAgentSheet.noSpendLevelsHint
                             : texts.count >= SpendAlertLevelRules.maxCount ? AddAgentSheet.maxLevelsHint
                             : AddAgentSheet.spendHint, color: Palette.secondaryText)
            }
        } else if currentMeter == nil {
            setSpendHint(AddAgentSheet.spendUnknownHint, color: Palette.secondaryText)
        } else if case .spend(let currency, _)? = currentMeter, let stored = storedSpend, !stored.amounts.isEmpty,
                  stored.unit != currency {
            setSpendHint(AddAgentSheet.unitMismatchHint(stored: stored.unit, meter: currency),
                         color: Palette.secondaryText)
        } else {
            setSpendHint("", color: Palette.secondaryText)
        }
        updateConfirm()
    }

    private func setSpendHint(_ text: String, color: NSColor) {
        spendHintLabel.stringValue = text
        spendHintLabel.textColor = color
        spendHintLabel.isHidden = text.isEmpty
    }

    /// "Now $140.00 and $170.00 of $200.00." for a known meter. Before the first snapshot: the stored amounts
    /// ("At 150 and 180 USD."), "No spend alerts for this agent." or the default title.
    private func spendDetailFor() -> String {
        switch currentMeter {
        case .spend(let currency, let limit)?:
            let amounts = SpendAlertLevelRules.defaultAmounts(limit: limit)
                .map { Formatting.money($0, currency: currency) }
            let limitText = Formatting.money(limit, currency: currency)
            let unit = currency == "credits" ? " credits" : ""
            return "Now \(AddAgentSheet.joined(amounts)) of \(limitText)\(unit)."
        case .noSpend?, nil:
            guard let stored = storedSpend else { return "\(AddAgentSheet.spendDefaultTitle)." }
            if stored.amounts.isEmpty { return AddAgentSheet.noSpendLevelsHint }
            return "At \(AddAgentSheet.joined(stored.amounts.map(String.init))) \(stored.unit)."
        }
    }

    /// "a", "a and b", "a, b and c".
    private static func joined(_ items: [String]) -> String {
        guard let last = items.last else { return "" }
        let rest = items.dropLast()
        return rest.isEmpty ? last : "\(rest.joined(separator: ", ")) and \(last)"
    }

    /// Sorts the amount rows when every field is a whole amount.
    private func commitSpend() {
        let texts = spendFields.map(\.stringValue)
        let amounts = texts.compactMap(SpendAlertLevelRules.amount)
        guard amounts.count == texts.count else { return }
        let sorted = amounts.sorted().map(String.init)
        if sorted != texts {
            setSpendRows(sorted)
        } else {
            updateSpend()
        }
    }

    @objc private func addSpendLevel(_ sender: Any?) {
        updateSpend()
        guard addSpendButton.isEnabled, case .spend(_, let limit)? = currentMeter else { return }
        let amounts = spendFields.map(\.stringValue).compactMap(SpendAlertLevelRules.amount)
        let next = SpendAlertLevelRules.nextAmount(after: amounts, limit: limit)
        setSpendRows((amounts + [next]).sorted().map(String.init))
    }

    @objc private func removeSpendRow(_ sender: NSButton) {
        removeSpendLevel(at: sender.tag)
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
        return AgentConfig(id: id, billing: selectedBilling, plan: selectedPlan, enabled: enabled,
                           alertLevels: alertLevels, spendAlertLevels: spendAlertLevels,
                           claudeConfigDir: claudeConfigDir)
    }

    /// The field's folder while the row is shown (#91). Any other case keeps the edited entry's folder, or nil when
    /// added, the same as the alert levels.
    private var claudeConfigDir: String? {
        if isConfigDirActive { return ClaudeConfigDirRules.value(configDirField.stringValue) }
        switch mode {
        case .add: return nil
        case .edit(let agent): return agent.claudeConfigDir
        }
    }

    /// A subscription with a known spend meter gets nil for the defaults and the sorted amount rows in the meter's
    /// unit for "Amounts" (#105). Any other case keeps the edited entry's value, or nil when added.
    private var spendAlertLevels: SpendAlertLevels? {
        if selectedBilling == .subscription, case .spend(let currency, _)? = currentMeter {
            guard isAmountsActive else { return nil }
            let amounts = spendFields.map(\.stringValue).compactMap(SpendAlertLevelRules.amount).sorted()
            return SpendAlertLevels(unit: currency, amounts: amounts)
        }
        return storedSpend
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
