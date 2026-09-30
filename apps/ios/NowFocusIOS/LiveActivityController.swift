import ActivityKit
import Foundation
import NowFocusCore

/// Starts/ends the session Live Activity. Live Activities don't end at `endAt` on their own, so
/// `AppModel.refresh` calls `sync` whenever the app runs and ends any that outlived their session.
@MainActor
enum LiveActivityController {
    static func sync(session: FocusSession?, policyName: String, now: Date) {
        let running = session.map { SessionEngine().isActive($0, currentTime: now) } ?? false
        let existing = Activity<SessionActivityAttributes>.activities
        guard running, let session else {
            for activity in existing { Task { await activity.end(nil, dismissalPolicy: .immediate) } }
            return
        }
        guard existing.isEmpty, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = SessionActivityAttributes.ContentState(endAt: session.endAt, mode: session.enforcementMode.rawValue)
        _ = try? Activity.request(
            attributes: SessionActivityAttributes(policyName: policyName, startAt: session.startAt),
            content: .init(state: state, staleDate: session.endAt),
            pushType: nil
        )
    }
}
