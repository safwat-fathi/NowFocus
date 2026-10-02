//! Port of apps/android/.../HistoryStats.kt (and macOS HistoryStats.swift). Pure
//! aggregation over ended focus sessions and block events — no DB, no OS — so it
//! tests on any host. The caller passes already time-ranged, focus-only session
//! rows (the `sessions_between` DB query filters `session_type = 'focus'`) so
//! nightly bedtime sessions never inflate focused time, completion, or streak.

use chrono::{DateTime, Datelike, Duration, NaiveDate, TimeZone, Utc};

use crate::focus_session::{FocusSession, FocusSessionStatus};

/// A recorded block attempt, with the target (app/site) parsed from the event's
/// `metadata_json`. `target` empty when a legacy event stored no metadata.
#[derive(Debug, Clone)]
pub struct BlockEvent {
    pub occurred_at: DateTime<Utc>,
    pub target: String,
}

/// Real focused time in minutes: full duration if it ran to completion,
/// elapsed-so-far if cancelled early, 0 otherwise. Mirrors Android exactly.
pub fn focused_minutes(s: &FocusSession) -> i64 {
    let span = match s.status {
        FocusSessionStatus::Completed => s.end_at - s.start_at,
        _ => match s.cancelled_at {
            Some(c) => c - s.start_at,
            None => Duration::zero(),
        },
    };
    span.num_minutes().max(0)
}

pub fn total_focused_minutes(sessions: &[FocusSession]) -> i64 {
    sessions.iter().map(focused_minutes).sum()
}

pub fn sessions_count(sessions: &[FocusSession]) -> usize {
    sessions.len()
}

pub fn completed_count(sessions: &[FocusSession]) -> usize {
    sessions
        .iter()
        .filter(|s| s.status == FocusSessionStatus::Completed)
        .count()
}

pub fn completion_rate(sessions: &[FocusSession]) -> f64 {
    if sessions.is_empty() {
        0.0
    } else {
        completed_count(sessions) as f64 / sessions.len() as f64
    }
}

/// The Monday on or before `date`.
pub fn monday_of(date: NaiveDate) -> NaiveDate {
    date - Duration::days(date.weekday().num_days_from_monday() as i64)
}

/// Minutes focused per day, Monday..Sunday, for the week whose Monday is
/// `monday`. Sessions dated outside the week are ignored.
pub fn week_buckets_minutes<Tz: TimeZone>(
    sessions: &[FocusSession],
    monday: NaiveDate,
    tz: &Tz,
) -> [i64; 7] {
    let mut buckets = [0i64; 7];
    for s in sessions {
        let day = s.start_at.with_timezone(tz).date_naive();
        let offset = (day - monday).num_days();
        if (0..7).contains(&offset) {
            buckets[offset as usize] += focused_minutes(s);
        }
    }
    buckets
}

/// A day counts toward the streak with at least this much real focused time, so opening and ending a session at
/// once doesn't.
pub const STREAK_MIN_MINUTES: i64 = 10;

/// Consecutive days with at least [`STREAK_MIN_MINUTES`] of focused time, walking back from the most recent
/// such day. One-day grace: a streak whose most recent day was yesterday still counts (it shouldn't zero out
/// before today's session happens). Mirrors Android's `currentStreakDays`.
pub fn current_streak_days<Tz: TimeZone>(
    sessions: &[FocusSession],
    today: NaiveDate,
    tz: &Tz,
) -> i64 {
    use std::collections::{HashMap, HashSet};
    let mut per_day: HashMap<NaiveDate, i64> = HashMap::new();
    for s in sessions {
        *per_day
            .entry(s.start_at.with_timezone(tz).date_naive())
            .or_insert(0) += focused_minutes(s);
    }
    let active: HashSet<NaiveDate> = per_day
        .into_iter()
        .filter(|(_, minutes)| *minutes >= STREAK_MIN_MINUTES)
        .map(|(d, _)| d)
        .collect();
    let Some(most_recent) = active.iter().max().copied() else {
        return 0;
    };
    if most_recent < today - Duration::days(1) {
        return 0;
    }
    let mut streak = 0;
    let mut day = most_recent;
    while active.contains(&day) {
        streak += 1;
        day -= Duration::days(1);
    }
    streak
}

/// 0-100 for the sessions of one week: half the completion rate, 30% focused time (five hours is full marks),
/// 20% days with focused time (five is full marks). `None` for a week with no sessions, so a quiet week isn't a
/// zero. Mirrors Android's `focusScore`: keep them in step.
pub fn focus_score<Tz: TimeZone>(sessions: &[FocusSession], tz: &Tz) -> Option<i64> {
    if sessions.is_empty() {
        return None;
    }
    let minutes = total_focused_minutes(sessions) as f64;
    let days = sessions
        .iter()
        .filter(|s| focused_minutes(s) > 0)
        .map(|s| s.start_at.with_timezone(tz).date_naive())
        .collect::<std::collections::HashSet<_>>()
        .len() as f64;
    let score = 0.5 * completion_rate(sessions)
        + 0.3 * (minutes / 300.0).min(1.0)
        + 0.2 * (days / 5.0).min(1.0);
    Some((score * 100.0).round() as i64)
}

/// The text "Copy this week" puts on the clipboard. Counts only: no app names, sites or times of day.
pub fn week_summary_text(
    sessions: &[FocusSession],
    turned_away: usize,
    streak_days: i64,
    score: Option<i64>,
) -> String {
    let minutes = total_focused_minutes(sessions);
    let n = sessions_count(sessions);
    let mut text = format!(
        "My NowFocus week: {}h {}m focused across {} {} ({} completed).",
        minutes / 60,
        minutes % 60,
        n,
        if n == 1 { "session" } else { "sessions" },
        completed_count(sessions)
    );
    if let Some(s) = score {
        text.push_str(&format!(" Focus score {s}."));
    }
    if streak_days > 0 {
        text.push_str(&format!(" {streak_days}-day streak."));
    }
    text.push_str(&format!(" Turned away {turned_away} times."));
    text
}

/// The most-turned-away targets, most frequent first (ties broken by name for
/// stable output). Empty-target events are skipped.
pub fn top_targets(events: &[BlockEvent], limit: usize) -> Vec<(String, usize)> {
    use std::collections::HashMap;
    let mut counts: HashMap<&str, usize> = HashMap::new();
    for e in events {
        if !e.target.is_empty() {
            *counts.entry(e.target.as_str()).or_insert(0) += 1;
        }
    }
    let mut ranked: Vec<(String, usize)> = counts
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
    ranked.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    ranked.truncate(limit);
    ranked
}

pub fn turned_away_count(events: &[BlockEvent]) -> usize {
    events.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Ported from apps/android/.../HistoryStatsTest.kt.

    fn session(start: DateTime<Utc>, mins: i64, status: FocusSessionStatus) -> FocusSession {
        let mut s = FocusSession::new("p1", start, start + Duration::minutes(mins), "test");
        s.status = status;
        s
    }

    #[test]
    fn focused_minutes_credits_completed_full_and_cancelled_elapsed() {
        let now = Utc::now();
        let done = session(now, 25, FocusSessionStatus::Completed);
        assert_eq!(focused_minutes(&done), 25);

        let mut early = session(now, 25, FocusSessionStatus::Cancelled);
        early.cancelled_at = Some(now + Duration::minutes(10));
        assert_eq!(focused_minutes(&early), 10);

        let scheduled = session(now, 25, FocusSessionStatus::Scheduled);
        assert_eq!(focused_minutes(&scheduled), 0);
    }

    #[test]
    fn completion_rate_is_completed_over_total() {
        let now = Utc::now();
        let rows = vec![
            session(now, 25, FocusSessionStatus::Completed),
            session(now, 25, FocusSessionStatus::Completed),
            session(now, 25, FocusSessionStatus::Cancelled),
        ];
        assert!((completion_rate(&rows) - 2.0 / 3.0).abs() < 1e-9);
        assert_eq!(completion_rate(&[]), 0.0);
    }

    #[test]
    fn week_buckets_place_minutes_on_the_right_weekday() {
        let monday = NaiveDate::from_ymd_opt(2026, 1, 5).unwrap(); // a Monday
        let mon_noon = Utc.with_ymd_and_hms(2026, 1, 5, 12, 0, 0).unwrap();
        let wed_noon = Utc.with_ymd_and_hms(2026, 1, 7, 12, 0, 0).unwrap();
        let rows = vec![
            session(mon_noon, 25, FocusSessionStatus::Completed),
            session(wed_noon, 45, FocusSessionStatus::Completed),
        ];
        let buckets = week_buckets_minutes(&rows, monday, &Utc);
        assert_eq!(buckets[0], 25);
        assert_eq!(buckets[2], 45);
        assert_eq!(buckets[1], 0);
    }

    #[test]
    fn streak_counts_back_with_one_day_grace() {
        let today = NaiveDate::from_ymd_opt(2026, 1, 10).unwrap();
        let day = |d: u32| Utc.with_ymd_and_hms(2026, 1, d, 9, 0, 0).unwrap();
        // Sessions on the 8th, 9th (yesterday) — grace keeps it alive today.
        let rows = vec![
            session(day(8), 25, FocusSessionStatus::Completed),
            session(day(9), 25, FocusSessionStatus::Completed),
        ];
        assert_eq!(current_streak_days(&rows, today, &Utc), 2);

        // Gap: most recent is the 7th, older than yesterday → 0.
        let stale = vec![session(day(7), 25, FocusSessionStatus::Completed)];
        assert_eq!(current_streak_days(&stale, today, &Utc), 0);
        assert_eq!(current_streak_days(&[], today, &Utc), 0);
    }

    #[test]
    fn top_targets_ranks_by_frequency() {
        let now = Utc::now();
        let ev = |t: &str| BlockEvent {
            occurred_at: now,
            target: t.to_string(),
        };
        let events = vec![ev("Steam"), ev("Steam"), ev("Discord"), ev("")];
        let top = top_targets(&events, 3);
        assert_eq!(top[0], ("Steam".to_string(), 2));
        assert_eq!(top[1], ("Discord".to_string(), 1));
        assert_eq!(turned_away_count(&events), 4);
    }

    fn cancelled_after(start: DateTime<Utc>, scheduled: i64, actual: i64) -> FocusSession {
        let mut s = session(start, scheduled, FocusSessionStatus::Cancelled);
        s.cancelled_at = Some(start + Duration::minutes(actual));
        s
    }

    #[test]
    fn a_session_ended_almost_at_once_does_not_keep_the_streak_alive() {
        let today = NaiveDate::from_ymd_opt(2026, 1, 10).unwrap();
        let day = |d: u32| Utc.with_ymd_and_hms(2026, 1, d, 9, 0, 0).unwrap();
        let rows = vec![
            session(day(9), 30, FocusSessionStatus::Completed),
            cancelled_after(day(10), 60, 1),
        ];
        assert_eq!(current_streak_days(&rows, today, &Utc), 1);
        assert_eq!(
            current_streak_days(&[cancelled_after(day(10), 60, 2)], today, &Utc),
            0
        );
    }

    #[test]
    fn several_short_sessions_in_a_day_add_up_toward_the_streak() {
        let today = NaiveDate::from_ymd_opt(2026, 1, 10).unwrap();
        let t = |h: u32| Utc.with_ymd_and_hms(2026, 1, 10, h, 0, 0).unwrap();
        let rows = vec![cancelled_after(t(9), 30, 5), cancelled_after(t(11), 30, 6)];
        assert_eq!(current_streak_days(&rows, today, &Utc), 1);
    }

    #[test]
    fn focus_score_is_none_for_an_empty_week_and_full_marks_for_a_perfect_one() {
        assert_eq!(focus_score(&[], &Utc), None);
        let monday = Utc.with_ymd_and_hms(2026, 1, 5, 9, 0, 0).unwrap();
        let perfect: Vec<_> = (0..5)
            .map(|d| {
                session(
                    monday + Duration::days(d),
                    60,
                    FocusSessionStatus::Completed,
                )
            })
            .collect(); // 5 days x 1h = 5h, all completed
        assert_eq!(focus_score(&perfect, &Utc), Some(100));
    }

    #[test]
    fn focus_score_weighs_completion_time_and_days() {
        let monday = Utc.with_ymd_and_hms(2026, 1, 5, 9, 0, 0).unwrap();
        // 0.5*1 + 0.3*(60/300) + 0.2*(1/5) = 0.60
        assert_eq!(
            focus_score(&[session(monday, 60, FocusSessionStatus::Completed)], &Utc),
            Some(60)
        );
        // completion 0, 30 focused minutes: 0.3*0.1 + 0.2*0.2 = 0.07
        assert_eq!(
            focus_score(&[cancelled_after(monday, 60, 30)], &Utc),
            Some(7)
        );
    }

    #[test]
    fn the_weekly_summary_has_counts_only() {
        let monday = Utc.with_ymd_and_hms(2026, 1, 5, 9, 0, 0).unwrap();
        let rows = vec![
            session(monday, 90, FocusSessionStatus::Completed),
            cancelled_after(monday + Duration::days(1), 60, 30),
        ];
        let text = week_summary_text(&rows, 1, 2, focus_score(&rows, &Utc));
        assert!(
            text.starts_with("My NowFocus week: 2h 0m focused across 2 sessions (1 completed)."),
            "{text}"
        );
        assert!(text.contains("2-day streak."), "{text}");
        assert!(text.contains("Turned away 1 times."), "{text}");
    }
}
