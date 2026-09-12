import AppKit
import Foundation

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: StatusItemController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let stateDirectory = AppDelegate.stateDirectory()
        let config = Config(
            stateDir: stateDirectory.path,
            refreshIntervalSeconds: 60,
            codexHome: nil,
            claudeCodeVersionOverride: nil
        )

        let core = Core.shared
        let controller = StatusItemController(core: core)
        statusItem = controller

        core.snapshotHandler = { [weak controller] snapshot in controller?.apply(snapshot: snapshot) }
        core.alertHandler = { alert in Notifier.shared.post(alert) }
        core.errorHandler = { message in NSLog("[token-watchroo] library error: %@", message) }

        let code = core.start(config: config)
        if code != 0 {
            NSLog("[token-watchroo] tw_start failed with code %d, quitting", code)
            NSApp.terminate(nil)
            return
        }

        Notifier.shared.requestAuthorizationIfBundled()
    }

    func applicationWillTerminate(_ notification: Notification) {
        Core.shared.shutdown()
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
