import Foundation

/// Locks chosen sites/apps for 14 days with no exit, except a 60-second grace to cancel. Port of
/// Android's `CommitmentShield`. Pure: callers pass `now` (wall clock) and `uptime` (a monotonic
/// clock that keeps counting through sleep, see `CommitmentShield.uptimeNow`) so it is checkable.
///
/// Elapsed time comes from the monotonic clock, so moving the phone's date forward can't end the
/// lock. A reboot resets that clock (`uptime < createdUptime`), and then it falls back to the wall
/// clock and refuses cancel, same as Android's boot-count fallback.
/// ponytail: clock changes combined with a reboot can still shorten it; add trusted/NTP time later.
public struct CommitmentShield: Codable {
    public static let duration: TimeInterval = 14 * 24 * 60 * 60
    public static let grace: TimeInterval = 60

    public let startAt: Date
    public let endAt: Date
    public let domains: [String]
    public let apps: [ApplicationRule]
    public let createdUptime: TimeInterval

    public init(startAt: Date, domains: [String], apps: [ApplicationRule] = [], createdUptime: TimeInterval) {
        self.startAt = startAt
        self.endAt = startAt.addingTimeInterval(Self.duration)
        self.domains = domains
        self.apps = apps
        self.createdUptime = createdUptime
    }

    /// Seconds since boot on a clock that includes time asleep and ignores date changes.
    public static func uptimeNow() -> TimeInterval {
        TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) / 1_000_000_000
    }

    /// Nil after a reboot (the monotonic clock went backwards relative to creation).
    private func monotonicElapsed(uptime: TimeInterval) -> TimeInterval? {
        uptime >= createdUptime ? uptime - createdUptime : nil
    }

    public func isOver(now: Date, uptime: TimeInterval) -> Bool {
        if let elapsed = monotonicElapsed(uptime: uptime) { return elapsed >= Self.duration }
        return now >= endAt
    }

    /// Only within the grace period, and never once a reboot makes the elapsed time unknowable.
    public func canCancel(now: Date, uptime: TimeInterval) -> Bool {
        guard let elapsed = monotonicElapsed(uptime: uptime) else { return false }
        return elapsed < Self.grace && now.timeIntervalSince(startAt) < Self.grace
    }

    /// Seconds left of the lock, for "N days to go".
    public func remaining(now: Date, uptime: TimeInterval) -> TimeInterval {
        if let elapsed = monotonicElapsed(uptime: uptime) { return max(0, Self.duration - elapsed) }
        return max(0, endAt.timeIntervalSince(now))
    }
}
