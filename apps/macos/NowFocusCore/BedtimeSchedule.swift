import Foundation

/// Ships with 2 of the mockup's 4 toggles. "Close the feeds" and
/// quiet-notifications/DND are dropped: macOS has no public API for a regular
/// (non-MDM, non-Screen-Time) app to toggle Focus/DND, and feed-level blocking
/// isn't visible to hosts-file blocking or foreground-app detection either —
/// same reasoning Android's BedtimeSchedule used to drop its other ones.
/// `lockAtSleep` survives because macOS *does* have a real, permission-free
/// mechanism for it — see BedtimeScheduler. `greyscale` has no public API
/// either; BedtimeScheduler flips it through the private UniversalAccess
/// framework (DMG build only) and does nothing if that isn't available.
public struct BedtimeSettings: Codable, Equatable {
    public var enabled: Bool = false
    public var windDownMinute: Int = 22 * 60
    public var sleepMinute: Int = 23 * 60
    public var wakeMinute: Int = 7 * 60
    public var lockAtSleep: Bool = true
    public var greyscale: Bool = false
    public var policyId: String?

    public init() {}

    // Hand-written so settings saved before `greyscale` existed still decode (it reads as off).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? enabled
        windDownMinute = try c.decodeIfPresent(Int.self, forKey: .windDownMinute) ?? windDownMinute
        sleepMinute = try c.decodeIfPresent(Int.self, forKey: .sleepMinute) ?? sleepMinute
        wakeMinute = try c.decodeIfPresent(Int.self, forKey: .wakeMinute) ?? wakeMinute
        lockAtSleep = try c.decodeIfPresent(Bool.self, forKey: .lockAtSleep) ?? lockAtSleep
        greyscale = try c.decodeIfPresent(Bool.self, forKey: .greyscale) ?? greyscale
        policyId = try c.decodeIfPresent(String.self, forKey: .policyId)
    }
}

public enum GreyscaleDecision: Equatable { case apply, restore, none }

/// Pure scheduling math — no side effects, so it's plain-Swift testable.
/// Mirrors Android's `BedtimeSchedule`. Windows frequently cross midnight
/// (wind-down 22:00 -> wake 07:00), so every boundary is resolved to an
/// absolute `Date` before comparison, matching native_tech_stack_spec.md's
/// explicit note on why minutes-since-midnight comparisons alone break across
/// the date line.
public enum BedtimeSchedule {

    /// Wind-down-to-wake window starting on `referenceDate`'s calendar day.
    public static func windowFor(_ settings: BedtimeSettings, referenceDate: Date, calendar: Calendar) -> (start: Date, end: Date) {
        let day = calendar.startOfDay(for: referenceDate)
        let start = calendar.date(bySettingHour: settings.windDownMinute / 60, minute: settings.windDownMinute % 60, second: 0, of: day) ?? day
        var end = calendar.date(bySettingHour: settings.wakeMinute / 60, minute: settings.wakeMinute % 60, second: 0, of: day) ?? day
        if settings.wakeMinute <= settings.windDownMinute {
            end = calendar.date(byAdding: .day, value: 1, to: end) ?? end
        }
        return (start, end)
    }

    /// The window containing `now`, checking both today's and yesterday's —
    /// just after midnight, "tonight's window" is actually *yesterday's*
    /// `windowFor` result, not today's. Missing this case is the exact bug
    /// Android's own `nextBoundary` comment documents: a relaunch just after
    /// midnight would otherwise miss the still-running window entirely.
    public static func currentWindow(_ settings: BedtimeSettings, now: Date, calendar: Calendar) -> (start: Date, end: Date)? {
        let yesterday = calendar.date(byAdding: .day, value: -1, to: now) ?? now
        for referenceDate in [yesterday, now] {
            let window = windowFor(settings, referenceDate: referenceDate, calendar: calendar)
            if now >= window.start && now < window.end {
                return window
            }
        }
        return nil
    }

    /// Greyscale while Bedtime is on, the toggle is on and `now` is inside the
    /// window. Restores only when `applied` says *we* turned it on, so a
    /// greyscale the user set themselves is never switched off by us.
    public static func decideGreyscale(_ settings: BedtimeSettings, now: Date, calendar: Calendar, applied: Bool) -> GreyscaleDecision {
        let should = settings.enabled && settings.greyscale && currentWindow(settings, now: now, calendar: calendar) != nil
        if should && !applied { return .apply }
        if !should && applied { return .restore }
        return .none
    }

    /// True only within a couple of minutes after the sleep moment inside
    /// `window` — a one-shot trigger, not "anytime during the window",
    /// so a relaunch at 23:30 doesn't immediately fire a 23:00 lock.
    public static func isAtSleepMoment(_ settings: BedtimeSettings, now: Date, window: (start: Date, end: Date), calendar: Calendar) -> Bool {
        let day = calendar.startOfDay(for: window.start)
        guard var sleepTime = calendar.date(bySettingHour: settings.sleepMinute / 60, minute: settings.sleepMinute % 60, second: 0, of: day) else {
            return false
        }
        if sleepTime < window.start {
            sleepTime = calendar.date(byAdding: .day, value: 1, to: sleepTime) ?? sleepTime
        }
        return now >= sleepTime && now.timeIntervalSince(sleepTime) < 120
    }

    #if DEBUG
    /// Smallest possible check for the midnight-crossing math above.
    public static func runSelfCheck() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        var settings = BedtimeSettings()
        settings.windDownMinute = 22 * 60   // 22:00
        settings.sleepMinute = 23 * 60       // 23:00
        settings.wakeMinute = 7 * 60         // 07:00

        let day = calendar.date(from: DateComponents(year: 2026, month: 1, day: 10))!

        // 23:30 on day 1: inside *today's* window (22:00 day1 -> 07:00 day2).
        let lateEvening = calendar.date(byAdding: .minute, value: 23 * 60 + 30, to: day)!
        let w1 = currentWindow(settings, now: lateEvening, calendar: calendar)
        assert(w1 != nil, "23:30 must fall inside the wind-down window")

        // 01:00 on day 2: inside *yesterday's* window, not "no window" or "today's".
        let afterMidnight = calendar.date(byAdding: .minute, value: 24 * 60 + 60, to: day)!
        let w2 = currentWindow(settings, now: afterMidnight, calendar: calendar)
        assert(w2 != nil, "01:00 must still fall inside last night's window")
        assert(w2!.start < afterMidnight, "the matched window must have started before now")

        // 12:00 noon: outside any window.
        let noon = calendar.date(byAdding: .minute, value: 12 * 60, to: day)!
        assert(currentWindow(settings, now: noon, calendar: calendar) == nil, "midday must not match any window")

        // Sleep moment: true right at 23:00 and shortly after, false well past it or before it.
        guard let window = w1 else { fatalError("w1 must be non-nil for this assertion") }
        let atSleep = calendar.date(byAdding: .minute, value: 23 * 60, to: day)!
        assert(isAtSleepMoment(settings, now: atSleep, window: window, calendar: calendar))
        let wellPast = calendar.date(byAdding: .minute, value: 23 * 60 + 10, to: day)!
        assert(!isAtSleepMoment(settings, now: wellPast, window: window, calendar: calendar))

        print("BedtimeSchedule self-check passed")
    }
    #endif
}
