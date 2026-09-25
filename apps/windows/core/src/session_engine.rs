use chrono::{DateTime, Utc};

use crate::focus_session::{FocusSession, FocusSessionStatus};

/// Port of apps/macos/NowFocusCore/SessionEngine.swift (mirrored again in
/// Android's SessionEngine.kt). Exactly these two functions: `end_at` is the
/// source of truth for whether a session is active, never a UI countdown,
/// and every recovery path recomputes state from persisted timestamps plus
/// the current time. Don't add more to this — the daemon-side IPC protocol
/// isn't session-aware, so anything beyond "is it active right now" belongs
/// in the caller, not here.
pub fn evaluate_state(session: &mut FocusSession, current_time: DateTime<Utc>) {
    match session.status {
        FocusSessionStatus::Scheduled => {
            if current_time >= session.start_at && current_time < session.end_at {
                session.status = FocusSessionStatus::Active;
            } else if current_time >= session.end_at {
                session.status = FocusSessionStatus::Expired;
            }
        }
        FocusSessionStatus::Active if current_time >= session.end_at => {
            session.status = FocusSessionStatus::Completed;
        }
        _ => {}
    }
}

pub fn is_active(session: &FocusSession, current_time: DateTime<Utc>) -> bool {
    session.status == FocusSessionStatus::Active
        && current_time >= session.start_at
        && current_time < session.end_at
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    // Ported from apps/macos/test_session_engine.swift (git commit a69d2bd).

    #[test]
    fn active_session_past_end_at_becomes_completed() {
        let now = Utc::now();
        let mut expired = FocusSession::new(
            "p1",
            now - Duration::seconds(3600),
            now - Duration::seconds(1),
            "test",
        );
        expired.status = FocusSessionStatus::Active;

        evaluate_state(&mut expired, now);

        assert_eq!(
            expired.status,
            FocusSessionStatus::Completed,
            "active session past end_at should become completed"
        );
        assert!(
            !is_active(&expired, now),
            "expired session should not be reported active"
        );
    }

    #[test]
    fn active_session_within_window_stays_active() {
        let now = Utc::now();
        let mut still_active = FocusSession::new(
            "p1",
            now - Duration::seconds(60),
            now + Duration::seconds(60),
            "test",
        );
        still_active.status = FocusSessionStatus::Active;

        evaluate_state(&mut still_active, now);

        assert_eq!(
            still_active.status,
            FocusSessionStatus::Active,
            "session within window should remain active"
        );
        assert!(
            is_active(&still_active, now),
            "session within window should be reported active"
        );
    }

    #[test]
    fn scheduled_session_activates_once_start_at_arrives() {
        let now = Utc::now();
        let mut scheduled = FocusSession::new(
            "p1",
            now - Duration::seconds(1),
            now + Duration::seconds(60),
            "test",
        );
        scheduled.status = FocusSessionStatus::Scheduled;

        evaluate_state(&mut scheduled, now);

        assert_eq!(scheduled.status, FocusSessionStatus::Active);
    }

    #[test]
    fn scheduled_session_already_past_end_at_expires_without_ever_running() {
        let now = Utc::now();
        let mut scheduled = FocusSession::new(
            "p1",
            now - Duration::seconds(120),
            now - Duration::seconds(60),
            "test",
        );
        scheduled.status = FocusSessionStatus::Scheduled;

        evaluate_state(&mut scheduled, now);

        assert_eq!(scheduled.status, FocusSessionStatus::Expired);
    }
}
