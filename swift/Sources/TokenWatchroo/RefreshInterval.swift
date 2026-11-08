import Foundation

/// The refresh interval pop-up of the settings window (#104).
enum RefreshInterval {

    /// No 15 s or 30 s: short intervals made the Claude usage API answer 429 (#104 manual check).
    static let presets = [60, 120, 300, 600, 1800, 3600]

    /// The core's rule: 15 to 3600 seconds.
    static func clamped(_ seconds: Int) -> Int {
        min(3600, max(15, seconds))
    }

    /// The presets, plus the clamped current value when it is not one of them, in order.
    static func choices(current: Int) -> [Int] {
        let value = clamped(current)
        return presets.contains(value) ? presets : (presets + [value]).sorted()
    }

    static func title(_ seconds: Int, strings: AppStrings) -> String {
        if seconds % 3600 == 0 {
            let hours = seconds / 3600
            return hours == 1 ? strings.text(.intervalHourOne) : strings.text(.intervalHourOther, ["n": "\(hours)"])
        }
        if seconds % 60 == 0 {
            let minutes = seconds / 60
            return minutes == 1
                ? strings.text(.intervalMinuteOne) : strings.text(.intervalMinuteOther, ["n": "\(minutes)"])
        }
        return strings.text(.intervalSeconds, ["n": "\(seconds)"])
    }
}
