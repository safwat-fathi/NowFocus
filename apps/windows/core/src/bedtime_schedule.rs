//! Port of apps/android/.../BedtimeSchedule.kt (and macOS BedtimeSchedule.swift).
//! Pure wall-clock scheduling math over local naive datetimes — no timezone
//! object, no OS calls — so it tests on any host. The caller converts
//! `Local::now()` to a `NaiveDateTime` and maps returned window boundaries back
//! to `Utc` for session timestamps. Bedtime windows routinely cross midnight,
//! so every boundary is a full datetime, never "minutes since midnight" (which
//! breaks across the date line — the bug the Android tests exist to catch).

use chrono::{Duration, NaiveDate, NaiveDateTime};
use serde::{Deserialize, Serialize};

/// Ships lock-at-sleep only, like macOS's 1-of-4 reduction. DND/greyscale have
/// no clean unprivileged Windows API. `policy_id` selects the block profile the
/// nightly locked session enforces.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BedtimeSettings {
    pub enabled: bool,
    /// Minutes since local midnight.
    pub wind_down_minute: i64,
    pub sleep_minute: i64,
    pub wake_minute: i64,
    pub lock_at_sleep: bool,
    pub policy_id: Option<String>,
}

impl Default for BedtimeSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            wind_down_minute: 22 * 60,
            sleep_minute: 23 * 60,
            wake_minute: 7 * 60,
            lock_at_sleep: true,
            policy_id: None,
        }
    }
}

fn at(date: NaiveDate, minute: i64) -> NaiveDateTime {
    date.and_hms_opt(0, 0, 0).unwrap() + Duration::minutes(minute)
}

/// Wind-down→wake window anchored on `date`. Ends next day when wake <= wind-down.
pub fn window_for(s: &BedtimeSettings, date: NaiveDate) -> (NaiveDateTime, NaiveDateTime) {
    let start = at(date, s.wind_down_minute);
    let mut end = at(date, s.wake_minute);
    if s.wake_minute <= s.wind_down_minute {
        end += Duration::days(1);
    }
    (start, end)
}

/// The window containing `now` — tonight's (already started) or last night's
/// (still running past midnight), else None. Mirrors isQuietTimeNow's
/// yesterday+today check so a window that crossed midnight is still found.
pub fn current_window(
    s: &BedtimeSettings,
    now: NaiveDateTime,
) -> Option<(NaiveDateTime, NaiveDateTime)> {
    if !s.enabled {
        return None;
    }
    let today = now.date();
    for day in [today.pred_opt().unwrap_or(today), today] {
        let w = window_for(s, day);
        if now >= w.0 && now < w.1 {
            return Some(w);
        }
    }
    None
}

/// True when `now` is within `band` of the sleep moment inside the current
/// window. The 30s tick calls this each cycle; the caller debounces repeat
/// locks (macOS uses a 120s guard).
pub fn is_at_sleep_moment(s: &BedtimeSettings, now: NaiveDateTime, band: Duration) -> bool {
    let Some((start, end)) = current_window(s, now) else {
        return false;
    };
    // The sleep minute belongs to the window's start date, bumped a day when it
    // falls before wind-down (i.e. it's an after-midnight time) — same
    // day-crossing rule as wake.
    let mut sleep = at(start.date(), s.sleep_minute);
    if s.sleep_minute < s.wind_down_minute {
        sleep += Duration::days(1);
    }
    sleep >= start && sleep < end && (now - sleep).num_seconds().abs() <= band.num_seconds()
}

/// What the 30s tick should do this cycle. Pure so it's tested here; the caller
/// (src-tauri `AppState::bedtime_tick`) does the impure parts — verifying the
/// policy still exists, starting the locked session, and calling
/// `LockWorkStation`. Mirrors macOS `BedtimeScheduler.tick`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TickDecision {
    /// The window to start a `LOCKED` bedtime session for, or `None`. `Some`
    /// only when enabled, a profile is chosen, we're inside the window, and
    /// nothing else is already active (a second session would let either
    /// one's end clear the other's enforcement — macOS's guard).
    pub start_window: Option<(NaiveDateTime, NaiveDateTime)>,
    /// Whether to lock the screen right now (at the sleep moment, debounced).
    pub lock_now: bool,
}

pub fn tick_decision(
    s: &BedtimeSettings,
    now_local: NaiveDateTime,
    session_active: bool,
    seconds_since_last_lock: Option<i64>,
) -> TickDecision {
    let start_window = match (
        current_window(s, now_local),
        s.policy_id.is_some(),
        session_active,
    ) {
        (Some(w), true, false) => Some(w),
        _ => None,
    };
    // 120s debounce, matching macOS's `lastSleepLockAt` guard, so a 30s tick
    // inside the ~3-minute sleep band doesn't lock repeatedly.
    let debounced = seconds_since_last_lock.is_none_or(|secs| secs > 120);
    let lock_now =
        s.lock_at_sleep && debounced && is_at_sleep_moment(s, now_local, Duration::seconds(90));
    TickDecision {
        start_window,
        lock_now,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings() -> BedtimeSettings {
        BedtimeSettings {
            enabled: true,
            wind_down_minute: 22 * 60,
            sleep_minute: 23 * 60,
            wake_minute: 7 * 60,
            lock_at_sleep: true,
            policy_id: Some("p1".into()),
        }
    }

    fn dt(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> NaiveDateTime {
        NaiveDate::from_ymd_opt(y, m, d)
            .unwrap()
            .and_hms_opt(hh, mm, 0)
            .unwrap()
    }

    #[test]
    fn window_crossing_midnight_ends_next_day() {
        let (start, end) = window_for(&settings(), NaiveDate::from_ymd_opt(2026, 1, 10).unwrap());
        assert_eq!(start, dt(2026, 1, 10, 22, 0));
        assert_eq!(end, dt(2026, 1, 11, 7, 0));
    }

    #[test]
    fn disabled_has_no_current_window() {
        let mut s = settings();
        s.enabled = false;
        assert!(current_window(&s, dt(2026, 1, 10, 23, 0)).is_none());
    }

    #[test]
    fn in_window_before_and_after_midnight() {
        let s = settings();
        assert!(
            current_window(&s, dt(2026, 1, 10, 23, 0)).is_some(),
            "before midnight"
        );
        assert!(
            current_window(&s, dt(2026, 1, 11, 2, 0)).is_some(),
            "after midnight (last night's window)"
        );
        assert!(
            current_window(&s, dt(2026, 1, 10, 12, 0)).is_none(),
            "midday"
        );
        assert!(
            current_window(&s, dt(2026, 1, 11, 8, 0)).is_none(),
            "after wake"
        );
    }

    #[test]
    fn sleep_moment_only_near_the_sleep_minute() {
        let s = settings();
        let band = Duration::seconds(90);
        assert!(is_at_sleep_moment(&s, dt(2026, 1, 10, 23, 0), band));
        assert!(is_at_sleep_moment(&s, dt(2026, 1, 10, 22, 59), band));
        assert!(!is_at_sleep_moment(&s, dt(2026, 1, 10, 23, 5), band));
        assert!(
            !is_at_sleep_moment(&s, dt(2026, 1, 10, 22, 30), band),
            "wind-down is not sleep"
        );
    }

    #[test]
    fn tick_starts_a_session_only_when_in_window_idle_and_configured() {
        let s = settings();
        let in_window = dt(2026, 1, 10, 23, 0);

        let d = tick_decision(&s, in_window, false, None);
        assert!(
            d.start_window.is_some(),
            "in window, idle, profile set → start"
        );

        let d = tick_decision(&s, in_window, true, None);
        assert!(
            d.start_window.is_none(),
            "a session is already active → skip"
        );

        let mut no_policy = s.clone();
        no_policy.policy_id = None;
        assert!(tick_decision(&no_policy, in_window, false, None)
            .start_window
            .is_none());

        let midday = dt(2026, 1, 10, 12, 0);
        assert!(tick_decision(&s, midday, false, None)
            .start_window
            .is_none());
    }

    #[test]
    fn tick_lock_is_debounced_and_disabled_when_configured_off() {
        let s = settings();
        let sleep_moment = dt(2026, 1, 10, 23, 0);

        assert!(
            tick_decision(&s, sleep_moment, true, None).lock_now,
            "fresh at sleep moment"
        );
        assert!(
            !tick_decision(&s, sleep_moment, true, Some(30)).lock_now,
            "locked 30s ago → debounced"
        );
        assert!(
            tick_decision(&s, sleep_moment, true, Some(200)).lock_now,
            "locked 200s ago → allowed"
        );

        let mut no_lock = s.clone();
        no_lock.lock_at_sleep = false;
        assert!(!tick_decision(&no_lock, sleep_moment, true, None).lock_now);
    }
}
