import AppKit

/// The per-agent card from `design/Main.dc.html`, drawn directly: 328 wide, corner radius 10, half-point border,
/// 12 pt padding, name, plan badge, status pill, and one row plus bar per window.
final class AgentCardView: NSView {

    static let width: CGFloat = 328
    private static let padding: CGFloat = 12
    private static let headerHeight: CGFloat = 18
    private static let blockGap: CGFloat = 10
    private static let rowHeight: CGFloat = 15
    private static let rowGap: CGFloat = 5
    private static let barHeight: CGFloat = 6
    private static let noteHeight: CGFloat = 14

    private var agent: AgentSnapshot
    private var now: Date

    init(agent: AgentSnapshot, now: Date) {
        self.agent = agent
        self.now = now
        super.init(frame: NSRect(x: 0, y: 0, width: AgentCardView.width, height: AgentCardView.height(for: agent)))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    static func height(for agent: AgentSnapshot) -> CGFloat {
        let body: CGFloat
        if agent.status == .unavailable {
            body = noteHeight * 2
        } else {
            let windows = CGFloat(max(agent.windows.count, 1))
            body = windows * (rowHeight + rowGap + barHeight) + (windows - 1) * blockGap
                + (agent.source == .localLog || agent.error != nil ? rowGap + noteHeight : 0)
        }
        return padding + headerHeight + blockGap + body + padding
    }

    func update(agent: AgentSnapshot, now: Date) {
        self.agent = agent
        self.now = now
        needsDisplay = true
    }

    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        let p = AgentCardView.padding
        let card = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.25, dy: 0.25), xRadius: 10, yRadius: 10)
        Palette.cardBackground.setFill()
        card.fill()
        card.lineWidth = 0.5
        Palette.cardBorder(for: agent.status).setStroke()
        card.stroke()

        var y = p
        drawHeader(at: y)
        y += AgentCardView.headerHeight + AgentCardView.blockGap

        if agent.status == .unavailable {
            let text = agent.error ?? "Unavailable"
            draw(text, at: NSPoint(x: p, y: y), font: .systemFont(ofSize: 11), color: Palette.secondaryText,
                 maxWidth: bounds.width - 2 * p)
            return
        }

        for (index, window) in agent.windows.enumerated() {
            if index > 0 { y += AgentCardView.blockGap }
            drawWindow(window, at: y)
            y += AgentCardView.rowHeight + AgentCardView.rowGap + AgentCardView.barHeight
        }

        if agent.source == .localLog {
            y += AgentCardView.rowGap
            let note = agent.error.map { "from local log · \($0)" } ?? "from local log"
            draw(note, at: NSPoint(x: p, y: y), font: .systemFont(ofSize: 11), color: Palette.secondaryText,
                 maxWidth: bounds.width - 2 * p)
        } else if let error = agent.error {
            y += AgentCardView.rowGap
            draw(error, at: NSPoint(x: p, y: y), font: .systemFont(ofSize: 11), color: Palette.secondaryText,
                 maxWidth: bounds.width - 2 * p)
        }
    }

    private func drawHeader(at y: CGFloat) {
        let p = AgentCardView.padding
        var x = p
        let name = NSAttributedString(string: agent.id.displayName, attributes: [
            .font: NSFont.systemFont(ofSize: 13, weight: .semibold), .foregroundColor: Palette.primaryText,
        ])
        name.draw(at: NSPoint(x: x, y: y))
        x += name.size().width + 8

        if let plan = agent.planLabel {
            drawPill(plan, at: NSPoint(x: x, y: y + 2), font: .systemFont(ofSize: 11), text: Palette.badgeText,
                     background: Palette.badgeBackground, radius: 4, horizontalPadding: 6, verticalPadding: 1)
        }

        let (pillText, pillTextColor, pillBackground) = Palette.statusPill(for: agent.status)
        let pillFont = NSFont.systemFont(ofSize: 11, weight: .medium)
        let pillSize = NSAttributedString(string: pillText, attributes: [.font: pillFont]).size()
        let pillWidth = pillSize.width + 16
        drawPill(pillText, at: NSPoint(x: bounds.width - p - pillWidth, y: y + 1), font: pillFont, text: pillTextColor,
                 background: pillBackground, radius: 999, horizontalPadding: 8, verticalPadding: 2)
    }

    private func drawWindow(_ window: UsageWindow, at y: CGFloat) {
        let p = AgentCardView.padding
        let left = NSMutableAttributedString(string: window.id.label, attributes: [
            .font: NSFont.systemFont(ofSize: 12), .foregroundColor: Palette.primaryText,
        ])
        if window.id == .session {
            left.append(NSAttributedString(string: " · 5 h window", attributes: [
                .font: NSFont.systemFont(ofSize: 12), .foregroundColor: Palette.secondaryText,
            ]))
        }
        left.draw(at: NSPoint(x: p, y: y))

        let right = NSMutableAttributedString()
        if window.isIdle {
            right.append(NSAttributedString(string: "idle", attributes: [
                .font: NSFont.systemFont(ofSize: 12), .foregroundColor: Palette.secondaryText,
            ]))
        } else {
            right.append(NSAttributedString(string: Formatting.percent(window.usedPercent), attributes: [
                .font: NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .semibold),
                .foregroundColor: Palette.percentText(for: window.usedPercent),
            ]))
            if let resetsAt = window.resetsAt {
                let reset = window.id == .session ? Formatting.resetsIn(epoch: resetsAt, now: now) : Formatting.resetsAt(epoch: resetsAt)
                right.append(NSAttributedString(string: " · \(reset)", attributes: [
                    .font: NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular),
                    .foregroundColor: Palette.secondaryText,
                ]))
            }
        }
        right.draw(at: NSPoint(x: bounds.width - p - right.size().width, y: y))

        let barY = y + AgentCardView.rowHeight + AgentCardView.rowGap
        let track = NSRect(x: p, y: barY, width: bounds.width - 2 * p, height: AgentCardView.barHeight)
        Palette.barTrack.setFill()
        NSBezierPath(roundedRect: track, xRadius: 3, yRadius: 3).fill()
        let fraction = min(max(window.usedPercent / 100, 0), 1)
        if fraction > 0 {
            let fill = NSRect(x: track.minX, y: barY, width: track.width * fraction, height: AgentCardView.barHeight)
            Palette.barFill(for: window).setFill()
            NSBezierPath(roundedRect: fill, xRadius: 3, yRadius: 3).fill()
        }
    }

    private func drawPill(_ text: String, at origin: NSPoint, font: NSFont, text textColor: NSColor, background: NSColor,
                          radius: CGFloat, horizontalPadding: CGFloat, verticalPadding: CGFloat) {
        let attributed = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: textColor])
        let size = attributed.size()
        let rect = NSRect(x: origin.x, y: origin.y, width: size.width + 2 * horizontalPadding,
                          height: size.height + 2 * verticalPadding)
        background.setFill()
        NSBezierPath(roundedRect: rect, xRadius: min(radius, rect.height / 2), yRadius: min(radius, rect.height / 2)).fill()
        attributed.draw(at: NSPoint(x: rect.minX + horizontalPadding, y: rect.minY + verticalPadding))
    }

    private func draw(_ text: String, at origin: NSPoint, font: NSFont, color: NSColor, maxWidth: CGFloat) {
        let attributed = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color])
        attributed.draw(with: NSRect(x: origin.x, y: origin.y, width: maxWidth, height: AgentCardView.noteHeight * 2),
                        options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine])
    }
}

/// Light values are transcribed from the artboards, dark values keep the same contrast.
enum Palette {

    private static func dynamic(light: NSColor, dark: NSColor) -> NSColor {
        NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
        }
    }

    private static func rgb(_ hex: UInt32, alpha: CGFloat = 1) -> NSColor {
        NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }

    static let cardBackground = dynamic(light: rgb(0xFFFFFF), dark: rgb(0x2A2A2E))
    static let primaryText = dynamic(light: rgb(0x1D1D1F), dark: rgb(0xF2F2F4))
    static let secondaryText = dynamic(light: rgb(0x7A7A80), dark: rgb(0x9A9AA0))
    static let badgeText = dynamic(light: rgb(0x6E6E73), dark: rgb(0xC0C0C6))
    static let badgeBackground = dynamic(light: rgb(0xECECF0), dark: rgb(0x3A3A3F))
    static let barTrack = dynamic(light: rgb(0xE6E6EA), dark: rgb(0x3A3A3F))
    static let sessionFill = dynamic(light: rgb(0x1D1D1F), dark: rgb(0xE6E6EA))
    static let weeklyFill = dynamic(light: rgb(0x8E8E93), dark: rgb(0x9A9AA0))
    static let amber = rgb(0xD9A021)
    static let red = rgb(0xFF6B5C)

    static func cardBorder(for status: AgentStatus) -> NSColor {
        switch status {
        case .warning: return rgb(0xD9A021, alpha: 0.55)
        case .critical, .exhausted: return rgb(0xFF6B5C, alpha: 0.55)
        case .ok, .unavailable: return dynamic(light: rgb(0x000000, alpha: 0.10), dark: rgb(0xFFFFFF, alpha: 0.12))
        }
    }

    static func statusPill(for status: AgentStatus) -> (String, NSColor, NSColor) {
        switch status {
        case .ok: return ("OK", dynamic(light: rgb(0x3A8F5A), dark: rgb(0x7FD1A7)), dynamic(light: rgb(0xE6F4EA), dark: rgb(0x1F3A2A)))
        case .warning: return ("Near limit", dynamic(light: rgb(0x9A6B00), dark: rgb(0xF0B73A)), dynamic(light: rgb(0xFBF1D6), dark: rgb(0x3D3117)))
        case .critical: return ("Almost out", dynamic(light: rgb(0xB3261E), dark: rgb(0xFF6B5C)), dynamic(light: rgb(0xFDE7E5), dark: rgb(0x43221F)))
        case .exhausted: return ("Exhausted", dynamic(light: rgb(0xB3261E), dark: rgb(0xFF6B5C)), dynamic(light: rgb(0xFDE7E5), dark: rgb(0x43221F)))
        case .unavailable: return ("Unavailable", badgeText, badgeBackground)
        }
    }

    static func percentText(for percent: Double) -> NSColor {
        if percent >= 95 { return red }
        if percent >= 80 { return dynamic(light: rgb(0x9A6B00), dark: rgb(0xF0B73A)) }
        return primaryText
    }

    static func barFill(for window: UsageWindow) -> NSColor {
        if window.usedPercent >= 95 { return red }
        if window.usedPercent >= 80 { return amber }
        return window.id == .session ? sessionFill : weeklyFill
    }
}
