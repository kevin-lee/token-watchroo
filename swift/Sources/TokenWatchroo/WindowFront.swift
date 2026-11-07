import AppKit

/// Brings a window of this `LSUIElement` app in front of other apps' windows (#8, #97). A menu action runs while the
/// status menu is still open, so the steps wait until it has closed. `activate()` is only a request since macOS 14, and
/// the system can refuse it for an LSUIElement app, which left the window behind the frontmost app.
/// `orderFrontRegardless()` puts the window in front of other apps' windows even then.
@MainActor
enum WindowFront {

    static func show(_ open: @escaping @MainActor () -> NSWindow?) {
        DispatchQueue.main.async {
            NSApp.activate()
            guard let window = open() else { return }
            window.makeKeyAndOrderFront(nil)
            window.orderFrontRegardless()
        }
    }

    /// The one window in `after` that was not in `before`, or nil when there is none or more than one.
    nonisolated static func newWindow<ID: Hashable>(before: [ID], after: [ID]) -> ID? {
        let old = Set(before)
        let added = after.filter { !old.contains($0) }
        return added.count == 1 ? added.first : nil
    }
}
