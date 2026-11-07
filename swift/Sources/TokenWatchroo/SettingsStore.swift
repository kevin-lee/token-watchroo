import Foundation

/// Holds the current config for the settings window (#8). A committed change is written to `config.json` at once and
/// pushed to the library after `pushDelay` without another change, so a burst of clicks gives one `tw_set_config` and
/// one fetch. A failed write is logged, and the change is still pushed, so the running app follows the user.
@MainActor
final class SettingsStore {

    private(set) var config: Config
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
        config.agents = agents
        do {
            try ConfigFile.write(config, to: fileURL)
        } catch {
            Log.settings.error("config.json could not be written: \(error)")
        }
        onChange?()
        schedulePush()
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

    func removing(_ id: AgentId) -> [AgentConfig] {
        filter { $0.id != id }
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
