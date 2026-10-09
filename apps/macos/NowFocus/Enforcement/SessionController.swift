import Foundation
import NowFocusCore

/// Live enforcement state, for anything that needs to react to a session
/// starting/ending without polling the database itself (currently: the menu
/// bar icon). `SessionController` is the single place enforcement actually
/// starts/stops, so it's also the single place this gets updated — nothing
/// else should assign to `isActive` directly.
@MainActor
@Observable
final class SessionStatus {
    var isActive: Bool = false
    /// When the running session ends, for the menu bar countdown.
    var endAt: Date?
    /// Advanced once a second while a session runs; `remainingText` reads it, so the menu bar label updates.
    private(set) var now = Date()
    @ObservationIgnored private var ticker: Timer?

    /// "24:35" until the session ends. Driven by our own tick: SwiftUI's self-updating `Text(timerInterval:)` inside
    /// the MenuBarExtra label pinned the main thread at ~100% CPU (memory climbing) as soon as a session started.
    var remainingText: String { Countdown.text(remaining: (endAt ?? now).timeIntervalSince(now)) }

    func startTicking() {
        guard ticker == nil else { return }
        now = Date()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.now = Date() }
        }
    }

    func stopTicking() {
        ticker?.invalidate()
        ticker = nil
    }
}

/// Single place that starts/stops enforcement (daemon + app blocker) so they
/// never drift out of sync with the session stored in the database.
///
/// Before this existed, "clear enforcement" was hand-rolled at three call
/// sites and one of them (MenuBarView.refreshState) forgot to actually clear
/// it, leaving the /etc/hosts block and the overlay stuck on indefinitely.
enum SessionController {
    @MainActor
    static let status = SessionStatus()

    @MainActor
    static func startEnforcement(policy: BlockPolicy, sessionId: String, endAt: Date? = nil, sessionType: SessionType = .focus) {
        DaemonClient.shared.apply(policy: policy)
        AppBlocker.shared.updatePolicy(
            sessionId: sessionId,
            isSessionActive: true,
            blockedApps: policy.applications.filter { $0.enabled }.map { $0.nativeIdentifier },
            endAt: endAt,
            sessionType: sessionType
        )
        BrowserGuard.shared.update(sessionId: sessionId, rules: policy.partial)
        status.isActive = true
        status.endAt = endAt
        status.startTicking()
    }

    @MainActor
    static func stopEnforcement() {
        DaemonClient.shared.clear()
        AppBlocker.shared.updatePolicy(sessionId: nil, isSessionActive: false, blockedApps: [])
        BrowserGuard.shared.update(sessionId: nil, rules: [])
        status.isActive = false
        status.endAt = nil
        status.stopTicking()
    }

    /// Marks `session` completed (ran its course) or cancelled (stopped
    /// early), persists it, and tears down enforcement. Idempotent — safe to
    /// call even if enforcement was never applied. `cancelled` matters beyond
    /// bookkeeping: `FocusSession.focusedDuration` (Stats) only credits full
    /// duration to `.completed` sessions — every other status counts elapsed
    /// time via `cancelledAt`, so a real early-stop that stayed `.completed`
    /// would silently inflate focused-time and completion-rate.
    @MainActor
    @discardableResult
    static func endSession(_ session: FocusSession, cancelled: Bool = false) -> FocusSession {
        var ended = session
        if ended.status == .active || ended.status == .scheduled {
            ended.status = cancelled ? .cancelled : .completed
        }
        if cancelled {
            ended.cancelledAt = Date()
        } else {
            ended.completedAt = Date()
        }

        do {
            try DatabaseManager.shared.saveSession(ended)
        } catch {
            print("Failed to persist ended session: \(error)")
        }

        stopEnforcement()
        return ended
    }
}
