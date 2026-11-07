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

    func testRemovingAndSettingEnabled() {
        let list = [codexTeam, AgentConfig.defaults(.claudeCode)]
        XCTAssertEqual(list.removing(.codex), [AgentConfig.defaults(.claudeCode)])
        var disabled = codexTeam
        disabled.enabled = .off
        XCTAssertEqual(list.settingEnabled(.off, for: .codex), [disabled, AgentConfig.defaults(.claudeCode)])
    }

    func testAddableIsEveryAgentNotInTheList() {
        XCTAssertEqual([AgentConfig]().addable, [.claudeCode, .codex])
        XCTAssertEqual([codexTeam].addable, [.claudeCode])
        XCTAssertEqual([codexTeam, AgentConfig.defaults(.claudeCode)].addable, [])
    }
}
