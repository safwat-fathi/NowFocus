import Foundation
import NowFocusCore

/// Single place that starts/stops enforcement (daemon + app blocker) so they
/// never drift out of sync with the session stored in the database.
///
/// Before this existed, "clear enforcement" was hand-rolled at three call
/// sites and one of them (MenuBarView.refreshState) forgot to actually clear
/// it, leaving the /etc/hosts block and the overlay stuck on indefinitely.
enum SessionController {
    static func startEnforcement(policy: BlockPolicy) {
        DaemonClient.shared.apply(policy: policy)
        AppBlocker.shared.updatePolicy(
            isSessionActive: true,
            blockedApps: policy.applications.filter { $0.enabled }.map { $0.nativeIdentifier }
        )
    }

    static func stopEnforcement() {
        DaemonClient.shared.clear()
        AppBlocker.shared.updatePolicy(isSessionActive: false, blockedApps: [])
    }

    /// Marks `session` completed, persists it, and tears down enforcement.
    /// Idempotent — safe to call even if enforcement was never applied.
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
