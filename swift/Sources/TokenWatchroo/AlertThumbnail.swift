import AppKit
import UserNotifications

/// The Alerts artboard tile from `design/Alerts.dc.html`: a dark rounded square with the ring in the alert's colour,
/// drawn per alert at the alert's percent and attached to the notification. The app icon on the left of the banner is
/// the bundle icon and cannot change, so the tile appears on the right.
enum AlertThumbnail {

    private static let size: CGFloat = 256
    private static let tileColor = NSColor(srgbRed: 0x1D / 255, green: 0x1D / 255, blue: 0x1F / 255, alpha: 1)

    static func attachment(for alert: AlertPayload) -> UNNotificationAttachment? {
        guard let style = style(for: alert.kind) else { return nil }
        let percent = min(max(alert.usedPercent ?? 0, 0), 100)
        guard let png = render(color: style.color, percent: style.drawsArc ? percent : nil) else { return nil }
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("token-watchroo-alert-\(UUID().uuidString).png")
        do {
            try png.write(to: fileURL)
            return try UNNotificationAttachment(
                identifier: "ring",
                url: fileURL,
                options: [UNNotificationAttachmentOptionsTypeHintKey: "public.png"]
            )
        } catch {
            NSLog("[token-watchroo] alert thumbnail failed: %@", error.localizedDescription)
            return nil
        }
    }

    private struct Style {
        let color: NSColor
        let drawsArc: Bool
    }

    /// Amber at 80, red at 95, white with the artboard's empty arc on reset. Any other kind gets no attachment.
    private static func style(for kind: String) -> Style? {
        switch kind {
        case "threshold80": return Style(color: RingIcon.warningColor, drawsArc: true)
        case "threshold95": return Style(color: RingIcon.criticalColor, drawsArc: true)
        case "reset": return Style(color: .white, drawsArc: false)
        default: return nil
        }
    }

    /// The 38 by 38 tile with the 22 by 22 ring of the artboard, scaled to `size`, on a transparent background.
    private static func render(color: NSColor, percent: Double?) -> Data? {
        let pixels = Int(size)
        guard let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: pixels,
            pixelsHigh: pixels,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else { return nil }

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        draw(color: color, percent: percent)
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .png, properties: [:])
    }

    private static func draw(color: NSColor, percent: Double?) {
        let scale = size / 38
        let tile = NSBezierPath(
            roundedRect: NSRect(x: 0, y: 0, width: size, height: size),
            xRadius: 9 * scale,
            yRadius: 9 * scale
        )
        tileColor.setFill()
        tile.fill()

        // The ring SVG is 22 points in the 38 point tile, drawn on a 16 grid: radius 6, stroke 2.
        let grid = 22 * scale / 16
        let center = NSPoint(x: size / 2, y: size / 2)
        let radius = 6 * grid
        let stroke = 2 * grid

        let track = NSBezierPath(ovalIn: NSRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        track.lineWidth = stroke
        color.withAlphaComponent(0.3).setStroke()
        track.stroke()

        guard let percent, percent > 0 else { return }
        let fraction = percent / 100
        let arc = NSBezierPath()
        arc.appendArc(withCenter: center, radius: radius, startAngle: 90, endAngle: 90 - 360 * fraction, clockwise: true)
        arc.lineWidth = stroke
        arc.lineCapStyle = .round
        color.setStroke()
        arc.stroke()
    }
}
