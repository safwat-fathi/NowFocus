//! Port of apps/android/.../UsageLimits.kt and SiteLimits.kt: "30 minutes of Instagram a day". Past it, the app
//! (or website) is blocked until local midnight. A limit can be tightened or removed at once while there is
//! time left, but raising it, or removing one that is already used up, only counts from the next midnight: the
//! moment you want a limit gone is the moment it has just stopped you.
//!
//! One limit type for both: `key` is an executable path for an app, or `site:<domain>` for a website.
//! ponytail: the "site:" prefix instead of a kind field, as on Android; add a real kind if a third appears.

use chrono::{DateTime, Duration, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::cheat_day::day_start;
use crate::domain_validation::host_matches;

pub const MINUTE_CHOICES: [u32; 6] = [15, 30, 45, 60, 90, 120];
const SITE_PREFIX: &str = "site:";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DailyLimit {
    pub key: String,
    pub label: String,
    pub minutes_per_day: u32,
    /// A change waiting for midnight; 0 means "remove".
    pub pending_minutes: Option<u32>,
    pub pending_from: Option<DateTime<Utc>>,
}

pub fn site_key(domain: &str) -> String {
    format!("{SITE_PREFIX}{domain}")
}

/// Local midnight that ends the day containing `now`: when a limit that was reached lifts.
pub fn next_midnight<Tz: TimeZone>(now: DateTime<Utc>, tz: &Tz) -> DateTime<Utc> {
    day_start(now.with_timezone(tz).date_naive() + Duration::days(1), tz)
}

/// The local calendar day of `now`; usage counters roll over when this changes.
pub fn day_key<Tz: TimeZone>(now: DateTime<Utc>, tz: &Tz) -> String {
    now.with_timezone(tz).date_naive().to_string()
}

impl DailyLimit {
    pub fn new(key: &str, label: &str, minutes: u32) -> Self {
        Self {
            key: key.to_string(),
            label: label.to_string(),
            minutes_per_day: minutes,
            pending_minutes: None,
            pending_from: None,
        }
    }

    pub fn is_site(&self) -> bool {
        self.key.starts_with(SITE_PREFIX)
    }

    pub fn domain(&self) -> Option<&str> {
        self.key.strip_prefix(SITE_PREFIX)
    }

    fn pending_due(&self, now: DateTime<Utc>) -> Option<u32> {
        match (self.pending_minutes, self.pending_from) {
            (Some(m), Some(from)) if now >= from => Some(m),
            _ => None,
        }
    }

    /// The minutes in force at `now`; 0 means no limit.
    pub fn minutes_at(&self, now: DateTime<Utc>) -> u32 {
        self.pending_due(now).unwrap_or(self.minutes_per_day)
    }

    pub fn limit_ms_at(&self, now: DateTime<Utc>) -> i64 {
        self.minutes_at(now) as i64 * 60_000
    }

    /// Folds a pending change that has come due into the limit itself. `None` once it is a removed limit.
    pub fn settled(self, now: DateTime<Utc>) -> Option<Self> {
        match self.pending_due(now) {
            Some(0) => None,
            Some(m) => Some(Self {
                minutes_per_day: m,
                pending_minutes: None,
                pending_from: None,
                ..self
            }),
            None if self.minutes_per_day == 0 => None, // removed at once
            None => Some(self),
        }
    }

    /// `minutes` 0 removes it. Tighter applies now; raising waits for midnight. Removing is also at once, unless
    /// the limit is already used up (`used_ms` reached it): then it waits too.
    pub fn with_minutes<Tz: TimeZone>(
        &self,
        minutes: u32,
        now: DateTime<Utc>,
        tz: &Tz,
        used_ms: i64,
    ) -> Self {
        let current = self.minutes_at(now);
        let used_up = current != 0 && used_ms >= self.limit_ms_at(now);
        let looser = (minutes == 0 && used_up) || (current != 0 && minutes > current);
        if looser {
            Self {
                pending_minutes: Some(minutes),
                pending_from: Some(next_midnight(now, tz)),
                ..self.clone()
            }
        } else {
            Self {
                minutes_per_day: minutes,
                pending_minutes: None,
                pending_from: None,
                ..self.clone()
            }
        }
    }
}

/// The limit for an app's executable (case-insensitive, like Windows paths).
pub fn limit_for_exe<'a>(limits: &'a [DailyLimit], exe: &str) -> Option<&'a DailyLimit> {
    limits
        .iter()
        .find(|l| !l.is_site() && l.key.eq_ignore_ascii_case(exe))
}

/// The site limit that covers `host` ("m.youtube.com" -> youtube.com), the longest domain first.
pub fn limit_for_host<'a>(limits: &'a [DailyLimit], host: &str) -> Option<&'a DailyLimit> {
    limits
        .iter()
        .filter(|l| l.domain().is_some_and(|d| host_matches(host, d)))
        .max_by_key(|l| l.domain().map_or(0, str::len))
}

/// What was in front on the previous tick. `key` None = nothing limited.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tick {
    pub key: Option<String>,
    pub at: DateTime<Utc>,
}

/// Milliseconds to credit to `prev`'s key now, capped at `max_gap` so a stall (sleep, a missed tick)
/// can't over-credit: the time between ticks is only ever as long as one tick interval.
pub fn credit_ms(prev: Option<&Tick>, now: DateTime<Utc>, max_gap: Duration) -> i64 {
    match prev {
        Some(Tick { key: Some(_), at }) => (now - *at)
            .num_milliseconds()
            .clamp(0, max_gap.num_milliseconds()),
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{FixedOffset, TimeZone};

    // UTC+3 stands in for Cairo's summer time; the DST skip is covered by cheat_day's own tests.
    fn tz() -> FixedOffset {
        FixedOffset::east_opt(3 * 3600).unwrap()
    }
    fn at(d: u32, h: u32, m: u32) -> DateTime<Utc> {
        tz().with_ymd_and_hms(2026, 10, d, h, m, 0).unwrap().with_timezone(&Utc)
    }
    fn ig() -> DailyLimit {
        DailyLimit::new("c:\\apps\\ig.exe", "Instagram", 30)
    }

    #[test]
    fn a_limit_lifts_at_the_next_local_midnight() {
        assert_eq!(next_midnight(at(2, 23, 59), &tz()), at(3, 0, 0));
        assert_eq!(next_midnight(at(2, 0, 0), &tz()), at(3, 0, 0));
    }

    #[test]
    fn tightening_counts_at_once() {
        let l = ig().with_minutes(15, at(2, 10, 0), &tz(), 0);
        assert_eq!(l.minutes_at(at(2, 10, 0)), 15);
        assert!(l.pending_minutes.is_none());
    }

    #[test]
    fn loosening_waits_for_midnight() {
        let l = ig().with_minutes(60, at(2, 10, 0), &tz(), 0);
        assert_eq!(l.minutes_at(at(2, 23, 59)), 30);
        assert_eq!(l.minutes_at(at(3, 0, 0)), 60);
        assert_eq!(l.clone().settled(at(2, 12, 0)), Some(l.clone()));
        assert_eq!(l.settled(at(3, 0, 1)).unwrap().minutes_per_day, 60);
    }

    #[test]
    fn removing_with_time_left_is_at_once() {
        let l = ig().with_minutes(0, at(2, 10, 0), &tz(), 10 * 60_000);
        assert_eq!(l.minutes_at(at(2, 10, 0)), 0);
        assert_eq!(l.settled(at(2, 10, 0)), None);
    }

    #[test]
    fn removing_a_used_up_limit_waits_for_midnight_then_is_gone() {
        let l = ig().with_minutes(0, at(2, 10, 0), &tz(), 30 * 60_000);
        assert_eq!(l.minutes_at(at(2, 23, 59)), 30);
        assert_eq!(l.minutes_at(at(3, 0, 0)), 0);
        assert_eq!(l.settled(at(3, 0, 1)), None);
    }

    #[test]
    fn exe_match_ignores_case_and_never_matches_a_site() {
        let limits = vec![ig(), DailyLimit::new(&site_key("youtube.com"), "youtube.com", 30)];
        assert!(limit_for_exe(&limits, "C:\\Apps\\IG.exe").is_some());
        assert!(limit_for_exe(&limits, "site:youtube.com").is_none());
    }

    #[test]
    fn a_subdomain_counts_toward_the_parent_and_the_longest_wins() {
        let limits = vec![
            DailyLimit::new(&site_key("youtube.com"), "youtube.com", 30),
            DailyLimit::new(&site_key("music.youtube.com"), "music.youtube.com", 60),
            ig(),
        ];
        let key = |h| limit_for_host(&limits, h).map(|l| l.key.clone());
        assert_eq!(key("m.youtube.com"), Some(site_key("youtube.com")));
        assert_eq!(key("music.youtube.com"), Some(site_key("music.youtube.com")));
        assert_eq!(key("notyoutube.com"), None);
    }

    #[test]
    fn a_stalled_tick_credits_at_most_one_interval() {
        let tick = |k: Option<&str>, t| Tick { key: k.map(String::from), at: t };
        let gap = Duration::seconds(10);
        assert_eq!(credit_ms(Some(&tick(Some("a"), at(2, 9, 0))), at(2, 9, 0) + Duration::seconds(3), gap), 3_000);
        assert_eq!(credit_ms(Some(&tick(Some("a"), at(2, 9, 0))), at(2, 12, 0), gap), 10_000);
        assert_eq!(credit_ms(Some(&tick(None, at(2, 9, 0))), at(2, 9, 1), gap), 0);
        assert_eq!(credit_ms(None, at(2, 9, 1), gap), 0);
        assert_eq!(credit_ms(Some(&tick(Some("a"), at(2, 9, 5))), at(2, 9, 0), gap), 0);
    }

    #[test]
    fn the_day_flips_at_local_midnight() {
        assert_eq!(day_key(at(2, 23, 59), &tz()), "2026-10-02");
        assert_eq!(day_key(at(3, 0, 0), &tz()), "2026-10-03");
    }
}
