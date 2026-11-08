import Foundation

/// Holds the current config for the settings window (#8). A committed change is written to `config.json` at once and
/// pushed to the library after `pushDelay` without another change, so a burst of clicks gives one `tw_set_config` and
/// one fetch. A failed write is logged, and the change is still pushed, so the running app follows the user. Adding a
/// detected agent that has no entry (#100) is a change too.
@MainActor
final class SettingsStore {

    private(set) var config: Config
    /// The spend meter of each agent from its last available snapshot (#105), in memory only, for the sheet.
    private(set) var meters: [AgentId: SpendMeterInfo] = [:]
    /// Called after each change, for the window to reload.
    var onChange: (() -> Void)?

    private let fileURL: URL
    private let push: (Config) -> Void
    private let pushDelay: TimeInterval
    private var pushTimer: Timer?

    init(config: Config, fileURL: URL, push: @escaping (Config) -> Void, pushDelay: TimeInterval) {
        self.config = config
        self.fileURL = fileURL
        self.push = push
        self.pushDelay = pushDelay
    }

    func setAgents(_ agents: [AgentConfig]) {
        commit { $0.agents = agents }
    }

    func setRefreshInterval(_ seconds: Int) {
        commit { $0.refreshIntervalSeconds = seconds }
    }

    private func commit(_ change: (inout Config) -> Void) {
        change(&config)
        do {
            try ConfigFile.write(config, to: fileURL)
        } catch {
            Log.settings.error("config.json could not be written: \(error)")
        }
        onChange?()
        schedulePush()
    }

    /// Adds an entry for each agent in a snapshot that has none (#100). Nothing is written or pushed when every agent
    /// has an entry.
    func addDetected(_ ids: [AgentId]) {
        let next = config.agents.addingDetected(ids)
        guard next != config.agents else { return }
        let added = next.dropFirst(config.agents.count).map(\.id.rawValue).joined(separator: ", ")
        Log.settings.notice("added detected agents: \(added)")
        setAgents(next)
    }

    /// Keeps the spend unit and limit of each available agent (#105). An unavailable agent keeps its last value. Nothing
    /// is written or pushed, and the window is not told.
    func noteMeters(_ agents: [AgentSnapshot]) {
        for agent in agents where agent.status != .unavailable {
            meters[agent.id] = agent.spend.map { .spend(currency: $0.currency, limit: $0.limit) } ?? .noSpend
        }
    }

    private func schedulePush() {
        pushTimer?.invalidate()
        pushTimer = Timer.scheduledTimer(withTimeInterval: pushDelay, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.pushTimer = nil
                self.push(self.config)
            }
        }
    }
}

/// Edits of the agent list. Pure, so the window and the tests use the same rules.
extension Array where Element == AgentConfig {

    /// Replaces the entry with the same id, else appends.
    func adding(_ agent: AgentConfig) -> [AgentConfig] {
        if contains(where: { $0.id == agent.id }) {
            return map { $0.id == agent.id ? agent : $0 }
        } else {
            return self + [agent]
        }
    }

    /// The detected agents that have no entry yet, appended with the defaults, in the order given (#100). Existing
    /// entries are kept as they are.
    func addingDetected(_ ids: [AgentId]) -> [AgentConfig] {
        ids.reduce(self) { agents, id in
            agents.contains(where: { $0.id == id }) ? agents : agents + [AgentConfig.defaults(id)]
        }
    }

    /// Moves the entry at `source` to before the row `destination` (a table drop index, `count` for the end), as
    /// `NSTableView` drops do (#104).
    func moving(from source: Int, to destination: Int) -> [AgentConfig] {
        guard indices.contains(source), destination >= 0, destination <= count else { return self }
        let target = newIndex(movingFrom: source, to: destination)
        guard target != source else { return self }
        var moved = self
        let entry = moved.remove(at: source)
        moved.insert(entry, at: target)
        return moved
    }

    /// The row of the moved entry after `moving(from:to:)`.
    func newIndex(movingFrom source: Int, to destination: Int) -> Int {
        destination > source ? destination - 1 : destination
    }

    func settingEnabled(_ enabled: Enabled, for id: AgentId) -> [AgentConfig] {
        map { agent in
            guard agent.id == id else { return agent }
            var changed = agent
            changed.enabled = enabled
            return changed
        }
    }

    /// The agents that are not in the list yet, in `AgentId` order.
    var addable: [AgentId] {
        AgentId.allCases.filter { id in !contains(where: { $0.id == id }) }
    }
}
