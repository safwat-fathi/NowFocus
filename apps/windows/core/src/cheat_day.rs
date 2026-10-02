//! Port of apps/android/.../CheatDay.kt. A whole local day on which sessions, Bedtime, schedules and limits
//! don't block. The Commitment Shield (service-held) is never touched by it.
//!
//! Pre-committed, not impulsive: set at least [`LEAD_HOURS`] ahead, and at most one in any [`GAP_DAYS`]-day
//! span, so it can't be reached for in the moment blocking is hardest to bear.
//!
//! ponytail: wall clock only, unlike the commitment's boot-relative check. Winding the clock forward could
//! start one early, which is the user defeating their own tool.

use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};

pub const LEAD_HOURS: i64 = 24;
pub const GAP_DAYS: i64 = 7;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CheatDay {
    pub start_at: DateTime<Utc>,
    pub end_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
}

impl CheatDay {
    pub fn is_active(&self, now: DateTime<Utc>) -> bool {
        now >= self.start_at && now < self.end_at
    }
}

/// Local midnight at the start of `date`. Some zones skip midnight on a DST day (Cairo does), so fall back to
/// the first hour that exists.
pub fn day_start<Tz: TimeZone>(date: NaiveDate, tz: &Tz) -> DateTime<Utc> {
    let midnight = date.and_hms_opt(0, 0, 0).expect("valid time");
    tz.from_local_datetime(&midnight)
        .earliest()
        .or_else(|| {
            tz.from_local_datetime(&(midnight + Duration::hours(1)))
                .earliest()
        })
        .expect("a local time exists within the first hour")
        .with_timezone(&Utc)
}

fn date_of<Tz: TimeZone>(t: DateTime<Utc>, tz: &Tz) -> NaiveDate {
    t.with_timezone(tz).date_naive()
}

/// `existing` is the latest cheat day, used or scheduled; a new one must be a week clear of it.
pub fn can_schedule<Tz: TimeZone>(
    now: DateTime<Utc>,
    day: DateTime<Utc>,
    existing: Option<&CheatDay>,
    tz: &Tz,
) -> bool {
    if day - now < Duration::hours(LEAD_HOURS) {
        return false;
    }
    match existing {
        None => true,
        Some(e) => {
            (date_of(day, tz) - date_of(e.start_at, tz))
                .num_days()
                .abs()
                >= GAP_DAYS
        }
    }
}

/// Day starts you can still pick, soonest first.
pub fn options<Tz: TimeZone>(
    now: DateTime<Utc>,
    tz: &Tz,
    existing: Option<&CheatDay>,
    count: usize,
) -> Vec<DateTime<Utc>> {
    let today = date_of(now, tz);
    (1..=(count as i64 + 2))
        .map(|n| day_start(today + Duration::days(n), tz))
        .filter(|d| can_schedule(now, *d, existing, tz))
        .take(count)
        .collect()
}

pub fn for_day<Tz: TimeZone>(day: DateTime<Utc>, now: DateTime<Utc>, tz: &Tz) -> CheatDay {
    CheatDay {
        start_at: day,
        end_at: day_start(date_of(day, tz) + Duration::days(1), tz),
        created_at: now,
    }
}

/// Cancelling a day that hasn't begun frees the week; ending a live one early keeps it counted.
pub fn cancel(cheat: &CheatDay, now: DateTime<Utc>) -> Option<CheatDay> {
    if now < cheat.start_at {
        None
    } else if cheat.is_active(now) {
        Some(CheatDay {
            end_at: now,
            ..cheat.clone()
        })
    } else {
        Some(cheat.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::FixedOffset;

    fn tz() -> FixedOffset {
        FixedOffset::east_opt(3 * 3600).unwrap() // Cairo, summer time
    }
    fn at(y: i32, m: u32, d: u32, h: u32) -> DateTime<Utc> {
        tz().with_ymd_and_hms(y, m, d, h, 0, 0)
            .unwrap()
            .with_timezone(&Utc)
    }

    #[test]
    fn tomorrow_is_too_soon_unless_a_full_day_away() {
        let now = at(2026, 10, 2, 10);
        assert!(!can_schedule(now, at(2026, 10, 3, 0), None, &tz())); // 14h
        assert!(can_schedule(now, at(2026, 10, 4, 0), None, &tz())); // 38h
        assert!(can_schedule(
            at(2026, 10, 2, 0),
            at(2026, 10, 3, 0),
            None,
            &tz()
        )); // exactly 24h
    }

    #[test]
    fn one_a_week_in_both_directions() {
        let now = at(2026, 10, 1, 10);
        let existing = for_day(at(2026, 10, 10, 0), now, &tz());
        assert!(!can_schedule(
            now,
            at(2026, 10, 16, 0),
            Some(&existing),
            &tz()
        )); // 6 after
        assert!(can_schedule(
            now,
            at(2026, 10, 17, 0),
            Some(&existing),
            &tz()
        )); // 7 after
        assert!(!can_schedule(
            now,
            at(2026, 10, 5, 0),
            Some(&existing),
            &tz()
        )); // 5 before
        assert!(can_schedule(
            now,
            at(2026, 10, 3, 0),
            Some(&existing),
            &tz()
        )); // 7 before
    }

    #[test]
    fn options_are_soonest_first_and_all_legal() {
        let now = at(2026, 10, 2, 10);
        let existing = for_day(at(2026, 9, 30, 0), now, &tz());
        let opts = options(now, &tz(), Some(&existing), 14);
        assert_eq!(opts[0], at(2026, 10, 7, 0));
        assert!(opts
            .iter()
            .all(|d| can_schedule(now, *d, Some(&existing), &tz())));
        assert!(opts.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn a_cheat_day_runs_midnight_to_midnight() {
        let c = for_day(at(2026, 10, 10, 0), at(2026, 10, 1, 0), &tz());
        assert_eq!(c.end_at, at(2026, 10, 11, 0));
        assert!(c.is_active(at(2026, 10, 10, 12)));
        assert!(!c.is_active(at(2026, 10, 9, 23)));
        assert!(!c.is_active(at(2026, 10, 11, 0)));
    }

    #[test]
    fn cancelling_before_it_starts_frees_the_week_ending_a_live_one_keeps_it_counted() {
        let c = for_day(at(2026, 10, 10, 0), at(2026, 10, 1, 0), &tz());
        assert_eq!(cancel(&c, at(2026, 10, 9, 0)), None);
        let ended = cancel(&c, at(2026, 10, 10, 12)).unwrap();
        assert_eq!(ended.end_at, at(2026, 10, 10, 12));
        assert_eq!(ended.start_at, c.start_at);
        assert_eq!(cancel(&c, at(2026, 10, 12, 0)), Some(c));
    }
}
