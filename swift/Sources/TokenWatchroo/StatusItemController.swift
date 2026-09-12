import AppKit

/// Owns the `NSStatusItem`: ring image, label, and the menu. Rebuilds the menu on every snapshot (deferred while it
/// is open), refreshes when the menu opens on a stale snapshot, and keeps the exhausted countdown label current.
@MainActor
final class StatusItemController: NSObject, NSMenuDelegate {

    private let core: Core
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let builder = MenuBuilder()

    private var snapshot: Snapshot?
    private var receivedAt: Date?
    private var menuIsOpen = false
    private var rebuildPending = false
    private var menuTimer: Timer?
    private var labelTimer: Timer?

    private static let staleAfter: TimeInterval = 30

    init(core: Core) {
        self.core = core
        super.init()
        render()
        installMenu()
        labelTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.render() }
        }
    }

    func apply(snapshot: Snapshot) {
        self.snapshot = snapshot
        receivedAt = Date()
        render()
        if menuIsOpen {
            rebuildPending = true
            builder.tick(snapshot: snapshot, receivedAt: receivedAt)
        } else {
            installMenu()
        }
    }

    // MARK: menubar item

    private func render() {
        guard let button = item.button else { return }
        let state = snapshot?.menubar ?? MenubarState(kind: .unavailable, usedPercent: nil, resetsAt: nil)
        button.image = RingIcon.image(kind: state.kind, percent: state.usedPercent)
        button.imagePosition = .imageLeading
        let label: String
        switch state.kind {
        case .exhausted:
            label = state.resetsAt.map { Formatting.shortCountdown(toEpoch: $0) } ?? "--"
        case .normal, .warning, .critical:
            label = state.usedPercent.map(Formatting.percent) ?? "--"
        case .unavailable:
            label = "--"
        }
        var attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .semibold),
        ]
        if let tint = RingIcon.tint(for: state.kind) {
            attributes[.foregroundColor] = tint
        }
        button.attributedTitle = NSAttributedString(string: " " + label, attributes: attributes)
        button.toolTip = "Token Watchroo"
    }

    // MARK: menu

    private func installMenu() {
        let menu = builder.rebuild(snapshot: snapshot, receivedAt: receivedAt, target: self)
        menu.delegate = self
        item.menu = menu
        rebuildPending = false
    }

    func menuWillOpen(_ menu: NSMenu) {
        menuIsOpen = true
        if let receivedAt, Date().timeIntervalSince(receivedAt) > StatusItemController.staleAfter {
            core.refresh()
        } else if receivedAt == nil {
            core.refresh()
        }
        menuTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.builder.tick(snapshot: self.snapshot, receivedAt: self.receivedAt)
            }
        }
        RunLoop.current.add(menuTimer!, forMode: .eventTracking)
    }

    func menuDidClose(_ menu: NSMenu) {
        menuIsOpen = false
        menuTimer?.invalidate()
        menuTimer = nil
        if rebuildPending {
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated { self?.installMenu() }
            }
        }
    }

    // MARK: actions

    @objc func refreshNow(_ sender: Any?) {
        core.refresh()
    }

    @objc func toggleLaunchAtLogin(_ sender: Any?) {
        LaunchAtLogin.toggle()
        installMenu()
    }

    @objc func quit(_ sender: Any?) {
        NSApp.terminate(nil)
    }
}
