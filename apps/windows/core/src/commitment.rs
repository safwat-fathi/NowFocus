//! Port of apps/macos/NowFocusDaemon/CommitmentStore.swift's `CommitmentState`
//! (and Android's CommitmentShield). The always-blocked 14-day commitment,
//! anchored so moving the system clock can't shorten it: elapsed *awake* time
//! since creation is authoritative as long as the machine hasn't rebooted (the
//! boot identity still matches); the wall-clock `end_at` is a fallback used
//! only across a reboot, when the awake-time counter resets.
//!
//! Awake-only elapsed matches macOS (`ProcessInfo.systemUptime`). On Windows the
//! caller supplies `QueryUnbiasedInterruptTime` (awake-only — `GetTickCount64`
//! would include sleep and diverge) for `uptime_secs`, and the clock-immune
//! `BootId` (not `LastBootUpTime`, which an NTP correction can shift) for
//! `boot_id`. NTP/server-time verification is deferred, same as macOS/Android.
//!
//! Known cross-platform divergence: macOS/Windows count awake time (a 14-day
//! commitment stretches across sleep); Android's `elapsedRealtime` includes
//! deep sleep. Matching macOS is intentional here.

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};

pub const DURATION_SECS: i64 = 14 * 24 * 60 * 60;
pub const GRACE_SECS: i64 = 60;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CommitmentState {
    pub domains: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub end_at: DateTime<Utc>,
    /// Awake seconds since boot at creation (QueryUnbiasedInterruptTime).
    pub created_uptime_secs: i64,
    /// Boot identity at creation — changes only across a real reboot, never on
    /// a clock adjustment (Windows `BootId`, macOS `kern.boottime`).
    pub created_boot_id: String,
}

impl CommitmentState {
    pub fn new(
        domains: Vec<String>,
        now: DateTime<Utc>,
        uptime_secs: i64,
        boot_id: impl Into<String>,
    ) -> Self {
        Self {
            domains,
            created_at: now,
            end_at: now + Duration::seconds(DURATION_SECS),
            created_uptime_secs: uptime_secs,
            created_boot_id: boot_id.into(),
        }
    }

    /// Awake seconds elapsed since creation, or `None` when the boot identity
    /// no longer matches (a reboot happened) — the caller then falls back to
    /// wall-clock `end_at`. Refuses across a reboot rather than guess.
    fn since_creation(&self, now_uptime_secs: i64, now_boot_id: &str) -> Option<i64> {
        (now_boot_id == self.created_boot_id).then_some(now_uptime_secs - self.created_uptime_secs)
    }

    /// The only cancel window this ever gets. After it, the full 14 days run.
    pub fn can_cancel(&self, now_uptime_secs: i64, now_boot_id: &str) -> bool {
        match self.since_creation(now_uptime_secs, now_boot_id) {
            Some(elapsed) => (0..GRACE_SECS).contains(&elapsed),
            None => false,
        }
    }

    pub fn is_over(
        &self,
        wall_now: DateTime<Utc>,
        now_uptime_secs: i64,
        now_boot_id: &str,
    ) -> bool {
        match self.since_creation(now_uptime_secs, now_boot_id) {
            Some(elapsed) => elapsed >= DURATION_SECS,
            None => wall_now >= self.end_at,
        }
    }

    pub fn remaining_secs(
        &self,
        wall_now: DateTime<Utc>,
        now_uptime_secs: i64,
        now_boot_id: &str,
    ) -> i64 {
        match self.since_creation(now_uptime_secs, now_boot_id) {
            Some(elapsed) => (DURATION_SECS - elapsed).max(0),
            None => (self.end_at - wall_now).num_seconds().max(0),
        }
    }
}

/// Whether a new commitment may replace `existing`. Refuses while a commitment
/// is in effect (not over) — *including during its grace window* — so a second
/// `ApplyCommitment` can't be used to swap the real one out for a throwaway and
/// then cancel that. The user must cancel (only possible in grace) first.
/// Mirrors Android's `createCommitmentShield` guard. `None` → nothing to
/// protect, so allow.
pub fn can_replace(
    existing: Option<&CommitmentState>,
    wall_now: DateTime<Utc>,
    now_uptime_secs: i64,
    now_boot_id: &str,
) -> bool {
    match existing {
        Some(s) => s.is_over(wall_now, now_uptime_secs, now_boot_id),
        None => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Ported from apps/android/.../CommitmentShieldTest.kt.

    fn fresh() -> CommitmentState {
        CommitmentState::new(vec!["youtube.com".into()], Utc::now(), 1_000, "boot-A")
    }

    #[test]
    fn cancellable_only_inside_the_grace_window() {
        let c = fresh();
        assert!(c.can_cancel(1_000, "boot-A"), "at creation");
        assert!(c.can_cancel(1_059, "boot-A"), "just before grace end");
        assert!(!c.can_cancel(1_060, "boot-A"), "at grace end");
        assert!(!c.can_cancel(1_500, "boot-A"), "well past grace");
    }

    #[test]
    fn a_reboot_refuses_cancel_even_within_grace_seconds() {
        let c = fresh();
        // Same wall seconds elapsed, but boot id changed → can't trust uptime.
        assert!(!c.can_cancel(5, "boot-B"));
    }

    #[test]
    fn moving_the_clock_forward_cannot_shorten_it() {
        let now = Utc::now();
        let c = CommitmentState::new(vec!["x.com".into()], now, 1_000, "boot-A");
        // Clock jumped 20 days ahead, but only 100 awake-seconds really passed
        // and it's the same boot → uptime is authoritative, not over.
        let faked_now = now + Duration::days(20);
        assert!(!c.is_over(faked_now, 1_100, "boot-A"));
        assert!(c.remaining_secs(faked_now, 1_100, "boot-A") > 0);
    }

    #[test]
    fn expires_by_uptime_when_boot_matches() {
        let c = fresh();
        assert!(c.is_over(Utc::now(), 1_000 + DURATION_SECS, "boot-A"));
    }

    #[test]
    fn across_a_reboot_it_falls_back_to_wall_clock_end_at() {
        let now = Utc::now();
        let c = CommitmentState::new(vec!["x.com".into()], now, 1_000, "boot-A");
        // Rebooted (boot-B): before end_at not over, after end_at over.
        assert!(!c.is_over(now + Duration::days(1), 50, "boot-B"));
        assert!(c.is_over(now + Duration::days(15), 50, "boot-B"));
    }

    #[test]
    fn a_live_commitment_cannot_be_replaced_even_during_grace() {
        let c = fresh(); // created at uptime 1_000, boot-A
        let now = Utc::now();
        // In grace (uptime 1_010) — still not replaceable.
        assert!(!can_replace(Some(&c), now, 1_010, "boot-A"));
        // Well past grace, still within 14 days — not replaceable.
        assert!(!can_replace(Some(&c), now, 5_000, "boot-A"));
        // Over (uptime past duration) — replaceable.
        assert!(can_replace(Some(&c), now, 1_000 + DURATION_SECS, "boot-A"));
        // Nothing in effect — replaceable.
        assert!(can_replace(None, now, 1_000, "boot-A"));
    }
}
