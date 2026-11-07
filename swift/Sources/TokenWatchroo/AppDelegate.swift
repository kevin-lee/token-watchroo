import AppKit
import Foundation

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: StatusItemController?
    private let updater = Updater()
    private var settingsStore: SettingsStore?
    private var settingsWindow: SettingsWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let stateDirectory = AppDelegate.stateDirectory()
        let configURL = ConfigFile.url(in: stateDirectory)
        let config = AppDelegate.loadConfig(from: configURL, stateDir: stateDirectory.path)

        let core = Core.shared
        let store = SettingsStore(config: config, fileURL: configURL, push: { core.setConfig($0) }, pushDelay: 1)
        let settings = SettingsWindowController(store: store)
        settingsStore = store
        settingsWindow = settings
        let controller = StatusItemController(core: core, updater: updater, settings: settings)
        statusItem = controller

        core.snapshotHandler = { [weak controller] snapshot in controller?.apply(snapshot: snapshot) }
        core.alertHandler = { alert in Notifier.shared.post(alert) }

        let code = core.start(config: config)
        if code != 0 {
            Log.app.error("tw_start failed with code \(code), quitting")
            NSApp.terminate(nil)
            return
        }
        // scripts/check-launch.sh looks for this line: a hang inside core.start keeps the process alive (#87).
        Log.app.notice("tw_start returned 0 pid=\(ProcessInfo.processInfo.processIdentifier)")

        Notifier.shared.requestAuthorizationIfBundled()
        updater.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        Core.shared.shutdown()
    }

    /// The config from `config.json` (#8). A missing file is the first run and gives the defaults without a file. An
    /// undecodable file is moved to `config.json.bad`, so the user's text is kept and the next save does not replace
    /// it, and the defaults are used. An unreadable file stays where it is.
    private static func loadConfig(from url: URL, stateDir: String) -> Config {
        let loaded = ConfigFile.load(from: url, stateDir: stateDir)
        switch loaded {
        case .missing, .loaded:
            break
        case .undecodable(_, let detail):
            do {
                try ConfigFile.moveAside(url)
                Log.settings.error("config.json could not be read, moved to \(ConfigFile.asideFileName): \(detail)")
            } catch {
                Log.settings.error("config.json could not be read and could not be moved aside: \(detail), \(error)")
            }
        case .unreadable(_, let detail):
            Log.settings.error("config.json could not be read: \(detail)")
        }
        return loaded.config
    }

    /// `~/Library/Application Support/Token Watchroo`, created if needed.
    private static func stateDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        let directory = base.appendingPathComponent("Token Watchroo", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
