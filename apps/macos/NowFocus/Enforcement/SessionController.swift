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
    static func startEnforcement(policy: BlockPolicy) {
        DaemonClient.shared.apply(policy: policy)
        AppBlocker.shared.updatePolicy(
            isSessionActive: true,
            blockedApps: policy.applications.filter { $0.enabled }.map { $0.nativeIdentifier }
        )
        status.isActive = true
    }

    @MainActor
    static func stopEnforcement() {
        DaemonClient.shared.clear()
        AppBlocker.shared.updatePolicy(isSessionActive: false, blockedApps: [])
        status.isActive = false
    }

    /// Marks `session` completed, persists it, and tears down enforcement.
    /// Idempotent — safe to call even if enforcement was never applied.
    @MainActor
    @discardableResult
    static func endSession(_ session: FocusSession) -> FocusSession {
        var ended = session
        if ended.status == .active || ended.status == .scheduled {
            ended.status = .completed
        }
        ended.completedAt = Date()

        do {
            try DatabaseManager.shared.saveSession(ended)
        } catch {
            print("Failed to persist ended session: \(error)")
        }

        stopEnforcement()
        return ended
    }
}
