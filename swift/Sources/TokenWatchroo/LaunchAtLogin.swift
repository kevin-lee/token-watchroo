import Foundation
import ServiceManagement

/// `SMAppService.mainApp`, which needs the bundled app.
enum LaunchAtLogin {

    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    static var isAvailable: Bool {
        Bundle.main.bundleIdentifier != nil
    }

    static func toggle() {
        do {
            if isEnabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            NSLog("[token-watchroo] launch at login change failed: %@", error.localizedDescription)
        }
    }
}
