import Foundation
import UserNotifications

/// System notifications per `design/Alerts.dc.html`. `UNUserNotificationCenter` aborts the process outside an app
/// bundle, so everything is gated on a bundle identifier being present.
@MainActor
final class Notifier {

    static let shared = Notifier()

    private var available: Bool { Bundle.main.bundleIdentifier != nil }
    private var authorizationRequested = false

    private init() {}

    func requestAuthorizationIfBundled() {
        guard available, !authorizationRequested else { return }
        authorizationRequested = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                NSLog("[token-watchroo] notification authorization failed: %@", error.localizedDescription)
            } else if !granted {
                NSLog("[token-watchroo] notifications not granted")
            }
        }
    }

    /// The identifier comes from the library, so macOS replaces a pending notification with the same identifier.
    func post(_ alert: AlertPayload) {
        guard available else {
            NSLog("[token-watchroo] alert (no bundle, not shown): %@ - %@", alert.title, alert.body)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        let request = UNNotificationRequest(identifier: alert.identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("[token-watchroo] notification failed: %@", error.localizedDescription)
            }
        }
    }
}
