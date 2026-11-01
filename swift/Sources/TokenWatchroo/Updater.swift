import AppKit
import Sparkle

/// Self-update through Sparkle (#71). It starts only in a bundle whose plist names a feed and a public key, so a run from
/// `swift run` or the tests has no updater. The app is `LSUIElement`, so an update alert gets a Dock icon while it shows
/// and the app goes back to an accessory when the update session ends (Sparkle's gentle reminders).
@MainActor
final class Updater: NSObject, SPUUpdaterDelegate, SPUStandardUserDriverDelegate {

    private var controller: SPUStandardUpdaterController?

    static func isConfigured(infoDictionary: [String: Any]?) -> Bool {
        ["SUFeedURL", "SUPublicEDKey"].allSatisfy { key in
            (infoDictionary?[key] as? String).map { !$0.isEmpty } ?? false
        }
    }

    func start() {
        guard Updater.isConfigured(infoDictionary: Bundle.main.infoDictionary) else { return }
        controller = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: self)
    }

    var canCheckForUpdates: Bool { controller?.updater.canCheckForUpdates ?? false }

    func checkForUpdates() {
        NSApp.activate()
        controller?.updater.checkForUpdates()
    }

    // MARK: SPUUpdaterDelegate

    /// A failed background check shows nothing, so this line is the only trace of it. A check the user started also
    /// shows Sparkle's error.
    nonisolated func updater(_ updater: SPUUpdater, didFinishUpdateCycleFor updateCheck: SPUUpdateCheck, error: (any Error)?) {
        guard let error else { return }
        let nsError = error as NSError
        let line = "update cycle ended: check=\(updateCheck.rawValue) domain=\(nsError.domain) code=\(nsError.code) detail=\(nsError.localizedDescription)"
        if Updater.isExpectedEnd(nsError) {
            Log.update.notice(line)
        } else {
            Log.update.error(line)
        }
    }

    /// No newer version, or the user cancelled the installation.
    nonisolated static func isExpectedEnd(_ error: NSError) -> Bool {
        error.domain == SUSparkleErrorDomain
            && [SUError.noUpdateError.rawValue, SUError.installationCanceledError.rawValue].contains(OSStatus(error.code))
    }

    // MARK: SPUStandardUserDriverDelegate

    nonisolated var supportsGentleScheduledUpdateReminders: Bool { true }

    nonisolated func standardUserDriverWillHandleShowingUpdate(_ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState) {
        guard handleShowingUpdate else { return }
        DispatchQueue.main.async { NSApp.setActivationPolicy(.regular) }
    }

    nonisolated func standardUserDriverWillFinishUpdateSession() {
        DispatchQueue.main.async { NSApp.setActivationPolicy(.accessory) }
    }
}
