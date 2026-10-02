//! Port of apps/android/.../Schedule.kt: recurring focus sessions ("Work: Mon-Fri 9:00-12:00 on Deep Work").
//! `days` are the days the window STARTS on (0 = Monday .. 6 = Sunday); a window that ends at or before its
//! start ends the next day, like Bedtime's.

use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::cheat_day::day_start;
use crate::focus_session::EnforcementMode;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Schedule {
    pub id: String,
    pub name: String,
    pub days: Vec<u8>,
    pub start_minute: i64,
    pub end_minute: i64,
    pub policy_id: String,
    pub mode: EnforcementMode,
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Occurrence {
    pub schedule_id: String,
    pub policy_id: String,
    pub mode: EnforcementMode,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
}

fn occurrence_on<Tz: TimeZone>(s: &Schedule, date: NaiveDate, tz: &Tz) -> Option<Occurrence> {
    use chrono::Datelike;
    if !s
        .days
        .contains(&(date.weekday().num_days_from_monday() as u8))
    {
        return None;
    }
    let start = day_start(date, tz) + Duration::minutes(s.start_minute);
    let mut end = day_start(date, tz) + Duration::minutes(s.end_minute);
    if s.end_minute <= s.start_minute {
        end += Duration::days(1);
    }
    Some(Occurrence {
        schedule_id: s.id.clone(),
        policy_id: s.policy_id.clone(),
        mode: s.mode,
        start,
        end,
    })
}

fn today<Tz: TimeZone>(now: DateTime<Utc>, tz: &Tz) -> NaiveDate {
    now.with_timezone(tz).date_naive()
}

/// The enabled occurrence running at `now` (today's, or one that crossed midnight); the longest-lasting if
/// several.
pub fn current<Tz: TimeZone>(
    schedules: &[Schedule],
    now: DateTime<Utc>,
    tz: &Tz,
) -> Option<Occurrence> {
    let t = today(now, tz);
    schedules
        .iter()
        .filter(|s| s.enabled)
        .flat_map(|s| {
            [t, t - Duration::days(1)]
                .into_iter()
                .filter_map(move |d| occurrence_on(s, d, tz))
        })
        .filter(|o| now >= o.start && now < o.end)
        .max_by_key(|o| o.end)
}

/// The soonest start after `now`, within the next eight days.
pub fn next_start<Tz: TimeZone>(
    schedules: &[Schedule],
    now: DateTime<Utc>,
    tz: &Tz,
) -> Option<DateTime<Utc>> {
    let t = today(now, tz);
    schedules
        .iter()
        .filter(|s| s.enabled)
        .flat_map(|s| (0..=8).filter_map(move |n| occurrence_on(s, t + Duration::days(n), tz)))
        .map(|o| o.start)
        .filter(|s| *s > now)
        .min()
}

/// "Mon-Fri", "Every day", "Sat, Sun", "Mon, Wed, Fri".
pub fn days_label(days: &[u8]) -> String {
    const NAMES: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    let mut d: Vec<u8> = days.iter().copied().filter(|n| *n < 7).collect();
    d.sort_unstable();
    d.dedup();
    if d.len() == 7 {
        "Every day".to_string()
    } else if d.len() > 2 && d.windows(2).all(|w| w[1] - w[0] == 1) {
        format!(
            "{}-{}",
            NAMES[d[0] as usize],
            NAMES[*d.last().unwrap() as usize]
        )
    } else {
        d.iter()
            .map(|n| NAMES[*n as usize])
            .collect::<Vec<_>>()
            .join(", ")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::FixedOffset;

    fn tz() -> FixedOffset {
        FixedOffset::east_opt(3 * 3600).unwrap()
    }
    fn at(d: u32, h: u32, m: u32) -> DateTime<Utc> {
        tz().with_ymd_and_hms(2026, 10, d, h, m, 0)
            .unwrap()
            .with_timezone(&Utc)
    }
    fn work() -> Schedule {
        Schedule {
            id: "w".into(),
            name: "Work".into(),
            days: vec![0, 1, 2, 3, 4],
            start_minute: 9 * 60,
            end_minute: 12 * 60,
            policy_id: "p".into(),
            mode: EnforcementMode::Normal,
            enabled: true,
        }
    }
    // 2026-10-05 is a Monday, 2026-10-03 a Saturday.

    #[test]
    fn inside_a_weekday_window_it_is_current() {
        let o = current(&[work()], at(5, 10, 0), &tz()).unwrap();
        assert_eq!((o.start, o.end), (at(5, 9, 0), at(5, 12, 0)));
    }

    #[test]
    fn outside_the_hours_or_on_a_weekend_nothing_is_current() {
        assert!(current(&[work()], at(5, 8, 59), &tz()).is_none());
        assert!(current(&[work()], at(5, 12, 0), &tz()).is_none());
        assert!(current(&[work()], at(3, 10, 0), &tz()).is_none());
    }

    #[test]
    fn a_disabled_schedule_never_runs() {
        let s = Schedule {
            enabled: false,
            ..work()
        };
        assert!(current(std::slice::from_ref(&s), at(5, 10, 0), &tz()).is_none());
        assert!(next_start(&[s], at(5, 10, 0), &tz()).is_none());
    }

    #[test]
    fn a_window_crossing_midnight_is_current_after_midnight_on_the_day_it_started() {
        let night = Schedule {
            days: vec![4],
            start_minute: 22 * 60,
            end_minute: 2 * 60,
            ..work()
        }; // Fri 22:00 to Sat 02:00
        assert!(current(std::slice::from_ref(&night), at(10, 1, 0), &tz()).is_some()); // Saturday 01:00
        assert!(current(&[night], at(11, 1, 0), &tz()).is_none()); // Sunday 01:00
    }

    #[test]
    fn next_start_skips_to_the_next_allowed_day() {
        assert_eq!(
            next_start(&[work()], at(3, 10, 0), &tz()),
            Some(at(5, 9, 0))
        );
        assert_eq!(next_start(&[work()], at(5, 9, 0), &tz()), Some(at(6, 9, 0)));
    }

    #[test]
    fn the_longest_of_two_overlapping_windows_wins() {
        let long = Schedule {
            id: "l".into(),
            end_minute: 15 * 60,
            ..work()
        };
        assert_eq!(
            current(&[work(), long], at(5, 10, 0), &tz())
                .unwrap()
                .schedule_id,
            "l"
        );
    }

    #[test]
    fn day_labels() {
        assert_eq!(days_label(&[0, 1, 2, 3, 4]), "Mon-Fri");
        assert_eq!(days_label(&[0, 1, 2, 3, 4, 5, 6]), "Every day");
        assert_eq!(days_label(&[5, 6]), "Sat, Sun");
        assert_eq!(days_label(&[0, 2, 4]), "Mon, Wed, Fri");
    }
}
