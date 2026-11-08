import Foundation

/// The language of the shell and of the core's text (#10). The app follows macOS: the Preferred Languages list, or the
/// per-app language in System Settings > General > Language & Region > Applications. A change shows after a relaunch.
enum AppLanguage: String, CaseIterable, Equatable {
    case en, ko

    /// The first preferred language that the app has, the rule that AppKit and Sparkle use too, else English.
    static func choose(preferences: [String]) -> AppLanguage {
        Bundle.preferredLocalizations(from: allCases.map(\.rawValue), forPreferences: preferences)
            .first.flatMap(AppLanguage.init(rawValue:)) ?? .en
    }
}

/// Every key of `Localizable.strings` (#10), with the names of its `{name}` placeholders.
enum StringKey: String, CaseIterable {
    case menuWaiting = "menu.waiting"
    case menuNoAgent = "menu.noAgent"
    case menuRefreshNow = "menu.refreshNow"
    case menuSettings = "menu.settings"
    case menuLaunchAtLogin = "menu.launchAtLogin"
    case menuAbout = "menu.about"
    case menuCheckForUpdates = "menu.checkForUpdates"
    case menuQuit = "menu.quit"
    case headerRefresh = "header.refresh"
    case headerWaiting = "header.waiting"
    case headerUpdatedSeconds = "header.updatedSeconds"
    case headerUpdatedMinutes = "header.updatedMinutes"
    case headerUpdatedHours = "header.updatedHours"
    case shortUnderMinute = "short.underMinute"
    case shortDays = "short.days"
    case shortDaysHours = "short.daysHours"
    case shortHours = "short.hours"
    case shortHoursMinutes = "short.hoursMinutes"
    case shortMinutes = "short.minutes"
    case shortResetting = "short.resetting"
    case cardResetsIn = "card.resetsIn"
    case cardResetsAt = "card.resetsAt"
    case cardExpired = "card.expired"
    case cardLocalLog = "card.localLog"
    case cardNoCurrentUsage = "card.noCurrentUsage"
    case cardUnavailable = "card.unavailable"
    case cardIdle = "card.idle"
    case cardSessionWindow = "card.sessionWindow"
    case windowSession = "window.session"
    case windowWeekly = "window.weekly"
    case windowModel = "window.model"
    case pillOk = "pill.ok"
    case pillElevated = "pill.elevated"
    case pillWarning = "pill.warning"
    case pillCritical = "pill.critical"
    case pillExhausted = "pill.exhausted"
    case pillUnavailable = "pill.unavailable"
    case spendSummary = "spend.summary"
    case spendSummaryCredits = "spend.summaryCredits"
    case settingsTitle = "settings.title"
    case settingsFootnote = "settings.footnote"
    case settingsAddAgent = "settings.addAgent"
    case settingsEdit = "settings.edit"
    case settingsMoveUp = "settings.moveUp"
    case settingsMoveDown = "settings.moveDown"
    case settingsRefreshEvery = "settings.refreshEvery"
    case settingsAgents = "settings.agents"
    case columnAgent = "column.agent"
    case columnBilling = "column.billing"
    case columnPlan = "column.plan"
    case columnEnabled = "column.enabled"
    case intervalHourOne = "interval.hour.one"
    case intervalHourOther = "interval.hour.other"
    case intervalMinuteOne = "interval.minute.one"
    case intervalMinuteOther = "interval.minute.other"
    case intervalSeconds = "interval.seconds"
    case billingSubscription = "billing.subscription"
    case billingApi = "billing.api"
    case sheetAddTitle = "sheet.addTitle"
    case sheetEditTitle = "sheet.editTitle"
    case sheetSubscriptionSubtitle = "sheet.subscriptionSubtitle"
    case sheetApiSubtitle = "sheet.apiSubtitle"
    case sheetSubscriptionHint = "sheet.subscriptionHint"
    case sheetApiHint = "sheet.apiHint"
    case sheetAgent = "sheet.agent"
    case sheetBilling = "sheet.billing"
    case sheetPlan = "sheet.plan"
    case sheetAlertAt = "sheet.alertAt"
    case sheetSpendAlerts = "sheet.spendAlerts"
    case sheetConfigDir = "sheet.configDir"
    case sheetAlertAtHint = "sheet.alertAtHint"
    case sheetNoLevelsHint = "sheet.noLevelsHint"
    case sheetMaxLevelsHint = "sheet.maxLevelsHint"
    case sheetNoLevels = "sheet.noLevels"
    case sheetSpendDefault = "sheet.spendDefault"
    case sheetSpendAmounts = "sheet.spendAmounts"
    case sheetSpendHint = "sheet.spendHint"
    case sheetNoSpendLevelsHint = "sheet.noSpendLevelsHint"
    case sheetSpendUnknownHint = "sheet.spendUnknownHint"
    case sheetSpendUnitMismatch = "sheet.spendUnitMismatch"
    case sheetSpendNow = "sheet.spendNow"
    case sheetSpendNowCredits = "sheet.spendNowCredits"
    case sheetSpendAt = "sheet.spendAt"
    case listPair = "list.pair"
    case sheetConfigDirPlaceholder = "sheet.configDirPlaceholder"
    case sheetConfigDirHint = "sheet.configDirHint"
    case sheetChoose = "sheet.choose"
    case sheetChoosePanel = "sheet.choosePanel"
    case sheetAdd = "sheet.add"
    case sheetSave = "sheet.save"
    case sheetCancel = "sheet.cancel"
    case a11yAddLevel = "a11y.addLevel"
    case a11yLevel = "a11y.level"
    case a11yRemoveLevel = "a11y.removeLevel"
    case a11yAddSpendLevel = "a11y.addSpendLevel"
    case a11ySpendLevel = "a11y.spendLevel"
    case a11yRemoveSpendLevel = "a11y.removeSpendLevel"
    case ruleConfigDir = "rule.configDir"
    case ruleLevelNumber = "rule.levelNumber"
    case ruleLevelGap = "rule.levelGap"
    case ruleSpendNumber = "rule.spendNumber"
    case ruleSpendDuplicate = "rule.spendDuplicate"
    case chipCritical = "chip.critical"
    case chipWarning = "chip.warning"
    case chipElevated = "chip.elevated"
    case chipNormal = "chip.normal"
    case chipAboveLimit = "chip.aboveLimit"
    case chipOfLimit = "chip.ofLimit"
    case colourRed = "colour.red"
    case colourAmber = "colour.amber"
    case colourBlue = "colour.blue"
    case colourNone = "colour.none"
    case unitCredits = "unit.credits"
    case aboutArchitecture = "about.architecture"
    case aboutProject = "about.project"
    case aboutAuthor = "about.author"

    var params: [String] {
        switch self {
        case .headerUpdatedSeconds: return ["n"]
        case .headerUpdatedMinutes: return ["n"]
        case .headerUpdatedHours: return ["n"]
        case .shortDays: return ["d"]
        case .shortDaysHours: return ["d", "h"]
        case .shortHours: return ["h"]
        case .shortHoursMinutes: return ["h", "m"]
        case .shortMinutes: return ["m"]
        case .cardResetsIn: return ["time"]
        case .cardResetsAt: return ["time"]
        case .windowModel: return ["model"]
        case .spendSummary: return ["spent", "limit"]
        case .spendSummaryCredits: return ["spent", "limit"]
        case .intervalHourOther: return ["n"]
        case .intervalMinuteOther: return ["n"]
        case .intervalSeconds: return ["n"]
        case .sheetSpendUnitMismatch: return ["stored", "meter"]
        case .sheetSpendNow: return ["amounts", "limit"]
        case .sheetSpendNowCredits: return ["amounts", "limit"]
        case .sheetSpendAt: return ["amounts", "unit"]
        case .listPair: return ["first", "last"]
        case .a11yLevel: return ["n"]
        case .a11ySpendLevel: return ["n"]
        case .chipOfLimit: return ["colour", "percent"]
        default: return []
        }
    }
}

/// The shell's text in one language (#10). It is passed as a parameter to every view and formatter, never read from a
/// global, so tests can render both languages.
struct AppStrings: Equatable {
    let language: AppLanguage
    let table: [String: String]

    /// The text with each `{name}` replaced in one pass. A missing key gives the key itself.
    func text(_ key: StringKey, _ args: [String: String] = [:]) -> String {
        let template = table[key.rawValue] ?? key.rawValue
        guard !args.isEmpty else { return template }
        var result = ""
        var rest = Substring(template)
        while let open = rest.firstIndex(of: "{") {
            result += rest[..<open]
            let afterOpen = rest.index(after: open)
            if let close = rest[afterOpen...].firstIndex(of: "}"), let value = args[String(rest[afterOpen..<close])] {
                result += value
                rest = rest[rest.index(after: close)...]
            } else {
                result += "{"
                rest = rest[afterOpen...]
            }
        }
        return result + rest
    }

    /// `<directory>/<language>.lproj/Localizable.strings`, or nil when it is missing or not a strings table.
    static func load(_ language: AppLanguage, directory: URL) -> AppStrings? {
        let url = directory.appendingPathComponent("\(language.rawValue).lproj/Localizable.strings")
        guard let data = try? Data(contentsOf: url),
              let table = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: String]
        else { return nil }
        return AppStrings(language: language, table: table)
    }

    /// The bundle's resources when they hold the strings (the app bundle), else `swift/Resources` of the source tree,
    /// for `swift run`.
    static func resourceDirectory(bundle: Bundle, sourceFile: String = #filePath) -> URL {
        if let resources = bundle.resourceURL,
           FileManager.default.fileExists(atPath: resources.appendingPathComponent("en.lproj/Localizable.strings").path) {
            return resources
        }
        return URL(fileURLWithPath: sourceFile)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources", isDirectory: true)
    }

    /// The strings for the launch: the chosen language, else English, else a table of keys, with a log line for each
    /// fallback. The returned language is the one the strings are in.
    static func launch(preferences: [String], bundle: Bundle) -> AppStrings {
        let directory = resourceDirectory(bundle: bundle)
        let chosen = AppLanguage.choose(preferences: preferences)
        if let strings = load(chosen, directory: directory) { return strings }
        Log.app.error("strings for \(chosen.rawValue) not found in \(directory.path), using English")
        if let english = load(.en, directory: directory) { return english }
        Log.app.error("English strings not found in \(directory.path), showing keys")
        return AppStrings(language: .en, table: [:])
    }
}

/// The number rules of the macOS region (#10), sent to the core with the config, because Scala Native has no locale
/// data. The money text in a notification then matches the card.
struct NumberFormatPayload: Encodable, Equatable {
    let decimalSeparator: String
    let groupingSeparator: String
    /// `before`, `before-spaced`, `after` or `after-spaced`.
    let currencySymbol: String

    init(decimalSeparator: String, groupingSeparator: String, currencySymbol: String) {
        self.decimalSeparator = decimalSeparator
        self.groupingSeparator = groupingSeparator
        self.currencySymbol = currencySymbol
    }

    init(locale: Locale) {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .currency
        self.init(
            decimalSeparator: locale.decimalSeparator ?? ".",
            groupingSeparator: locale.groupingSeparator ?? ",",
            currencySymbol: NumberFormatPayload.placement(pattern: formatter.positiveFormat ?? "¤#,##0.00")
        )
    }

    /// Where `¤` is in a currency pattern, before or after the first digit sign, and whether a space is next to it.
    static func placement(pattern: String) -> String {
        let characters = Array(pattern)
        guard let symbol = characters.firstIndex(of: "¤"),
              let digit = characters.firstIndex(where: { $0 == "#" || $0 == "0" }) else { return "before" }
        let spaces: Set<Character> = [" ", "\u{00A0}", "\u{202F}"]
        if symbol < digit {
            let next = symbol + 1 < characters.count ? characters[symbol + 1] : "#"
            return spaces.contains(next) ? "before-spaced" : "before"
        }
        let previous = symbol > 0 ? characters[symbol - 1] : "0"
        return spaces.contains(previous) ? "after-spaced" : "after"
    }
}

/// The config JSON for the library: the config as in `config.json`, plus `locale` and `numberFormat`, which are
/// derived at launch and never written to `config.json` (#10).
struct FfiConfig: Encodable {
    let config: Config
    let locale: AppLanguage
    let numberFormat: NumberFormatPayload

    private enum CodingKeys: String, CodingKey {
        case locale, numberFormat
    }

    func encode(to encoder: Encoder) throws {
        try config.encode(to: encoder)
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(locale.rawValue, forKey: .locale)
        try container.encode(numberFormat, forKey: .numberFormat)
    }
}
