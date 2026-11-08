import Foundation

/// Time-dependent labels are rendered here, on the shell's own timers, so they never go stale between polls. The words
/// come from `strings` (#10), and dates and money follow the macOS region.
@MainActor
enum Formatting {

    /// "Updated 30 s ago"
    static func updatedAgo(since date: Date?, now: Date = Date(), strings: AppStrings) -> String {
        guard let date else { return strings.text(.headerWaiting) }
        let seconds = max(0, Int(now.timeIntervalSince(date)))
        if seconds < 60 { return strings.text(.headerUpdatedSeconds, ["n": "\(seconds)"]) }
        if seconds < 3600 { return strings.text(.headerUpdatedMinutes, ["n": "\(seconds / 60)"]) }
        return strings.text(.headerUpdatedHours, ["n": "\(seconds / 3600)"])
    }

    /// "<1 m", "38 m", "1 h 12 m", "2 d 3 h", or "resetting..." when the reset time has passed.
    static func shortCountdown(toEpoch epoch: Int64, now: Date = Date(), strings: AppStrings) -> String {
        let remaining = Int(epoch - Int64(now.timeIntervalSince1970))
        if remaining < 0 { return strings.text(.shortResetting) }
        return shortDuration(seconds: remaining, strings: strings)
    }

    static func shortDuration(seconds: Int, strings: AppStrings) -> String {
        let total = max(0, seconds)
        let days = total / 86400
        let hours = (total % 86400) / 3600
        let minutes = (total % 3600) / 60
        if total < 60 { return strings.text(.shortUnderMinute) }
        if days > 0 {
            return hours > 0
                ? strings.text(.shortDaysHours, ["d": "\(days)", "h": "\(hours)"])
                : strings.text(.shortDays, ["d": "\(days)"])
        }
        if hours > 0 {
            return minutes > 0
                ? strings.text(.shortHoursMinutes, ["h": "\(hours)", "m": "\(minutes)"])
                : strings.text(.shortHours, ["h": "\(hours)"])
        }
        return strings.text(.shortMinutes, ["m": "\(minutes)"])
    }

    /// "resets in 1 h 12 m"
    static func resetsIn(epoch: Int64, now: Date = Date(), strings: AppStrings) -> String {
        strings.text(.cardResetsIn, ["time": shortCountdown(toEpoch: epoch, now: now, strings: strings)])
    }

    /// "resets Mon 09:00" in the local time zone.
    static func resetsAt(epoch: Int64, strings: AppStrings) -> String {
        strings.text(.cardResetsAt, ["time": localTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))])
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
    static func spendSummary(_ spend: Spend, locale: Locale = .current, strings: AppStrings) -> String {
        let args = [
            "spent": money(spend.spent, currency: spend.currency, locale: locale),
            "limit": money(spend.limit, currency: spend.currency, locale: locale),
        ]
        return strings.text(spend.isCredits ? .spendSummaryCredits : .spendSummary, args)
    }

    /// "resets Thu, Oct 1", the date only, as the Claude usage page shows a spend limit reset.
    static func resetsOn(epoch: Int64, locale: Locale = .current, timeZone: TimeZone = .current,
                         strings: AppStrings) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.setLocalizedDateFormatFromTemplate("EEEMMMd")
        return strings.text(.cardResetsAt, ["time": formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))])
    }

    /// The right side of a window row whose reset time has passed (issue #81).
    static func expiredWindow(strings: AppStrings) -> String {
        strings.text(.cardExpired)
    }

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
                             timeZone: TimeZone = .current, strings: AppStrings) -> String {
        var note = strings.text(.cardLocalLog)
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
