import Foundation

/// Time-dependent labels are rendered here, on the shell's own timers, so they never go stale between polls.
@MainActor
enum Formatting {

    /// "Updated 30 s ago"
    static func updatedAgo(since date: Date?, now: Date = Date()) -> String {
        guard let date else { return "Waiting for first update" }
        let seconds = max(0, Int(now.timeIntervalSince(date)))
        if seconds < 60 { return "Updated \(seconds) s ago" }
        if seconds < 3600 { return "Updated \(seconds / 60) m ago" }
        return "Updated \(seconds / 3600) h ago"
    }

    /// "<1 m", "38 m", "1 h 12 m", "2 d 3 h", or "resetting..." when the reset time has passed.
    static func shortCountdown(toEpoch epoch: Int64, now: Date = Date()) -> String {
        let remaining = Int(epoch - Int64(now.timeIntervalSince1970))
        if remaining < 0 { return "resetting..." }
        return shortDuration(seconds: remaining)
    }

    static func shortDuration(seconds: Int) -> String {
        let total = max(0, seconds)
        let days = total / 86400
        let hours = (total % 86400) / 3600
        let minutes = (total % 3600) / 60
        if total < 60 { return "<1 m" }
        if days > 0 { return hours > 0 ? "\(days) d \(hours) h" : "\(days) d" }
        if hours > 0 { return minutes > 0 ? "\(hours) h \(minutes) m" : "\(hours) h" }
        return "\(minutes) m"
    }

    /// "resets in 1 h 12 m"
    static func resetsIn(epoch: Int64, now: Date = Date()) -> String {
        "resets in \(shortCountdown(toEpoch: epoch, now: now))"
    }

    /// "resets Mon 09:00" in the local time zone.
    static func resetsAt(epoch: Int64) -> String {
        "resets \(localTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(epoch))))"
    }

    /// "72%"
    static func percent(_ value: Double) -> String {
        "\(Int(value.rounded()))%"
    }

    private static let localTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = TimeZone.current
        formatter.setLocalizedDateFormatFromTemplate("EEE HH:mm")
        return formatter
    }()
}
