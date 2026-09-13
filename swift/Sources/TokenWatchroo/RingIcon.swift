import AppKit

/// The 16 by 16 menubar ring from `.ai/docs/design/ui/MenubarStates.dc.html`: radius 6 at the centre, stroke 2, a
/// track at 30% opacity, and an arc from 12 o'clock clockwise by the used percent. Exhausted is a filled disc with a
/// clock glyph.
enum RingIcon {

    static let warningColor = NSColor(srgbRed: 0xF0 / 255, green: 0xB7 / 255, blue: 0x3A / 255, alpha: 1)
    static let criticalColor = NSColor(srgbRed: 0xFF / 255, green: 0x6B / 255, blue: 0x5C / 255, alpha: 1)

    static func image(kind: MenubarKind, percent: Double?) -> NSImage {
        let size = NSSize(width: 16, height: 16)
        let image = NSImage(size: size, flipped: false) { _ in
            draw(kind: kind, percent: percent)
            return true
        }
        image.isTemplate = kind == .normal || kind == .unavailable
        return image
    }

    static func tint(for kind: MenubarKind) -> NSColor? {
        switch kind {
        case .warning: return warningColor
        case .critical, .exhausted: return criticalColor
        case .normal, .unavailable: return nil
        }
    }

    private static func draw(kind: MenubarKind, percent: Double?) {
        let center = NSPoint(x: 8, y: 8)
        let radius: CGFloat = 6
        let color = tint(for: kind) ?? NSColor.black

        if kind == .exhausted {
            let disc = NSBezierPath(ovalIn: NSRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
            color.setFill()
            disc.fill()
            disc.lineWidth = 2
            color.setStroke()
            disc.stroke()
            // Clock hand, the SVG path M8 4.8 v3.4 l2.2 1.4 converted to a y-up coordinate system.
            let hand = NSBezierPath()
            hand.move(to: NSPoint(x: 8, y: 11.2))
            hand.line(to: NSPoint(x: 8, y: 7.8))
            hand.line(to: NSPoint(x: 10.2, y: 6.4))
            hand.lineWidth = 1.6
            hand.lineCapStyle = .round
            hand.lineJoinStyle = .round
            NSColor(white: 0.17, alpha: 1).setStroke()
            hand.stroke()
            return
        }

        let track = NSBezierPath(ovalIn: NSRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        track.lineWidth = 2
        color.withAlphaComponent(0.3).setStroke()
        track.stroke()

        guard let percent, percent > 0 else { return }
        let fraction = min(max(percent / 100, 0), 1)
        let arc = NSBezierPath()
        arc.appendArc(withCenter: center, radius: radius, startAngle: 90, endAngle: 90 - 360 * fraction, clockwise: true)
        arc.lineWidth = 2
        arc.lineCapStyle = .round
        color.setStroke()
        arc.stroke()
    }
}
