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

    /// "$0.05" for an ISO currency code, "8,000" for credits. The narrow symbol matches the Claude usage page in every
    /// locale, where the standard style gives "USD 0.05" in en_AU.
    static func money(_ amount: Decimal, currency: String, locale: Locale = .current) -> String {
        if currency == "credits" {
            return amount.formatted(Decimal.FormatStyle(locale: locale).precision(.fractionLength(0...2)))
        }
        return amount.formatted(Decimal.FormatStyle.Currency(code: currency, locale: locale).presentation(.narrow))
    }

    /// "$0.05 of $200.00 spent", or "8,000 of 25,000 credits spent".
    static func spendSummary(_ spend: Spend, locale: Locale = .current) -> String {
        let spent = money(spend.spent, currency: spend.currency, locale: locale)
        let limit = money(spend.limit, currency: spend.currency, locale: locale)
        return spend.isCredits ? "\(spent) of \(limit) credits spent" : "\(spent) of \(limit) spent"
    }

    /// "resets Thu, Oct 1", the date only, as the Claude usage page shows a spend limit reset.
    static func resetsOn(epoch: Int64, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.setLocalizedDateFormatFromTemplate("EEEMMMd")
        return "resets \(formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epoch))))"
    }

    /// The right side of a window row whose reset time has passed (issue #81).
    static let expiredWindow = "reset · no newer data"

    /// "Sep 28 at 13:11": when local-log data was recorded.
    static func observedAt(epoch: Int64, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.setLocalizedDateFormatFromTemplate("MMMd HH:mm")
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
    }

    /// "from local log (Sep 28 at 13:11) · Network error: curl 6", with each part only when it is known.
    static func localLogNote(observedAt epoch: Int64?, error: String?, locale: Locale = .current,
                             timeZone: TimeZone = .current) -> String {
        var note = "from local log"
        if let epoch { note += " (\(observedAt(epoch: epoch, locale: locale, timeZone: timeZone)))" }
        if let error { note += " · \(error)" }
        return note
    }

    private static let localTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = TimeZone.current
        formatter.setLocalizedDateFormatFromTemplate("EEE HH:mm")
        return formatter
    }()
}
