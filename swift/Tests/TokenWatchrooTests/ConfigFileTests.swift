import XCTest
@testable import TokenWatchroo

final class ConfigFileTests: XCTestCase {

    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("ConfigFileTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: folder)
    }

    private var url: URL { ConfigFile.url(in: folder) }

    private func write(_ text: String) throws {
        try Data(text.utf8).write(to: url)
    }

    func testAMissingFileGivesTheDefaults() {
        guard case .missing(let config) = ConfigFile.load(from: url, stateDir: "/state") else {
            return XCTFail("expected .missing")
        }
        XCTAssertEqual(config, Config.defaults(stateDir: "/state"))
        XCTAssertEqual(config.refreshIntervalSeconds, 60)
        XCTAssertEqual(config.agents, [])
    }

    func testAFileThatIsNotJsonGivesTheDefaults() throws {
        try write("not json")
        guard case .undecodable(let config, _) = ConfigFile.load(from: url, stateDir: "/state") else {
            return XCTFail("expected .undecodable")
        }
        XCTAssertEqual(config, Config.defaults(stateDir: "/state"))
    }

    func testAVersionOneFileHasNoAgents() throws {
        try write(#"{"stateDir":"/old","refreshIntervalSeconds":30,"codexHome":null,"claudeCodeVersionOverride":null}"#)
        guard case .loaded(let config) = ConfigFile.load(from: url, stateDir: "/state") else {
            return XCTFail("expected .loaded")
        }
        XCTAssertEqual(config.refreshIntervalSeconds, 30)
        XCTAssertEqual(config.agents, [])
    }

    func testTheStateDirectoryInTheFileIsReplaced() throws {
        try write(#"{"stateDir":"/moved/elsewhere"}"#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.stateDir, "/state")
    }

    func testEntriesThatCannotBeReadAreSkipped() throws {
        try write(#"""
        {"agents":[{"id":"cursor"},{"id":"codex","plan":"family"},{"id":"claude-code","billing":"api","plan":"enterprise","enabled":false}]}
        """#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.agents,
                       [AgentConfig(id: .claudeCode, billing: .api, plan: .enterprise, enabled: .off)])
    }

    func testAnEntryWithOnlyAnIdGetsTheDefaults() throws {
        try write(#"{"agents":[{"id":"codex"}]}"#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.agents, [AgentConfig.defaults(.codex)])
    }

    /// The same text is in the Scala `CodecsSpec`, so both sides agree on the wire.
    func testAnEntryEncodesToTheCoreWireValues() throws {
        let entry = AgentConfig(id: .codex, billing: .subscription, plan: .team, enabled: .on)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let json = String(decoding: try encoder.encode(entry), as: UTF8.self)
        XCTAssertEqual(json, #"{"billing":"subscription","enabled":true,"id":"codex","plan":"team"}"#)
        let fromCore = #"{"id":"codex","billing":"subscription","plan":"team","enabled":true}"#
        XCTAssertEqual(try JSONDecoder().decode(AgentConfig.self, from: Data(fromCore.utf8)), entry)
    }

    func testAlertLevelsAreReadLeniently() throws {
        try write(#"""
        {"agents":[{"id":"codex","alertLevels":[60,90]},{"id":"claude-code","alertLevels":[70,72]}]}
        """#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.agents.map(\.alertLevels),
                       [[60, 90], AlertLevelRules.defaults])
        try write(#"{"agents":[{"id":"codex","alertLevels":"x"},{"id":"claude-code","alertLevels":[]}]}"#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.agents.map(\.alertLevels),
                       [AlertLevelRules.defaults, []])
    }

    /// The same rules as in the Scala `CodecsSpec`: the defaults are left out, other levels and `[]` are written.
    func testAlertLevelsAreWrittenOnlyWhenNotTheDefaults() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        func json(_ levels: [Int]) throws -> String {
            var entry = AgentConfig.defaults(.codex)
            entry.alertLevels = levels
            return String(decoding: try encoder.encode(entry), as: UTF8.self)
        }
        XCTAssertFalse(try json(AlertLevelRules.defaults).contains("alertLevels"))
        XCTAssertTrue(try json([60, 90]).contains(#""alertLevels":[60,90]"#))
        XCTAssertTrue(try json([]).contains(#""alertLevels":[]"#))
        let strict = #"{"id":"codex","billing":"subscription","plan":"team","enabled":true,"alertLevels":[70,72]}"#
        XCTAssertEqual(try JSONDecoder().decode(AgentConfig.self, from: Data(strict.utf8)).alertLevels,
                       AlertLevelRules.defaults)
    }

    func testWriteThenLoadGivesTheSameConfig() throws {
        var config = Config.defaults(stateDir: "/state")
        config.codexHome = "/Users/someone/.codex"
        config.agents = [AgentConfig(id: .codex, billing: .subscription, plan: .team, enabled: .on),
                         AgentConfig(id: .claudeCode, billing: .subscription, plan: .individual, enabled: .off,
                                     alertLevels: [])]
        try ConfigFile.write(config, to: url)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config, config)
        XCTAssertTrue(String(decoding: try Data(contentsOf: url), as: UTF8.self).contains(#""codexHome" : "/Users/someone/.codex""#))
    }

    func testMoveAsideReplacesAnOlderBadFile() throws {
        try Data("older".utf8).write(to: folder.appendingPathComponent(ConfigFile.asideFileName))
        try write("newer")
        let aside = try ConfigFile.moveAside(url)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertEqual(String(decoding: try Data(contentsOf: aside), as: UTF8.self), "newer")
    }

    /// Spend levels (#105) are read like the window levels: a wrong type or broken rules give nil, the defaults.
    func testSpendAlertLevelsAreReadLeniently() throws {
        try write(#"""
        {"agents":[{"id":"codex","spendAlertLevels":{"unit":"USD","amounts":[150,180]}},{"id":"claude-code","spendAlertLevels":{"unit":"credits","amounts":[]}}]}
        """#)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config.agents.map(\.spendAlertLevels),
                       [SpendAlertLevels(unit: "USD", amounts: [150, 180]), SpendAlertLevels(unit: "credits", amounts: [])])
        try write(#"""
        {"agents":[{"id":"codex","plan":"team","spendAlertLevels":"x"},{"id":"claude-code","spendAlertLevels":{"unit":"usd","amounts":[150]}},{"id":"codex","spendAlertLevels":{"unit":"USD","amounts":[180,150]}}]}
        """#)
        let agents = ConfigFile.load(from: url, stateDir: "/state").config.agents
        XCTAssertEqual(agents.map(\.spendAlertLevels), [nil, nil, nil])
        XCTAssertEqual(agents.first?.plan, .team)
    }

    func testSpendAlertLevelsAreWrittenOnlyWhenSet() throws {
        func json(_ levels: SpendAlertLevels?) throws -> String {
            var entry = AgentConfig.defaults(.codex)
            entry.spendAlertLevels = levels
            return String(decoding: try JSONEncoder().encode(entry), as: UTF8.self)
        }
        XCTAssertFalse(try json(nil).contains("spendAlertLevels"))
        XCTAssertTrue(try json(SpendAlertLevels(unit: "USD", amounts: [150, 180])).contains(#""amounts":[150,180]"#))
        XCTAssertTrue(try json(SpendAlertLevels(unit: "credits", amounts: [])).contains(#""amounts":[]"#))
    }

    /// The config folder (#91) round-trips through the file and is left out when it is not set.
    func testTheConfigFolderRoundTripsAndIsLeftOutWhenNotSet() throws {
        var config = Config.defaults(stateDir: "/state")
        config.agents = [AgentConfig(id: .claudeCode, billing: .subscription, plan: .individual, enabled: .on,
                                     claudeConfigDir: "/Users/alex/.claude-work/"),
                         AgentConfig.defaults(.codex)]
        try ConfigFile.write(config, to: url)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: "/state").config, config)
        let text = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        XCTAssertTrue(text.contains(#""claudeConfigDir" : "/Users/alex/.claude-work/""#))
        XCTAssertEqual(text.components(separatedBy: "claudeConfigDir").count, 2)
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(AgentConfig.defaults(.claudeCode)), as: UTF8.self)
            .contains("claudeConfigDir"))
    }

    /// A folder that is not an absolute path, or has the wrong type, is not set and the entry is kept. A file from
    /// before #91 has no folder.
    func testTheConfigFolderIsReadLeniently() throws {
        try write(#"""
        {"agents":[{"id":"claude-code","plan":"team","claudeConfigDir":"relative"},{"id":"claude-code","claudeConfigDir":"  "},{"id":"claude-code","claudeConfigDir":7},{"id":"codex"}]}
        """#)
        let agents = ConfigFile.load(from: url, stateDir: "/state").config.agents
        XCTAssertEqual(agents.map(\.claudeConfigDir), [nil, nil, nil, nil])
        XCTAssertEqual(agents.first?.plan, .team)
        let strict = #"{"id":"claude-code","billing":"subscription","plan":"team","enabled":true,"claudeConfigDir":"x"}"#
        XCTAssertNil(try JSONDecoder().decode(AgentConfig.self, from: Data(strict.utf8)).claudeConfigDir)
    }

    func testConfigFolderRulesTrimAndNeedAnAbsolutePath() {
        XCTAssertEqual(ClaudeConfigDirRules.value(" /a/b \n"), "/a/b")
        XCTAssertNil(ClaudeConfigDirRules.value("~/x"))
        XCTAssertNil(ClaudeConfigDirRules.value(""))
        XCTAssertNil(ClaudeConfigDirRules.value(nil))
        XCTAssertNil(ClaudeConfigDirRules.problem(""))
        XCTAssertNil(ClaudeConfigDirRules.problem("/a"))
        XCTAssertEqual(ClaudeConfigDirRules.problem("a/b"), ClaudeConfigDirRules.problem)
    }
}
