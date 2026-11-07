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

    func testWriteThenLoadGivesTheSameConfig() throws {
        var config = Config.defaults(stateDir: "/state")
        config.codexHome = "/Users/someone/.codex"
        config.agents = [AgentConfig(id: .codex, billing: .subscription, plan: .team, enabled: .on),
                         AgentConfig(id: .claudeCode, billing: .subscription, plan: .individual, enabled: .off)]
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
}
