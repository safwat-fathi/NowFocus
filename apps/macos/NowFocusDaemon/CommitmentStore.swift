import Foundation
import NowFocusCore

/// Reads `kern.boottime` — the one signal that changes only across an actual
/// reboot, not a mere clock adjustment. Comparing this at read-time against
/// the value stamped at creation is how `CommitmentState` tells "the clock
/// moved" apart from "the machine restarted" (`ProcessInfo.systemUptime`
/// alone resets on both, and can't tell them apart).
func systemBootTime() -> Date {
    var bootTime = timeval()
    var size = MemoryLayout<timeval>.size
    let result = sysctlbyname("kern.boottime", &bootTime, &size, nil, 0)
    guard result == 0 else { return Date(timeIntervalSince1970: 0) }
    return Date(timeIntervalSince1970: TimeInterval(bootTime.tv_sec) + TimeInterval(bootTime.tv_usec) / 1_000_000)
}

/// Persisted, daemon-owned state for the Always-Blocked Commitment Shield.
/// Mirrors Android's `CommitmentShield` boot-relative approach: elapsed time
/// since creation (`ProcessInfo.systemUptime`) is authoritative as long as the
/// machine hasn't rebooted since (`createdBootTime` still matches); wall-clock
/// `endAt` is a fallback used only across a reboot, when elapsed time resets.
/// NTP/server-time verification is a spec item Android's own version also
/// skipped — deferred here for the same reason: not needed to prove the lock
/// itself holds.
struct CommitmentState: Codable {
    let domains: [String]
    let createdAt: Date
    let endAt: Date
    let createdSystemUptime: TimeInterval
    let createdBootTime: Date

    static let duration: TimeInterval = 14 * 24 * 60 * 60
    static let grace: TimeInterval = 60

    init(domains: [String], now: Date = Date(), uptime: TimeInterval = ProcessInfo.processInfo.systemUptime, bootTime: Date = systemBootTime()) {
        self.domains = domains
        self.createdAt = now
        self.endAt = now.addingTimeInterval(Self.duration)
        self.createdSystemUptime = uptime
        self.createdBootTime = bootTime
    }

    private func sinceCreation(nowUptime: TimeInterval, nowBootTime: Date) -> TimeInterval? {
        // Boot-time comparisons are only meaningful to whole-second precision
        // (sysctl's own resolution); require an exact match, not "close enough".
        guard nowBootTime == createdBootTime else { return nil }
        return nowUptime - createdSystemUptime
    }

    /// The only cancel window this ever gets — after this, it runs the full 14
    /// days. Refuses across a reboot rather than guess.
    func canCancel(nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime, nowBootTime: Date = systemBootTime()) -> Bool {
        guard let elapsed = sinceCreation(nowUptime: nowUptime, nowBootTime: nowBootTime) else { return false }
        return elapsed >= 0 && elapsed < Self.grace
    }

    func isOver(wallNow: Date = Date(), nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime, nowBootTime: Date = systemBootTime()) -> Bool {
        if let elapsed = sinceCreation(nowUptime: nowUptime, nowBootTime: nowBootTime) {
            return elapsed >= Self.duration
        }
        return wallNow >= endAt
    }

    func remainingSeconds(wallNow: Date = Date(), nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime, nowBootTime: Date = systemBootTime()) -> TimeInterval {
        if let elapsed = sinceCreation(nowUptime: nowUptime, nowBootTime: nowBootTime) {
            return max(0, Self.duration - elapsed)
        }
        return max(0, endAt.timeIntervalSince(wallNow))
    }
}

/// The daemon's own persistence for `CommitmentState` — deliberately separate
/// from the app's SQLite database, which the daemon must never open (see
/// native_tech_stack_spec.md's privileged-helper boundary).
final class CommitmentStore {
    private let stateURL: URL

    private(set) var current: CommitmentState?

    init(directory: URL = URL(fileURLWithPath: "/Library/Application Support/NowFocus")) {
        stateURL = directory.appendingPathComponent("commitment_state.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: stateURL) else { return }
        current = try? JSONDecoder().decode(CommitmentState.self, from: data)
    }

    private func persist() {
        guard let current else {
            try? FileManager.default.removeItem(at: stateURL)
            return
        }
        guard let data = try? JSONEncoder().encode(current) else { return }
        try? data.write(to: stateURL, options: .atomic)
    }

    /// Starts a freshly time-stamped commitment — but refuses (returns false)
    /// while one is already in effect, *including its grace window*. Without
    /// this guard a second `applyCommitment` could swap the real commitment out
    /// for a throwaway and then cancel that during grace, defeating the lock;
    /// the XPC boundary accepts all connections (see DaemonXPCDelegate), so the
    /// guard has to live here, not in the caller. The user must cancel first
    /// (only possible in grace). Mirrors Android's createCommitmentShield and
    /// the Windows commitment_store.
    @discardableResult
    func apply(domains: [String]) -> Bool {
        if let current, !current.isOver() { return false }
        current = CommitmentState(domains: domains)
        persist()
        return true
    }

    /// Refuses (returns false) once the grace window has passed — independent
    /// of whether the app that created the commitment is even still running.
    @discardableResult
    func clearIfInGrace() -> Bool {
        guard let current, current.canCancel() else { return false }
        self.current = nil
        persist()
        return true
    }

    /// Called once at daemon startup: an expired commitment (e.g. the 14 days
    /// elapsed while the daemon wasn't running) is cleared rather than
    /// silently kept.
    func expireIfNeeded() {
        guard let current, current.isOver() else { return }
        self.current = nil
        persist()
    }

    func status() -> CommitmentStatusDTO? {
        guard let current else { return nil }
        return CommitmentStatusDTO(
            domains: current.domains,
            endAt: current.endAt,
            canCancelNow: current.canCancel(),
            remainingSeconds: current.remainingSeconds()
        )
    }
}
