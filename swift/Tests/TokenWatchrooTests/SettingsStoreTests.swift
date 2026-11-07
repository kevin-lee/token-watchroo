import XCTest
@testable import TokenWatchroo

final class SettingsStoreTests: XCTestCase {

    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("SettingsStoreTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: folder)
    }

    private let codexTeam = AgentConfig(id: .codex, billing: .subscription, plan: .team, enabled: .on)

    @MainActor
    func testAChangeIsWrittenAtOnce() {
        let url = ConfigFile.url(in: folder)
        let store = SettingsStore(config: .defaults(stateDir: folder.path), fileURL: url, push: { _ in }, pushDelay: 60)
        store.setAgents([codexTeam])
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: folder.path).config.agents, [codexTeam])
    }

    @MainActor
    func testABurstOfChangesGivesOnePushWithTheLastConfig() {
        var pushed: [Config] = []
        let done = expectation(description: "one push")
        let store = SettingsStore(config: .defaults(stateDir: folder.path), fileURL: ConfigFile.url(in: folder),
                                  push: { pushed.append($0); done.fulfill() }, pushDelay: 0.05)
        store.setAgents([AgentConfig.defaults(.codex)])
        store.setAgents([codexTeam])
        store.setAgents([codexTeam, AgentConfig.defaults(.claudeCode)])
        wait(for: [done], timeout: 2)
        let settled = expectation(description: "no second push")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        XCTAssertEqual(pushed.map(\.agents), [[codexTeam, AgentConfig.defaults(.claudeCode)]])
    }

    func testAddingReplacesAnEntryWithTheSameIdInPlace() {
        let list = [AgentConfig.defaults(.codex), AgentConfig.defaults(.claudeCode)]
        XCTAssertEqual(list.adding(codexTeam), [codexTeam, AgentConfig.defaults(.claudeCode)])
        XCTAssertEqual([AgentConfig.defaults(.claudeCode)].adding(codexTeam), [AgentConfig.defaults(.claudeCode), codexTeam])
    }

    func testSettingEnabled() {
        let list = [codexTeam, AgentConfig.defaults(.claudeCode)]
        var disabled = codexTeam
        disabled.enabled = .off
        XCTAssertEqual(list.settingEnabled(.off, for: .codex), [disabled, AgentConfig.defaults(.claudeCode)])
    }

    func testAddingDetectedAppendsOnlyAgentsWithoutAnEntry() {
        var disabledCodex = codexTeam
        disabledCodex.enabled = .off
        XCTAssertEqual([AgentConfig]().addingDetected([.claudeCode, .codex]),
                       [AgentConfig.defaults(.claudeCode), AgentConfig.defaults(.codex)])
        XCTAssertEqual([codexTeam].addingDetected([.claudeCode, .codex]), [codexTeam, AgentConfig.defaults(.claudeCode)])
        XCTAssertEqual([disabledCodex].addingDetected([.codex]), [disabledCodex])
        XCTAssertEqual([AgentConfig]().addingDetected([.codex, .codex]), [AgentConfig.defaults(.codex)])
        XCTAssertEqual([codexTeam].addingDetected([]), [codexTeam])
    }

    @MainActor
    func testAddDetectedWritesAndPushesOnlyAChange() {
        var config = Config.defaults(stateDir: folder.path)
        config.agents = [codexTeam]
        let url = ConfigFile.url(in: folder)
        var pushed: [Config] = []
        let store = SettingsStore(config: config, fileURL: url, push: { pushed.append($0) }, pushDelay: 0.05)

        store.addDetected([.codex])
        let quiet = expectation(description: "no push")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { quiet.fulfill() }
        wait(for: [quiet], timeout: 2)
        guard case .missing = ConfigFile.load(from: url, stateDir: folder.path) else {
            return XCTFail("config.json was written without a change")
        }
        XCTAssertTrue(pushed.isEmpty)

        store.addDetected([.claudeCode, .codex])
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: folder.path).config.agents,
                       [codexTeam, AgentConfig.defaults(.claudeCode)])
        let settled = expectation(description: "one push")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        XCTAssertEqual(pushed.map(\.agents), [[codexTeam, AgentConfig.defaults(.claudeCode)]])
    }

    func testMovingFollowsTableDropIndexes() {
        let claude = AgentConfig.defaults(.claudeCode)
        let list = [claude, codexTeam, AgentConfig.defaults(.codex)]
        let (a, b, c) = (list[0], list[1], list[2])
        XCTAssertEqual(list.moving(from: 0, to: 2), [b, a, c])
        XCTAssertEqual(list.moving(from: 2, to: 0), [c, a, b])
        XCTAssertEqual(list.moving(from: 0, to: 3), [b, c, a])
        XCTAssertEqual(list.moving(from: 1, to: 1), list)
        XCTAssertEqual(list.moving(from: 1, to: 2), list)
        XCTAssertEqual(list.moving(from: 3, to: 0), list)
        XCTAssertEqual(list.moving(from: 0, to: 4), list)
        XCTAssertEqual(list.newIndex(movingFrom: 0, to: 3), 2)
        XCTAssertEqual(list.newIndex(movingFrom: 2, to: 0), 0)
    }

    @MainActor
    func testSetRefreshIntervalWritesAndPushes() {
        let url = ConfigFile.url(in: folder)
        var pushed: [Config] = []
        let done = expectation(description: "one push")
        let store = SettingsStore(config: .defaults(stateDir: folder.path), fileURL: url,
                                  push: { pushed.append($0); done.fulfill() }, pushDelay: 0.05)
        store.setRefreshInterval(300)
        XCTAssertEqual(ConfigFile.load(from: url, stateDir: folder.path).config.refreshIntervalSeconds, 300)
        wait(for: [done], timeout: 2)
        XCTAssertEqual(pushed.map(\.refreshIntervalSeconds), [300])
    }

    func testAddableIsEveryAgentNotInTheList() {
        XCTAssertEqual([AgentConfig]().addable, [.claudeCode, .codex])
        XCTAssertEqual([codexTeam].addable, [.claudeCode])
        XCTAssertEqual([codexTeam, AgentConfig.defaults(.claudeCode)].addable, [])
    }
}
