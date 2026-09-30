import Foundation

extension FocusSession {
    /// Real focused time: full duration if it ran to completion, elapsed-so-far
    /// if cancelled, 0 otherwise. Mirrors Android's `SessionHistoryRow.focusedMillis`.
    public var focusedDuration: TimeInterval {
        if status == .completed { return endAt.timeIntervalSince(startAt) }
        if let cancelledAt { return cancelledAt.timeIntervalSince(startAt) }
        return 0
    }
}

/// The local hour of day with the most blocked attempts, and the app tried most in that hour.
public struct Urge: Equatable {
    public let hour: Int
    public let count: Int
    public let topApp: String
}

/// Pure aggregation over already-fetched rows — no GRDB/Context involved, so
/// it's plain-Swift testable. Mirrors Android's `HistoryStats` object.
public enum HistoryStats {

    public static func totalFocusedSeconds(_ sessions: [FocusSession]) -> TimeInterval {
        sessions.reduce(0) { $0 + $1.focusedDuration }
    }

    public static func sessionsCount(_ sessions: [FocusSession]) -> Int { sessions.count }

    public static func completedCount(_ sessions: [FocusSession]) -> Int {
        sessions.filter { $0.status == .completed }.count
    }

    public static func completionRate(_ sessions: [FocusSession]) -> Double {
        let total = sessions.count
        return total == 0 ? 0 : Double(completedCount(sessions)) / Double(total)
    }

    /// Monday-first week regardless of locale/region settings, matching
    /// Android's explicit `DayOfWeek.MONDAY` choice.
    public static func mondayFirstCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.firstWeekday = 2
        return calendar
    }

    /// Minutes focused per day, Monday..Sunday, for the week containing `anyDayInWeek`.
    public static func weekBucketsMinutes(_ sessions: [FocusSession], anyDayInWeek: Date, calendar: Calendar) -> [Double] {
        guard let week = calendar.dateInterval(of: .weekOfYear, for: anyDayInWeek) else {
            return Array(repeating: 0, count: 7)
        }
        return (0..<7).map { offset -> Double in
            guard let dayStart = calendar.date(byAdding: .day, value: offset, to: week.start),
                  let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart) else { return 0 }
            let seconds = sessions
                .filter { $0.startAt >= dayStart && $0.startAt < dayEnd }
                .reduce(0.0) { $0 + $1.focusedDuration }
            return seconds / 60
        }
    }

    /// Consecutive days with at least one real (non-zero-duration) session,
    /// walking back from the most recent active day. One day of grace: if the
    /// most recent session was yesterday, the streak still counts — it
    /// shouldn't zero out the moment a new day starts, before today's session.
    public static func currentStreakDays(_ sessions: [FocusSession], today: Date, calendar: Calendar) -> Int {
        let activeDays = Set(
            sessions.filter { $0.focusedDuration > 0 }.map { calendar.startOfDay(for: $0.startAt) }
        )
        guard let mostRecent = activeDays.max() else { return 0 }
        let todayStart = calendar.startOfDay(for: today)
        guard let yesterday = calendar.date(byAdding: .day, value: -1, to: todayStart), mostRecent >= yesterday else {
            return 0
        }

        var streak = 0
        var day = mostRecent
        while activeDays.contains(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    /// `sessionEvent` rows of type "app_blocked" carry `{"bundleId","name"}` in
    /// `metadataJson` (set by `AppBlocker` when it shows the overlay).
    public static func topBlockedApps(_ events: [SessionEvent], limit: Int = 3) -> [(name: String, count: Int)] {
        var counts: [String: Int] = [:]
        var displayNames: [String: String] = [:]
        for event in events where event.type == "app_blocked" {
            guard let data = event.metadataJson?.data(using: .utf8),
                  let meta = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                  let bundleId = meta["bundleId"] else { continue }
            counts[bundleId, default: 0] += 1
            if let name = meta["name"] { displayNames[bundleId] = name }
        }
        return counts
            .sorted { $0.value > $1.value }
            .prefix(limit)
            .map { (displayNames[$0.key] ?? $0.key, $0.value) }
    }

    /// Every blocked-app attempt, across all apps (`topBlockedApps` is limited to a top list, so
    /// summing it undercounts).
    public static func turnedAwayCount(_ events: [SessionEvent]) -> Int {
        events.filter { $0.type == "app_blocked" }.count
    }

    /// Blocked-app attempts per local hour of day: always 24 entries, index = hour (0-23).
    /// Only app attempts are known: hosts-file blocks can't be observed.
    public static func urgeByHour(_ events: [SessionEvent], calendar: Calendar) -> [Int] {
        var counts = [Int](repeating: 0, count: 24)
        for event in events where event.type == "app_blocked" {
            counts[calendar.component(.hour, from: event.occurredAt)] += 1
        }
        return counts
    }

    /// The busiest hour (ties go to the earlier hour) and the app tried most in it, or nil with no attempts.
    public static func peakUrge(_ events: [SessionEvent], calendar: Calendar) -> Urge? {
        let counts = urgeByHour(events, calendar: calendar)
        guard let count = counts.max(), count > 0, let hour = counts.firstIndex(of: count) else { return nil }
        var perApp: [String: (name: String, count: Int)] = [:]
        for event in events where event.type == "app_blocked" && calendar.component(.hour, from: event.occurredAt) == hour {
            guard let data = event.metadataJson?.data(using: .utf8),
                  let meta = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                  let bundleId = meta["bundleId"] else { continue }
            perApp[bundleId] = (meta["name"] ?? bundleId, (perApp[bundleId]?.count ?? 0) + 1)
        }
        let top = perApp.values.max { $0.count < $1.count }?.name ?? "a blocked app"
        return Urge(hour: hour, count: count, topApp: top)
    }

    #if DEBUG
    /// Smallest possible check for the non-obvious logic above (streak grace
    /// period, week bucketing, Monday-first). Called once from
    /// `AppDelegate.applicationDidFinishLaunching` in debug builds.
    public static func runSelfCheck() {
        let calendar = mondayFirstCalendar()
        let now = Date()
        let today = calendar.startOfDay(for: now)

        func session(daysAgo: Int, minutes: Int, status: FocusSessionStatus = .completed) -> FocusSession {
            let start = calendar.date(byAdding: .day, value: -daysAgo, to: today)!
            return FocusSession(
                policyId: "p", startAt: start, endAt: start.addingTimeInterval(TimeInterval(minutes * 60)),
                status: status, deviceId: "test"
            )
        }

        // Streak: sessions today and yesterday and 2 days ago => 3; a gap at
        // 2 days ago breaks it.
        assert(currentStreakDays([session(daysAgo: 0, minutes: 10), session(daysAgo: 1, minutes: 10), session(daysAgo: 2, minutes: 10)], today: now, calendar: calendar) == 3)
        assert(currentStreakDays([session(daysAgo: 1, minutes: 10), session(daysAgo: 3, minutes: 10)], today: now, calendar: calendar) == 1)
        assert(currentStreakDays([session(daysAgo: 2, minutes: 10)], today: now, calendar: calendar) == 0)
        assert(currentStreakDays([], today: now, calendar: calendar) == 0)

        // A cancelled-with-no-elapsed-time session doesn't count as an active day.
        let neverRan = FocusSession(policyId: "p", startAt: today, endAt: today.addingTimeInterval(3600), status: .cancelled, deviceId: "test")
        assert(currentStreakDays([neverRan], today: now, calendar: calendar) == 0)

        // Week buckets: a 30-minute session today lands in exactly one bucket
        // and the other six are empty.
        let buckets = weekBucketsMinutes([session(daysAgo: 0, minutes: 30)], anyDayInWeek: now, calendar: calendar)
        assert(buckets.count == 7)
        assert(buckets.filter { $0 > 0 }.count == 1)
        assert(abs(buckets.reduce(0, +) - 30) < 0.01)

        print("HistoryStats self-check passed")
    }
    #endif
}
