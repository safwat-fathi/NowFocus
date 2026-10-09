use std::collections::HashMap;
use std::path::Path;

use chrono::{
    DateTime, Duration as ChronoDuration, Local, NaiveDate, NaiveDateTime, TimeZone, Utc,
};
use now_focus_core::block_policy::FeedRule;
use now_focus_core::cheat_day::{self, CheatDay};
use now_focus_core::daily_limit::{self, DailyLimit};
use now_focus_core::feed_url::URL_FEEDS;
use now_focus_core::history_stats::BlockEvent;
use now_focus_core::schedule::{self, Schedule};
use now_focus_core::{
    allowlist, bedtime_schedule, domain_validation, history_stats, passes, session_engine,
    ApplicationRule, BedtimeSettings, DomainRule, EnforcementMode, FocusSession,
    FocusSessionStatus, PolicyMode, Profile, SessionOrigin, SessionType,
};
use now_focus_ipc::CommitmentStatusWire;

use crate::dto::{
    AppStateDto, ApplicationRuleDto, BedtimeDto, CheatDayDto, CommitmentDto, DomainRuleDto,
    FeedRuleDto, LimitDto, ProfileDto, ScheduleDto, SessionDto, ShieldDto, StatsDto, SyncStatusDto,
    TargetCountDto, UnlockStateDto,
};
use crate::enforcer::Enforcer;

/// How long the "say it, then wait" pause lasts in Strict mode. The mockup's
/// `NowFocus PC.dc.html` exposed this as a designer-only prop
/// (`unlockWait`, 5-120s, default 30) for previewing different values; the
/// real app has no such setting yet, so it's a fixed constant matching that
/// default. Promote it to a real user preference if that's ever asked for.
const UNLOCK_WAIT: ChronoDuration = ChronoDuration::seconds(30);

/// A foreground note credits at most this long to the app that was in front (the recheck runs every 30s).
const LIMIT_MAX_GAP_SECS: i64 = 45;

const UNLOCK_SENTENCE: &str = "I am choosing to end this focus session early.";
/// The Arabic one, typed instead when the app is in Arabic. Plain letters only, so an exact match needs no
/// normalization. Keep in step with `unlock.sentence` in src/i18n/ar.ts.
const UNLOCK_SENTENCE_AR: &str = "قررت الخروج من جلسة التركيز قبل موعدها.";

fn unlock_matches(typed: &str) -> bool {
    let typed = typed.trim();
    typed == UNLOCK_SENTENCE || typed == UNLOCK_SENTENCE_AR
}

/// The feed rules this PC can enforce: pages with their own address (see `feed_url`), checked in the front
/// browser's address bar. The other synced names (`ythome`, `xfy`, Android's) are kept but have no row here.
/// Labels live in the UI's i18n (`feed.<key>`); these are the English fallback the DTO carries.
const FEED_DEFS: &[(&str, &str, &str)] = &[
    (
        "shorts",
        "YouTube Shorts",
        "Closes the tab when a Shorts page opens",
    ),
    (
        "reels",
        "Instagram Reels & Explore",
        "Closes the tab; the feed and DMs stay open",
    ),
    (
        "fbreels",
        "Facebook Reels",
        "Closes the tab; the feed stays open",
    ),
];

struct UnlockFlow {
    typed: String,
    wait_started_at: Option<DateTime<Utc>>,
}

pub struct AppState {
    db: now_focus_core::Database,
    enforcer: Box<dyn Enforcer>,
    device_id: String,
    unlock: Option<UnlockFlow>,
    shield: Option<ShieldDto>,
    /// Cached commitment status. Refreshed on apply/clear and the 30s tick, not
    /// per-snapshot — the UI (main window + each per-monitor overlay) polls
    /// every 500ms against a one-client pipe, so a per-snapshot round-trip
    /// would thrash the service.
    commitment: Option<CommitmentStatusWire>,
    /// Last logged time per block target, for the 3s dedup (a single "try to
    /// open it" gesture fires several foreground events).
    last_block_at: HashMap<String, DateTime<Utc>>,
    /// Debounce for the bedtime sleep-time screen lock (macOS's lastSleepLockAt).
    last_sleep_lock_at: Option<DateTime<Utc>>,
    /// Whether the running session's policy is applied to the enforcer right now. False while a cheat day
    /// pauses it, and until the first tick after launch re-asserts it.
    session_enforced: bool,
    /// Live per-app passes (lowercased exe path to when it ends) and how many the running session has used.
    /// In memory only: a restart forgets them.
    passes: Vec<(String, DateTime<Utc>)>,
    passes_used: (Option<String>, usize),
    /// The blocked app the Shield is showing, so a pass can name it.
    shield_native: Option<String>,
    /// What the sync loop last reported, for the Devices screen. Set by `sync_glue`.
    sync_status: SyncStatusDto,
    /// The limited app (or none) that was in front at the last foreground note, and when: the next note
    /// credits the time since then to it. In memory only.
    limit_tick: Option<daily_limit::Tick>,
}

impl AppState {
    pub fn open(db_path: &Path, enforcer: Box<dyn Enforcer>) -> Result<Self, String> {
        let db = now_focus_core::Database::open(db_path).map_err(|e| e.to_string())?;
        let device_id = db.device_id().map_err(|e| e.to_string())?;

        if db.list_policy_ids().map_err(|e| e.to_string())?.is_empty() {
            // First run: seed one starter profile so Focus/Profiles aren't
            // empty on first launch. Real user profiles replace/extend this.
            let starter = Profile::new("Deep Work");
            db.save_profile(&starter).map_err(|e| e.to_string())?;
        }

        let commitment = enforcer.commitment_status();
        Ok(Self::from_parts(db, enforcer, device_id, commitment))
    }

    fn from_parts(
        db: now_focus_core::Database,
        enforcer: Box<dyn Enforcer>,
        device_id: String,
        commitment: Option<CommitmentStatusWire>,
    ) -> Self {
        Self {
            db,
            enforcer,
            device_id,
            unlock: None,
            shield: None,
            commitment,
            last_block_at: HashMap::new(),
            last_sleep_lock_at: None,
            session_enforced: false,
            passes: Vec::new(),
            passes_used: (None, 0),
            shield_native: None,
            sync_status: SyncStatusDto::default(),
            limit_tick: None,
        }
    }

    // ---- Recovery --------------------------------------------------

    /// Reconciles the persisted session against wall-clock time. Called at
    /// the start of every command that reads or depends on session state —
    /// this is the "every recovery path recomputes activity from persisted
    /// state and the current time" rule, not a one-time startup check.
    fn recover(&mut self) -> Result<Option<FocusSession>, String> {
        let Some(mut session) = self.db.most_recent_session().map_err(|e| e.to_string())? else {
            return Ok(None);
        };
        let before = session.status;
        session_engine::evaluate_state(&mut session, Utc::now());
        if session.status != before {
            self.db.save_session(&session).map_err(|e| e.to_string())?;
            let event = match session.status {
                FocusSessionStatus::Completed => Some("SESSION_COMPLETED"),
                FocusSessionStatus::Expired => Some("SESSION_EXPIRED"),
                _ => None,
            };
            if let Some(event) = event {
                self.db
                    .record_event(&session.id, event, None)
                    .map_err(|e| e.to_string())?;
            }
            if !session_engine::is_active(&session, Utc::now()) {
                self.enforcer.clear()?;
                self.session_enforced = false;
                self.unlock = None;
                self.shield = None;
                self.shield_native = None;
            }
        }
        Ok(Some(session))
    }

    // ---- Snapshot ----------------------------------------------------

    pub fn snapshot(&mut self) -> Result<AppStateDto, String> {
        let session = self.recover()?;

        // While inside the commitment grace window, refresh the cached status
        // each snapshot so the "Undo (Ns)" countdown ticks and the button
        // disappears on time. Bounded to the ~60s grace; outside it the 30s
        // tick refresh is enough (avoids a pipe round-trip on every 500ms poll).
        if self.commitment.as_ref().is_some_and(|c| c.can_cancel_now) {
            self.refresh_commitment();
        }
        let profiles = self.db.list_profiles().map_err(|e| e.to_string())?;
        let profile_dtos = profiles.iter().map(profile_to_dto).collect();

        let session_dto = match &session {
            Some(s) if session_engine::is_active(s, Utc::now()) => {
                let profile = profiles.iter().find(|p| p.policy.id == s.policy_id);
                let profile_name = profile.map(|p| p.policy.name.clone()).unwrap_or_default();
                let allowlist = profile.is_some_and(|p| p.policy.mode == PolicyMode::Allowlist);
                Some(session_to_dto(s, &profile_name, allowlist))
            }
            _ => None,
        };

        let unlock_dto = match (&self.unlock, &session) {
            (Some(flow), Some(s)) => Some(unlock_to_dto(flow, s)),
            _ => None,
        };

        let stats = self.compute_stats()?;

        // Only mutated on Windows (below) — the cfg_attr avoids an
        // unused-mut warning when compiling this file's non-Windows path.
        #[cfg_attr(not(windows), allow(unused_mut))]
        let mut health = self.enforcer.health();
        // app_blocker is a separate Windows-only module (a Win32 foreground
        // hook in the Tauri process), not part of the pipe-connected
        // service `self.enforcer` talks to, so its health is layered in
        // here rather than guessed at inside any `Enforcer` impl.
        #[cfg(windows)]
        {
            health.app_blocking = if crate::app_blocker::is_active() {
                "active"
            } else {
                "unavailable"
            }
            .to_string();
        }
        // The old "browser extensions" layer was a placeholder for a feature that never shipped; this row
        // says whether the address check can read the browser, which is what the feed rules rely on.
        health.layers.retain(|l| l.name != "extensions");
        health.layers.push(crate::dto::DeviceLayerDto {
            name: "feeds".to_string(),
            #[cfg(windows)]
            state: if crate::browser_guard::can_read() {
                "ok"
            } else {
                "cantRead"
            }
            .to_string(),
            #[cfg(not(windows))]
            state: "devMode".to_string(),
            #[cfg(windows)]
            healthy: crate::browser_guard::can_read(),
            #[cfg(not(windows))]
            healthy: false,
        });

        let bedtime = bedtime_to_dto(&self.db.get_bedtime().map_err(|e| e.to_string())?);

        let now = Utc::now();
        let cheat = self.cheat();
        let paused = cheat.as_ref().is_some_and(|c| c.is_active(now));
        let session_dto = session_dto.map(|mut s| {
            s.paused = paused;
            s
        });
        // How many passes the Shield can offer: an app block inside a running Normal or Strict session.
        let shield = self.shield.clone().map(|mut sh| {
            sh.passes_left = match (&session, &self.shield_native) {
                (Some(s), Some(_))
                    if session_engine::is_active(s, now) && sh.target_kind == "app" =>
                {
                    passes::passes_left(
                        s.enforcement_mode,
                        s.session_type,
                        self.passes_used_for(&s.id),
                    )
                }
                _ => 0,
            };
            sh
        });
        let schedules = self
            .db
            .list_schedules()
            .map_err(|e| e.to_string())?
            .iter()
            .map(schedule_to_dto)
            .collect();
        let limits = self.limit_dtos(now);
        let cheat_options = cheat_day::options(now, &Local, cheat.as_ref(), 14)
            .into_iter()
            .map(|d| d.to_rfc3339())
            .collect();

        Ok(AppStateDto {
            language: self.language(),
            profiles: profile_dtos,
            session: session_dto,
            unlock: unlock_dto,
            shield,
            health,
            stats,
            commitment: self.commitment.as_ref().map(commitment_to_dto),
            bedtime,
            schedules,
            cheat_day: cheat.as_ref().map(|c| cheat_to_dto(c, now)),
            limits,
            cheat_options,
            sync: self.sync_status.clone(),
        })
    }

    fn passes_used_for(&self, session_id: &str) -> usize {
        match &self.passes_used {
            (Some(id), n) if id == session_id => *n,
            _ => 0,
        }
    }

    /// Real Stats numbers, sourced from focus-only session rows (the DB query
    /// excludes bedtime) so nightly wind-down sessions never inflate focused
    /// time, completion, or streak. Today's figures and the weekly view are all
    /// computed here through `now_focus_core::history_stats`. Local-time day
    /// boundaries so "today"/"this week" match the user's calendar.
    fn compute_stats(&self) -> Result<StatsDto, String> {
        let now = Utc::now();
        let today = Local::now().date_naive();
        let monday = history_stats::monday_of(today);

        let today_start = local_midnight_utc(today);
        let week_start = local_midnight_utc(monday);
        let week_end = local_midnight_utc(monday + ChronoDuration::days(7));
        let streak_start = local_midnight_utc(today - ChronoDuration::days(60));

        let week_sessions = self
            .db
            .focus_sessions_between(week_start, week_end)
            .map_err(|e| e.to_string())?;
        let streak_sessions = self
            .db
            .focus_sessions_between(streak_start, now)
            .map_err(|e| e.to_string())?;
        let week_events: Vec<BlockEvent> = self
            .db
            .block_events_between(week_start, now)
            .map_err(|e| e.to_string())?
            .into_iter()
            .map(|(occurred_at, meta)| BlockEvent {
                occurred_at,
                target: parse_block_target(meta.as_deref()),
            })
            .collect();

        let today_sessions: Vec<FocusSession> = week_sessions
            .iter()
            .filter(|s| s.start_at >= today_start)
            .cloned()
            .collect();
        let today_block_attempts = week_events
            .iter()
            .filter(|e| e.occurred_at >= today_start)
            .count() as i64;

        let streak_days = history_stats::current_streak_days(&streak_sessions, today, &Local);
        let focus_score = history_stats::focus_score(&week_sessions, &Local);
        Ok(StatsDto {
            focus_score,
            week_minutes_total: history_stats::total_focused_minutes(&week_sessions),
            week_sessions: history_stats::sessions_count(&week_sessions) as i64,
            week_completed: history_stats::completed_count(&week_sessions) as i64,
            week_turned_away: week_events.len() as i64,
            today_minutes: history_stats::total_focused_minutes(&today_sessions),
            sessions_completed: history_stats::completed_count(&today_sessions) as i64,
            sessions_started: today_sessions.len() as i64,
            block_attempts_today: today_block_attempts,
            week_minutes: history_stats::week_buckets_minutes(&week_sessions, monday, &Local)
                .to_vec(),
            streak_days,
            completion_rate: history_stats::completion_rate(&week_sessions),
            top_targets: history_stats::top_targets(&week_events, 3)
                .into_iter()
                .map(|(name, count)| TargetCountDto {
                    name,
                    count: count as i64,
                })
                .collect(),
        })
    }

    // ---- Profiles ------------------------------------------------------

    /// The mode is fixed here: a blocklist and an allowlist hold the same kind of rows with opposite meaning.
    pub fn create_profile(&mut self, name: String, mode: PolicyMode) -> Result<(), String> {
        let profile = Profile::with_mode(name, mode);
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    pub fn rename_profile(&mut self, profile_id: &str, name: String) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        profile.policy.name = name;
        self.save_edited(profile)
    }

    /// Returns `Err` with mockup-matching copy for an invalid or duplicate
    /// domain ("That doesn't look like a website..." / "already on the
    /// list") rather than a raw validation error — this becomes the
    /// `domErr` field the Profiles screen shows inline.
    pub fn add_domain(&mut self, profile_id: &str, raw: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        if profile.policy.mode == PolicyMode::Allowlist {
            return Err("whitelistNoSites".to_string());
        }
        let Some(domain) = domain_validation::normalize(raw) else {
            return Err("badDomain".to_string());
        };
        if profile.policy.domains.iter().any(|d| d.domain == domain) {
            return Err(format!("listed|{domain}"));
        }
        profile.policy.domains.push(DomainRule::new(domain));
        self.save_edited(profile.clone())?;
        self.reapply_if_active(profile_id, &profile)
    }

    pub fn remove_domain(&mut self, profile_id: &str, rule_id: &str) -> Result<(), String> {
        self.reject_if_active(profile_id)?;
        let mut profile = self.require_profile(profile_id)?;
        profile.policy.domains.retain(|d| d.id != rule_id);
        self.save_edited(profile)
    }

    /// `native_identifier`/`display_name` come from a real OS file picker on
    /// the frontend (Tauri's dialog plugin), not a hardcoded pool like the
    /// mockup's `APPS` table.
    pub fn add_application(
        &mut self,
        profile_id: &str,
        native_identifier: String,
        display_name: String,
    ) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        // The rules are what stays open, so allowing one more app is the edit that weakens a running session.
        if profile.policy.mode == PolicyMode::Allowlist && self.session_active_on(profile_id)? {
            return Err("cantAllowMore".to_string());
        }
        if profile
            .policy
            .applications
            .iter()
            .any(|a| a.native_identifier == native_identifier)
        {
            return Err(format!("listed|{display_name}"));
        }
        profile
            .policy
            .applications
            .push(ApplicationRule::new(native_identifier, display_name));
        self.save_edited(profile)
    }

    pub fn remove_application(&mut self, profile_id: &str, rule_id: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        if profile.policy.mode == PolicyMode::Blocklist {
            self.reject_if_active(profile_id)?;
        }
        profile.policy.applications.retain(|a| a.id != rule_id);
        // Narrowing a running allowlist is fine, but its last app going would leave nothing to enforce.
        if !allowlist::enforces_here(&profile.policy) && self.session_active_on(profile_id)? {
            return Err("cantRemoveLast".to_string());
        }
        self.save_edited(profile)
    }

    pub fn toggle_feed(&mut self, profile_id: &str, feed_key: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        if let Some(rule) = profile
            .feed_rules
            .iter_mut()
            .find(|f| f.feed_key == feed_key)
        {
            // Switching a rule off loosens a running session, like removing a site or an app.
            if rule.enabled {
                self.reject_if_active(profile_id)?;
            }
            rule.enabled = !rule.enabled;
        } else {
            profile.feed_rules.push(FeedRule {
                id: uuid::Uuid::new_v4().to_string(),
                profile_id: profile_id.to_string(),
                feed_key: feed_key.to_string(),
                enabled: true,
            });
        }
        self.save_edited(profile)
    }

    /// Every user edit goes through here so `updated_at` moves: sync uses it as the time of the change.
    fn save_edited(&mut self, mut profile: Profile) -> Result<(), String> {
        profile.policy.updated_at = Utc::now();
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    fn require_profile(&self, profile_id: &str) -> Result<Profile, String> {
        self.db
            .get_profile(profile_id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "That profile no longer exists".to_string())
    }

    /// If a session is currently active on `profile_id`, return `Err` —
    /// removals/disabling of blocks are refused while the session is running.
    fn reject_if_active(&mut self, profile_id: &str) -> Result<(), String> {
        if self.session_active_on(profile_id)? {
            return Err("cantRemove".to_string());
        }
        Ok(())
    }

    fn session_active_on(&mut self, profile_id: &str) -> Result<bool, String> {
        Ok(self.recover()?.is_some_and(|s| {
            s.policy_id == profile_id && session_engine::is_active(&s, Utc::now())
        }))
    }

    /// If a session is currently active on `profile_id`, re-push the given
    /// profile's policy to the enforcer so the addition takes effect
    /// immediately (idempotent — the policy only grew).
    fn reapply_if_active(&mut self, profile_id: &str, profile: &Profile) -> Result<(), String> {
        if let Some(session) = self.recover()? {
            if session.policy_id == profile_id
                && session_engine::is_active(&session, Utc::now())
                && self.session_enforced
            {
                self.enforcer.apply(&profile.policy)?;
            }
        }
        Ok(())
    }

    // ---- Sessions ------------------------------------------------------

    pub fn start_session(
        &mut self,
        profile_id: &str,
        duration_minutes: i64,
        mode: &str,
    ) -> Result<(), String> {
        if self
            .recover()?
            .is_some_and(|s| session_engine::is_active(&s, Utc::now()))
        {
            return Err("running".to_string());
        }
        let profile = self.require_profile(profile_id)?;
        if !allowlist::enforces_here(&profile.policy) {
            return Err("allowOne".to_string());
        }
        let mode = parse_mode(mode)?;
        let now = Utc::now();
        let mut session = FocusSession::new(
            profile_id,
            now,
            now + ChronoDuration::minutes(duration_minutes),
            &self.device_id,
        );
        session.enforcement_mode = mode;

        // A session started on a cheat day runs paused: nothing is blocked until the day ends.
        let paused = self.cheat_active(now);
        if !paused {
            self.enforcer.apply(&profile.policy)?;
        }
        self.session_enforced = !paused;
        self.db.save_session(&session).map_err(|e| e.to_string())?;
        self.db
            .record_event(&session.id, "SESSION_STARTED", None)
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    // ---- Cheat day -----------------------------------------------------------

    fn cheat(&self) -> Option<CheatDay> {
        self.db.get_cheat_day().ok().flatten()
    }

    fn cheat_active(&self, now: DateTime<Utc>) -> bool {
        self.cheat().is_some_and(|c| c.is_active(now))
    }

    /// Plan a day off from blocking (see `now_focus_core::cheat_day` for the rules). `day_start` is one of the
    /// instants `snapshot().cheat_options` offered, as RFC3339.
    pub fn schedule_cheat_day(&mut self, day_start: &str) -> Result<(), String> {
        let day = DateTime::parse_from_rfc3339(day_start)
            .map_err(|_| "notDay".to_string())?
            .with_timezone(&Utc);
        let now = Utc::now();
        if !cheat_day::can_schedule(now, day, self.cheat().as_ref(), &Local) {
            return Err("cheatRule".to_string());
        }
        self.db
            .set_cheat_day(Some(&cheat_day::for_day(day, now, &Local)))
            .map_err(|e| e.to_string())
    }

    pub fn cancel_cheat_day(&mut self) -> Result<(), String> {
        if let Some(current) = self.cheat() {
            self.db
                .set_cheat_day(cheat_day::cancel(&current, Utc::now()).as_ref())
                .map_err(|e| e.to_string())?;
        }
        self.reconcile_enforcement();
        Ok(())
    }

    /// Brings the enforcer in line with now: a running session is applied unless a cheat day pauses it, and
    /// lifted while one does. Runs on the 30s tick and after cheat-day changes, so a session also comes back
    /// when the day ends, and after the app restarts mid-session. The service-held commitment is untouched.
    pub fn reconcile_enforcement(&mut self) {
        let now = Utc::now();
        let Some(session) = self.recover().ok().flatten() else {
            return;
        };
        let want = session_engine::is_active(&session, now) && !self.cheat_active(now);
        if want && !self.session_enforced {
            if let Ok(Some(profile)) = self.db.get_profile(&session.policy_id) {
                if self.enforcer.apply(&profile.policy).is_ok() {
                    self.session_enforced = true;
                }
            }
        } else if !want && self.session_enforced && self.enforcer.clear().is_ok() {
            self.session_enforced = false;
        }
    }

    // ---- Schedules -----------------------------------------------------------

    pub fn save_schedule(&mut self, s: ScheduleDto) -> Result<(), String> {
        let mode = parse_mode(&s.mode)?;
        if s.name.trim().is_empty() || s.days.is_empty() {
            return Err("scheduleNeeds".to_string());
        }
        self.require_profile(&s.policy_id)
            .map_err(|_| "scheduleProfile".to_string())?;
        let id = if s.id.is_empty() {
            uuid::Uuid::new_v4().to_string()
        } else {
            s.id
        };
        self.db
            .save_schedule(&Schedule {
                id,
                name: s.name.trim().to_string(),
                days: s.days.into_iter().filter(|d| *d < 7).collect(),
                start_minute: s.start_minute.clamp(0, 1439),
                end_minute: s.end_minute.clamp(0, 1439),
                policy_id: s.policy_id,
                mode,
                enabled: s.enabled,
            })
            .map_err(|e| e.to_string())
    }

    pub fn delete_schedule(&mut self, id: &str) -> Result<(), String> {
        self.db.delete_schedule(id).map_err(|e| e.to_string())
    }

    /// Runs on the 30s tick: starts the session a recurring schedule says should be running now. Nothing
    /// starts by itself on a cheat day, while another session runs, or for a window already begun (even if
    /// the user ended it early).
    pub fn schedule_tick(&mut self) {
        let now = Utc::now();
        if self.cheat_active(now) {
            return;
        }
        let Ok(schedules) = self.db.list_schedules() else {
            return;
        };
        let Some(due) = schedule::current(&schedules, now, &Local) else {
            return;
        };
        if self.db.schedule_run(&due.schedule_id).ok().flatten() == Some(due.start) {
            return;
        }
        let running = self
            .recover()
            .ok()
            .flatten()
            .is_some_and(|s| session_engine::is_active(&s, now));
        if running {
            return;
        }
        let Ok(Some(profile)) = self.db.get_profile(&due.policy_id) else {
            return;
        };
        // An allowlist with no app for this PC (it was built on another platform) has nothing to enforce here.
        if !allowlist::enforces_here(&profile.policy) {
            return;
        }

        let mut session = FocusSession::new(&profile.policy.id, now, due.end, &self.device_id);
        session.enforcement_mode = due.mode;
        session.origin = SessionOrigin::Schedule;
        // Enforce first; only persist the session if enforcement took (like Bedtime).
        if self.enforcer.apply(&profile.policy).is_ok() {
            self.session_enforced = true;
            let _ = self.db.note_schedule_run(&due.schedule_id, due.start);
            let _ = self.db.save_session(&session);
            let _ = self.db.record_event(&session.id, "SESSION_STARTED", None);
        }
    }

    // ---- Passes -------------------------------------------------------------

    /// "Open <app> for 5 min": lets the app the Shield just closed stay open, in a Normal or Strict session,
    /// twice per session. Not a cancel, so it never touches the unlock flow. Apps only: a hosts-file block
    /// can't be lifted for one site without lifting it for all.
    pub fn use_pass(&mut self) -> Result<(), String> {
        let now = Utc::now();
        let session = self
            .recover()?
            .filter(|s| session_engine::is_active(s, now))
            .ok_or("noSession")?;
        let native = self
            .shield_native
            .clone()
            .ok_or("There's nothing to open")?;
        let used = self.passes_used_for(&session.id);
        if passes::passes_left(session.enforcement_mode, session.session_type, used) == 0 {
            return Err("noPasses".to_string());
        }
        self.passes.retain(|(_, until)| *until > now);
        self.passes.push((
            native.to_lowercase(),
            now + ChronoDuration::seconds(passes::DURATION_SECS),
        ));
        self.passes_used = (Some(session.id), used + 1);
        self.shield = None;
        self.shield_native = None;
        Ok(())
    }

    /// Normal mode only — the frontend shouldn't offer this button in
    /// strict/locked mode, but the backend re-checks anyway (the same
    /// "don't trust the caller already validated it" rule domain validation
    /// follows at the enforcement boundary).
    pub fn end_session_normal(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("noSession")?;
        if session.enforcement_mode != EnforcementMode::Normal {
            return Err("notNormal".to_string());
        }
        self.finish_session(session, "SESSION_CANCELLED")
    }

    fn finish_session(&mut self, mut session: FocusSession, event: &str) -> Result<(), String> {
        session.status = FocusSessionStatus::Cancelled;
        session.cancelled_at = Some(Utc::now());
        self.enforcer.clear()?;
        self.session_enforced = false;
        self.db.save_session(&session).map_err(|e| e.to_string())?;
        self.db
            .record_event(&session.id, event, None)
            .map_err(|e| e.to_string())?;
        self.unlock = None;
        self.shield = None;
        self.shield_native = None;
        Ok(())
    }

    // ---- Unlock flow (Strict/Locked "end early") ------------------------

    pub fn begin_unlock(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("noSession")?;
        if session.enforcement_mode == EnforcementMode::Normal {
            return self.end_session_normal();
        }
        self.unlock = Some(UnlockFlow {
            typed: String::new(),
            wait_started_at: None,
        });
        // The shield has no unlock UI and covers every screen: step aside so
        // the flow (rendered in the main window) is reachable.
        self.shield = None;
        Ok(())
    }

    pub fn cancel_unlock(&mut self) {
        self.unlock = None;
    }

    pub fn update_unlock_text(&mut self, typed: String) -> Result<(), String> {
        let flow = self.unlock.as_mut().ok_or("Not in the unlock flow")?;
        flow.typed = typed;
        Ok(())
    }

    /// Only reachable once the typed sentence matches exactly — mirrors the
    /// mockup's `unlockDisabled` gating on the "Start Ns pause" button.
    pub fn start_unlock_wait(&mut self) -> Result<(), String> {
        let flow = self.unlock.as_mut().ok_or("Not in the unlock flow")?;
        if !unlock_matches(&flow.typed) {
            return Err("keepMatching".to_string());
        }
        flow.wait_started_at = Some(Utc::now());
        Ok(())
    }

    /// Only reachable once the wait has actually elapsed — the backend, not
    /// just a disabled frontend button, is what enforces that a locked
    /// session can't be ended early at all (checked via `enforcement_mode`).
    pub fn confirm_unlock(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("noSession")?;
        if session.enforcement_mode == EnforcementMode::Locked {
            return Err("lockedUntilEnd".to_string());
        }
        let flow = self.unlock.as_ref().ok_or("Not in the unlock flow")?;
        let started = flow.wait_started_at.ok_or("The pause hasn't started yet")?;
        if Utc::now() - started < UNLOCK_WAIT {
            return Err("pauseNotOver".to_string());
        }
        self.finish_session(session, "SESSION_CANCELLED")
    }

    // ---- Shield (blocked-app/site overlay) ------------------------------

    fn record_block_attempt(
        &mut self,
        target_kind: String,
        target_name: String,
    ) -> Result<(), String> {
        let session = self.recover()?.ok_or("noSession")?;
        self.shield = Some(ShieldDto {
            target_kind: target_kind.clone(),
            target_name: target_name.clone(),
            passes_left: 0, // filled in per snapshot
            limit_minutes: 0,
        });
        self.shield_native = None; // only a real app detection (check_foreground_app) sets it

        // 3s dedup per target — a single "try to open it" gesture can fire
        // several foreground-change events (mirrors macOS/Android
        // shouldLogBlockEvent). The shield still shows above; we just don't
        // log a second event. The target name goes into metadata so Stats can
        // rank "most turned away".
        let now = Utc::now();
        let recent = self
            .last_block_at
            .get(&target_name)
            .is_some_and(|t| (now - *t).num_seconds() < 3);
        if recent {
            return Ok(());
        }
        self.last_block_at.insert(target_name.clone(), now);

        let metadata = serde_json::json!({ "kind": target_kind, "name": target_name }).to_string();
        self.db
            .record_event(&session.id, "BLOCK_ATTEMPT", Some(&metadata))
            .map_err(|e| e.to_string())
    }

    /// Dev-only affordance for exercising the Shield screen without the
    /// real Win32 foreground hook (see `check_foreground_app`, which calls
    /// the same `record_block_attempt` path for a real detection).
    pub fn simulate_block(
        &mut self,
        target_kind: String,
        target_name: String,
    ) -> Result<(), String> {
        self.record_block_attempt(target_kind, target_name)
    }

    /// Called by `app_blocker`'s Win32 foreground-window hook on every
    /// foreground change (Windows-only in practice, but kept
    /// platform-neutral here since it's pure policy lookup — no Win32
    /// calls). `exe_path` is the resolved image path of whatever process
    /// just became the foreground window. Returns the matched app's display
    /// name if it should be closed, so the caller knows whether to post
    /// `WM_CLOSE` — this function only records the attempt and arms the
    /// Shield screen, it never touches a window handle itself.
    #[cfg_attr(not(windows), allow(dead_code))]
    pub fn check_foreground_app(&mut self, exe_path: &str) -> Option<String> {
        let now = Utc::now();
        self.note_foreground(exe_path, now);
        self.check_session_block(exe_path)
            .or_else(|| self.check_limit(exe_path, now))
    }

    fn check_session_block(&mut self, exe_path: &str) -> Option<String> {
        let session = self.recover().ok()??;
        let now = Utc::now();
        if !session_engine::is_active(&session, now) || self.cheat_active(now) {
            return None;
        }
        // An app the user was just given a pass for stays open until the pass ends.
        if self
            .passes
            .iter()
            .any(|(p, until)| *until > now && allowlist::same_exe(p, exe_path))
        {
            return None;
        }
        let profile = self.db.get_profile(&session.policy_id).ok()??;
        if profile.policy.mode == PolicyMode::Allowlist {
            if !allowlist::closes(
                &profile.policy,
                exe_path,
                own_exe().as_deref(),
                &system_root(),
            ) {
                return None;
            }
            // No rule names an app the list doesn't have, so the Shield and a pass go by the exe itself.
            let display_name = allowlist::display_name(exe_path);
            let _ = self.record_block_attempt("app".to_string(), display_name.clone());
            self.shield_native = Some(exe_path.to_string());
            return Some(display_name);
        }
        let matched = profile
            .policy
            .applications
            .iter()
            .find(|a| a.enabled && allowlist::same_exe(&a.native_identifier, exe_path))?;
        let display_name = matched.display_name.clone();
        let native = matched.native_identifier.clone();
        let _ = self.record_block_attempt("app".to_string(), display_name.clone());
        self.shield_native = Some(native);
        Some(display_name)
    }

    /// The address-checked feed rules (`feed_url::URL_FEEDS`) switched on for the session running now. Empty
    /// outside a session and on a cheat day. Feed rules apply to allowlist profiles too (WIRE_FORMAT.md, section 4).
    #[cfg_attr(not(windows), allow(dead_code))]
    pub fn live_feeds(&mut self) -> Vec<String> {
        let Some(session) = self.recover().ok().flatten() else {
            return Vec::new();
        };
        let now = Utc::now();
        if !session_engine::is_active(&session, now) || self.cheat_active(now) {
            return Vec::new();
        }
        let Some(profile) = self.db.get_profile(&session.policy_id).ok().flatten() else {
            return Vec::new();
        };
        profile
            .feed_rules
            .iter()
            .filter(|f| f.enabled && URL_FEEDS.contains(&f.feed_key.as_str()))
            .map(|f| f.feed_key.clone())
            .collect()
    }

    /// Logs that a tab on `feed_key`'s page was closed. Unlike an app block this shows no Shield: the page is
    /// gone and the browser is still theirs. Only the rule's label is kept, never the address.
    #[cfg_attr(not(windows), allow(dead_code))]
    pub fn record_feed_block(&mut self, feed_key: &str) {
        let Some(label) = FEED_DEFS
            .iter()
            .find(|(k, _, _)| *k == feed_key)
            .map(|(_, l, _)| *l)
        else {
            return;
        };
        let Ok(Some(session)) = self.recover() else {
            return;
        };
        let metadata = serde_json::json!({ "kind": "feed", "name": label }).to_string();
        let _ = self
            .db
            .record_event(&session.id, "BLOCK_ATTEMPT", Some(&metadata));
    }

    // ---- Daily limits ---------------------------------------------------------

    /// Credits the time since the last note to the limited app that was in front, then notes `exe` as in
    /// front now. Runs on every foreground change and on the 30s recheck, so a stall (sleep, a missed
    /// tick) credits at most `LIMIT_MAX_GAP_SECS`.
    /// ponytail: counts an idle PC with the app still in front; add GetLastInputInfo if that over-counts.
    fn note_foreground(&mut self, exe: &str, now: DateTime<Utc>) {
        let ms = daily_limit::credit_ms(
            self.limit_tick.as_ref(),
            now,
            ChronoDuration::seconds(LIMIT_MAX_GAP_SECS),
        );
        if let Some(daily_limit::Tick { key: Some(k), .. }) = &self.limit_tick {
            if ms > 0 {
                let _ = self
                    .db
                    .add_limit_usage(k, &daily_limit::day_key(now, &Local), ms);
            }
        }
        let key = self
            .db
            .list_limits()
            .ok()
            .and_then(|ls| daily_limit::limit_for_exe(&ls, exe).map(|l| l.key.clone()));
        self.limit_tick = Some(daily_limit::Tick { key, at: now });
    }

    /// Arms the Shield and returns the label when `exe` has used up its limit today (paused on a cheat day).
    fn check_limit(&mut self, exe: &str, now: DateTime<Utc>) -> Option<String> {
        if self.cheat_active(now) {
            return None;
        }
        let limits = self.db.list_limits().ok()?;
        let l = daily_limit::limit_for_exe(&limits, exe)?;
        let allowed = l.limit_ms_at(now);
        let used = self
            .db
            .limit_used_ms(&l.key, &daily_limit::day_key(now, &Local))
            .ok()?;
        if allowed == 0 || used < allowed {
            return None;
        }
        self.shield = Some(ShieldDto {
            target_kind: "limit".to_string(),
            target_name: l.label.clone(),
            passes_left: 0,
            limit_minutes: (allowed / 60_000) as u32,
        });
        self.shield_native = None;
        Some(l.label.clone())
    }

    /// `minutes` 0 removes the limit (at once, or from midnight if it is already used up); tighter applies now,
    /// raising waits for midnight.
    pub fn set_limit(&mut self, key: &str, label: &str, minutes: u32) -> Result<(), String> {
        if minutes != 0 && !daily_limit::MINUTE_CHOICES.contains(&minutes) {
            return Err("pickLimit".to_string());
        }
        let now = Utc::now();
        let existing = self
            .db
            .list_limits()
            .map_err(|e| e.to_string())?
            .into_iter()
            .find(|l| l.key == key);
        let next = match existing {
            Some(l) => {
                let used = self
                    .db
                    .limit_used_ms(key, &daily_limit::day_key(now, &Local))
                    .unwrap_or(0);
                l.with_minutes(minutes, now, &Local, used)
            }
            None if minutes == 0 => return Ok(()),
            None => DailyLimit::new(key, label, minutes),
        };
        let saved = match next.settled(now) {
            Some(l) => self.db.save_limit(&l),
            None => self.db.delete_limit(key),
        };
        saved.map_err(|e| e.to_string())
    }

    fn limit_dtos(&self, now: DateTime<Utc>) -> Vec<LimitDto> {
        let day = daily_limit::day_key(now, &Local);
        self.db
            .list_limits()
            .unwrap_or_default()
            .into_iter()
            .filter(|l| l.clone().settled(now).is_some())
            .map(|l| {
                let minutes = l.minutes_at(now);
                let used = self.db.limit_used_ms(&l.key, &day).unwrap_or(0);
                let pending = match (l.pending_minutes, l.pending_from) {
                    (Some(m), Some(from)) if from > now => Some(m),
                    _ => None,
                };
                LimitDto {
                    is_site: l.is_site(),
                    used_up: minutes > 0 && used >= minutes as i64 * 60_000,
                    used_minutes: used / 60_000,
                    key: l.key,
                    label: l.label,
                    minutes,
                    pending,
                }
            })
            .collect()
    }

    pub fn dismiss_shield(&mut self) {
        self.shield = None;
    }

    // ---- Commitment (14-day shield, service-owned) ----------------------

    /// Start (or replace) the commitment. Domains are normalized here and
    /// re-validated again at the service's write boundary. The 60s grace and
    /// all time-anchoring live in the service; this only relays.
    pub fn start_commitment(&mut self, domains: Vec<String>) -> Result<(), String> {
        let clean: Vec<String> = domains
            .iter()
            .filter_map(|d| domain_validation::normalize(d))
            .collect();
        if clean.is_empty() {
            return Err("needSite".to_string());
        }
        self.enforcer.apply_commitment(&clean)?;
        self.commitment = self.enforcer.commitment_status();
        Ok(())
    }

    /// Ask the service to clear the commitment. Succeeds only inside the grace
    /// window; otherwise the service's refusal message comes back as `Err`.
    pub fn clear_commitment(&mut self) -> Result<(), String> {
        self.enforcer.clear_commitment()?;
        self.commitment = self.enforcer.commitment_status();
        Ok(())
    }

    /// Refresh the cached commitment status (called from the 30s tick).
    pub fn refresh_commitment(&mut self) {
        self.commitment = self.enforcer.commitment_status();
    }

    // ---- Bedtime --------------------------------------------------------

    pub fn set_bedtime(
        &mut self,
        enabled: bool,
        wind_down_minute: i64,
        sleep_minute: i64,
        wake_minute: i64,
        lock_at_sleep: bool,
        policy_id: Option<String>,
    ) -> Result<(), String> {
        let settings = BedtimeSettings {
            enabled,
            wind_down_minute,
            sleep_minute,
            wake_minute,
            lock_at_sleep,
            policy_id,
        };
        self.db.set_bedtime(&settings).map_err(|e| e.to_string())?;
        // The time of a user edit: sync's last-write-wins uses it.
        self.db
            .kv_set(KV_BEDTIME_UPDATED, &Utc::now().to_rfc3339())
            .map_err(|e| e.to_string())
    }

    /// Runs on the 30s tick. Starts a `LOCKED` bedtime session for the chosen
    /// profile when inside the window and nothing else is active (reusing the
    /// normal session machinery, like macOS's BedtimeScheduler), and returns
    /// whether the caller should lock the screen now (the caller does the
    /// Win32 `LockWorkStation` — this stays platform-neutral). The
    /// in-window/sleep-moment/debounce decision is the unit-tested
    /// `bedtime_schedule::tick_decision`.
    pub fn bedtime_tick(&mut self) -> bool {
        let settings = match self.db.get_bedtime() {
            Ok(s) => s,
            Err(_) => return false,
        };
        if !settings.enabled {
            return false;
        }

        let now = Utc::now();
        // Nothing starts by itself on a cheat day, and the screen isn't locked at sleep time.
        if self.cheat_active(now) {
            return false;
        }
        let session = self.recover().ok().flatten();
        let active = session
            .as_ref()
            .is_some_and(|s| session_engine::is_active(s, now));
        let secs_since_lock = self.last_sleep_lock_at.map(|t| (now - t).num_seconds());

        let decision = bedtime_schedule::tick_decision(
            &settings,
            Local::now().naive_local(),
            active,
            secs_since_lock,
        );

        if let Some((start, end)) = decision.start_window {
            if let Some(policy_id) = &settings.policy_id {
                if let Some(profile) = self
                    .db
                    .get_profile(policy_id)
                    .ok()
                    .flatten()
                    .filter(|p| allowlist::enforces_here(&p.policy))
                {
                    let mut bedtime = FocusSession::new(
                        &profile.policy.id,
                        local_naive_to_utc(start),
                        local_naive_to_utc(end),
                        &self.device_id,
                    );
                    bedtime.session_type = SessionType::BedtimeWinddown;
                    bedtime.enforcement_mode = EnforcementMode::Locked;
                    // Enforce first; only persist the session if enforcement took.
                    if self.enforcer.apply(&profile.policy).is_ok() {
                        self.session_enforced = true;
                        let _ = self.db.save_session(&bedtime);
                        let _ = self.db.record_event(&bedtime.id, "SESSION_STARTED", None);
                    }
                }
            }
        }

        if decision.lock_now {
            self.last_sleep_lock_at = Some(now);
        }
        decision.lock_now
    }
}

const KV_SYNC_STATE: &str = "sync_state";
const KV_BEDTIME_UPDATED: &str = "bedtime_updated_at";
const KV_JOIN_REMOTE: &str = "join_remote";
const KV_LANGUAGE: &str = "language";

/// What the sync crate needs from the app (see `sync_glue`). Kept together so the boundary is easy to see:
/// everything here either reads local data or applies something another device decided.
impl AppState {
    pub fn set_sync_status(&mut self, status: SyncStatusDto) {
        self.sync_status = status;
    }

    pub fn join_remote(&self) -> bool {
        self.db
            .kv_get(KV_JOIN_REMOTE)
            .ok()
            .flatten()
            .map(|v| v != "off")
            .unwrap_or(true)
    }

    pub fn set_join_remote(&mut self, on: bool) -> Result<(), String> {
        self.db
            .kv_set(KV_JOIN_REMOTE, if on { "on" } else { "off" })
            .map_err(|e| e.to_string())
    }

    /// "system" (follow Windows), "en" or "ar". This device only: it is not part of the synced data.
    pub fn language(&self) -> String {
        self.db
            .kv_get(KV_LANGUAGE)
            .ok()
            .flatten()
            .filter(|v| matches!(v.as_str(), "en" | "ar"))
            .unwrap_or_else(|| "system".to_string())
    }

    pub fn set_language(&mut self, language: &str) -> Result<(), String> {
        if !matches!(language, "system" | "en" | "ar") {
            return Err(format!("Unknown language: {language}"));
        }
        self.db
            .kv_set(KV_LANGUAGE, language)
            .map_err(|e| e.to_string())
    }

    pub fn device_name(&self) -> String {
        std::env::var("COMPUTERNAME")
            .ok()
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| "Windows PC".to_string())
    }

    pub fn kv_get(&self, key: &str) -> Result<Option<String>, String> {
        self.db.kv_get(key).map_err(|e| e.to_string())
    }

    pub fn kv_set(&mut self, key: &str, value: &str) -> Result<(), String> {
        self.db.kv_set(key, value).map_err(|e| e.to_string())
    }

    pub fn sync_read_local(&self) -> Result<now_focus_sync::model::Local, String> {
        Ok(now_focus_sync::model::Local {
            profiles: self.db.list_profiles().map_err(|e| e.to_string())?,
            bedtime: self.db.get_bedtime().map_err(|e| e.to_string())?,
            bedtime_updated_at: self
                .db
                .kv_get(KV_BEDTIME_UPDATED)
                .ok()
                .flatten()
                .and_then(|t| DateTime::parse_from_rfc3339(&t).ok())
                .map(|t| t.with_timezone(&Utc)),
            state: self
                .db
                .kv_get(KV_SYNC_STATE)
                .ok()
                .flatten()
                .and_then(|t| serde_json::from_str(&t).ok())
                .unwrap_or_default(),
        })
    }

    /// Writes only what `after` changed from `before`: server data is applied here, not stamped as a user edit.
    pub fn sync_write_local(
        &mut self,
        before: &now_focus_sync::model::Local,
        after: &now_focus_sync::model::Local,
    ) -> Result<(), String> {
        let json = |p: &Profile| serde_json::to_string(p).unwrap_or_default();
        // The profile a running session enforces is never changed or deleted by a pull (a remote edit could lift
        // the block). The local copy is kept and stamped as the newer edit, so the next push wins it back.
        let in_use = self.running_session_policy_id();
        let is_in_use = |id: &str| {
            in_use
                .as_deref()
                .is_some_and(|u| u.eq_ignore_ascii_case(id))
        };
        for p in &after.profiles {
            let old = before.profiles.iter().find(|b| b.policy.id == p.policy.id);
            if old.is_some_and(|b| json(b) == json(p)) {
                continue;
            }
            match old {
                Some(b) if is_in_use(&b.policy.id) => {
                    let mut keep = b.clone();
                    keep.policy.updated_at = Utc::now();
                    self.db.save_profile(&keep).map_err(|e| e.to_string())?;
                }
                _ => self.db.save_profile(p).map_err(|e| e.to_string())?,
            }
        }
        for b in &before.profiles {
            if after.profiles.iter().any(|p| p.policy.id == b.policy.id) {
                continue;
            }
            if is_in_use(&b.policy.id) {
                let mut keep = b.clone();
                keep.policy.updated_at = Utc::now();
                self.db.save_profile(&keep).map_err(|e| e.to_string())?;
            } else {
                self.db
                    .delete_profile(&b.policy.id)
                    .map_err(|e| e.to_string())?;
            }
        }
        if before.bedtime != after.bedtime {
            self.db
                .set_bedtime(&after.bedtime)
                .map_err(|e| e.to_string())?;
        }
        if before.bedtime_updated_at != after.bedtime_updated_at {
            match after.bedtime_updated_at {
                Some(t) => self.db.kv_set(KV_BEDTIME_UPDATED, &t.to_rfc3339()),
                None => self.db.kv_delete(KV_BEDTIME_UPDATED),
            }
            .map_err(|e| e.to_string())?;
        }
        if before.state != after.state {
            let text = serde_json::to_string(&after.state).map_err(|e| e.to_string())?;
            self.db
                .kv_set(KV_SYNC_STATE, &text)
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    /// The newest local session, status evaluated against the clock.
    pub fn sync_local_session(&mut self) -> Result<Option<FocusSession>, String> {
        self.recover()
    }

    pub fn sync_profile(&self, id: &str) -> Result<Option<Profile>, String> {
        self.db.get_profile(id).map_err(|e| e.to_string())
    }

    fn running_session_policy_id(&mut self) -> Option<String> {
        self.recover()
            .ok()
            .flatten()
            .filter(|s| session_engine::is_active(s, Utc::now()))
            .map(|s| s.policy_id)
    }

    /// Profiles something still points at (bedtime's, the current session's): sync never drops these.
    pub fn sync_referenced_ids(&mut self) -> std::collections::HashSet<String> {
        let mut ids = std::collections::HashSet::new();
        if let Ok(b) = self.db.get_bedtime() {
            ids.extend(b.policy_id.map(|p| p.to_lowercase()));
        }
        ids.extend(self.running_session_policy_id().map(|p| p.to_lowercase()));
        ids
    }

    /// Starts enforcing a session another device started. `false` when it can't be: the profile isn't here, it
    /// is an allowlist with no app for this PC, or a session is already running.
    pub fn sync_join(
        &mut self,
        remote: &now_focus_sync::session::RemoteSession,
    ) -> Result<bool, String> {
        let now = Utc::now();
        let Some(profile) = self
            .db
            .get_profile(&remote.policy_id)
            .map_err(|e| e.to_string())?
        else {
            return Ok(false);
        };
        if !allowlist::enforces_here(&profile.policy) {
            return Ok(false);
        }
        if self
            .recover()?
            .is_some_and(|s| session_engine::is_active(&s, now))
        {
            return Ok(false);
        }
        let mut session = FocusSession::new(
            &profile.policy.id,
            remote.start_at,
            remote.end_at,
            &self.device_id,
        );
        session.id = remote.id.clone();
        session.enforcement_mode = remote.mode;
        session.origin = SessionOrigin::Remote;
        let paused = self.cheat_active(now);
        if !paused {
            self.enforcer.apply(&profile.policy)?;
        }
        self.session_enforced = !paused;
        self.db.save_session(&session).map_err(|e| e.to_string())?;
        self.db
            .record_event(&session.id, "SESSION_STARTED", None)
            .map_err(|e| e.to_string())?;
        Ok(true)
    }

    /// The session was cancelled on another device. That device already did whatever its mode asks (Strict's
    /// typing and wait, Locked is refused by the server), so no unlock flow here.
    pub fn sync_end_local(&mut self, id: &str) -> Result<(), String> {
        match self.recover()? {
            Some(s) if s.id == id && session_engine::is_active(&s, Utc::now()) => {
                self.finish_session(s, "SESSION_CANCELLED")
            }
            _ => Ok(()),
        }
    }

    pub fn sync_extend_local(&mut self, id: &str, end: DateTime<Utc>) -> Result<(), String> {
        if let Some(mut s) = self.recover()? {
            if s.id == id && end > s.end_at {
                s.end_at = end;
                self.db.save_session(&s).map_err(|e| e.to_string())?;
            }
        }
        Ok(())
    }
}

fn own_exe() -> Option<String> {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.to_str().map(String::from))
}

fn system_root() -> String {
    std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".to_string())
}

fn parse_mode(mode: &str) -> Result<EnforcementMode, String> {
    match mode {
        "normal" => Ok(EnforcementMode::Normal),
        "strict" => Ok(EnforcementMode::Strict),
        "locked" => Ok(EnforcementMode::Locked),
        other => Err(format!("Unknown mode: {other}")),
    }
}

fn mode_str(mode: EnforcementMode) -> &'static str {
    match mode {
        EnforcementMode::Normal => "normal",
        EnforcementMode::Strict => "strict",
        EnforcementMode::Locked => "locked",
    }
}

fn profile_to_dto(profile: &Profile) -> ProfileDto {
    ProfileDto {
        id: profile.policy.id.clone(),
        name: profile.policy.name.clone(),
        mode: profile.policy.mode,
        domains: profile
            .policy
            .domains
            .iter()
            .map(|d| DomainRuleDto {
                id: d.id.clone(),
                domain: d.domain.clone(),
            })
            .collect(),
        applications: profile
            .policy
            .applications
            .iter()
            .map(|a| ApplicationRuleDto {
                id: a.id.clone(),
                display_name: a.display_name.clone(),
                native_identifier: a.native_identifier.clone(),
            })
            .collect(),
        feeds: FEED_DEFS
            .iter()
            .map(|(key, label, sub)| FeedRuleDto {
                feed_key: key.to_string(),
                label: label.to_string(),
                sub: sub.to_string(),
                enabled: profile
                    .feed_rules
                    .iter()
                    .any(|f| f.feed_key == *key && f.enabled),
            })
            .collect(),
    }
}

fn format_remaining(remaining: ChronoDuration) -> String {
    let total = remaining.num_seconds().max(0);
    let h = total / 3600;
    let m = (total % 3600) / 60;
    let s = total % 60;
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m:02}:{s:02}")
    }
}

fn session_to_dto(session: &FocusSession, profile_name: &str, allowlist: bool) -> SessionDto {
    let now = Utc::now();
    let remaining = (session.end_at - now).max(ChronoDuration::zero());
    let total = (session.end_at - session.start_at)
        .num_milliseconds()
        .max(1);
    let elapsed = (now - session.start_at).num_milliseconds().clamp(0, total);
    SessionDto {
        id: session.id.clone(),
        profile_id: session.policy_id.clone(),
        profile_name: profile_name.to_string(),
        mode: mode_str(session.enforcement_mode).to_string(),
        status: "active".to_string(),
        start_at: session.start_at.to_rfc3339(),
        end_at: session.end_at.to_rfc3339(),
        remaining_ms: remaining.num_milliseconds(),
        remaining_label: format_remaining(remaining),
        progress_pct: (elapsed as f64 / total as f64) * 100.0,
        paused: false, // set per snapshot, from the cheat day
        bedtime: session.session_type == SessionType::BedtimeWinddown,
        allowlist,
    }
}

fn unlock_to_dto(flow: &UnlockFlow, session: &FocusSession) -> UnlockStateDto {
    let matches = unlock_matches(&flow.typed);
    let (phase, wait_remaining_ms) = match flow.wait_started_at {
        Some(started) => {
            let remaining = (UNLOCK_WAIT - (Utc::now() - started)).max(ChronoDuration::zero());
            ("waiting", remaining.num_milliseconds())
        }
        None => ("typing", UNLOCK_WAIT.num_milliseconds()),
    };
    UnlockStateDto {
        phase: phase.to_string(),
        sentence: UNLOCK_SENTENCE.to_string(),
        typed: flow.typed.clone(),
        matches,
        wait_remaining_ms,
        wait_total_ms: UNLOCK_WAIT.num_milliseconds(),
        locked_mode: session.enforcement_mode == EnforcementMode::Locked,
    }
}

// ---- Local-time helpers & DTO converters --------------------------------

/// A local wall-clock instant mapped to UTC. On a DST spring-forward gap the
/// naive time doesn't exist; `earliest()` picks the sensible boundary, and the
/// fallback keeps this total (never panics).
fn local_naive_to_utc(naive: NaiveDateTime) -> DateTime<Utc> {
    Local
        .from_local_datetime(&naive)
        .earliest()
        .map(|dt| dt.with_timezone(&Utc))
        .unwrap_or_else(Utc::now)
}

/// UTC instant of local midnight starting `date`.
fn local_midnight_utc(date: NaiveDate) -> DateTime<Utc> {
    local_naive_to_utc(date.and_hms_opt(0, 0, 0).unwrap())
}

/// Pull the target display name out of a BLOCK_ATTEMPT event's metadata JSON
/// (`{"kind","name"}`). Empty for legacy rows that stored no metadata.
fn parse_block_target(metadata_json: Option<&str>) -> String {
    metadata_json
        .and_then(|m| serde_json::from_str::<serde_json::Value>(m).ok())
        .and_then(|v| v.get("name").and_then(|n| n.as_str()).map(String::from))
        .unwrap_or_default()
}

fn commitment_to_dto(w: &CommitmentStatusWire) -> CommitmentDto {
    CommitmentDto {
        domains: w.domains.clone(),
        end_at: w.end_at.clone(),
        can_cancel_now: w.can_cancel_now,
        remaining_secs: w.remaining_secs,
    }
}

fn schedule_to_dto(s: &Schedule) -> ScheduleDto {
    ScheduleDto {
        id: s.id.clone(),
        name: s.name.clone(),
        days: s.days.clone(),
        start_minute: s.start_minute,
        end_minute: s.end_minute,
        policy_id: s.policy_id.clone(),
        mode: mode_str(s.mode).to_string(),
        enabled: s.enabled,
    }
}

fn cheat_to_dto(c: &CheatDay, now: DateTime<Utc>) -> CheatDayDto {
    CheatDayDto {
        start_at: c.start_at.to_rfc3339(),
        end_at: c.end_at.to_rfc3339(),
        active: c.is_active(now),
        upcoming: now < c.start_at,
    }
}

fn bedtime_to_dto(s: &BedtimeSettings) -> BedtimeDto {
    BedtimeDto {
        enabled: s.enabled,
        wind_down_minute: s.wind_down_minute,
        sleep_minute: s.sleep_minute,
        wake_minute: s.wake_minute,
        lock_at_sleep: s.lock_at_sleep,
        policy_id: s.policy_id.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use now_focus_core::{BlockPolicy, Database, Profile};
    use now_focus_ipc::CommitmentStatusWire;
    use std::sync::{Arc, Mutex};

    /// Records every policy passed to `apply` so tests can assert what was
    /// pushed to the enforcer.
    struct FakeEnforcer {
        applied: Arc<Mutex<Vec<BlockPolicy>>>,
        clears: Arc<std::sync::atomic::AtomicUsize>,
    }

    impl FakeEnforcer {
        fn new() -> (Self, Arc<Mutex<Vec<BlockPolicy>>>) {
            let (fake, log, _) = Self::counting();
            (fake, log)
        }

        /// Also returns how many times `clear` was called.
        fn counting() -> (
            Self,
            Arc<Mutex<Vec<BlockPolicy>>>,
            Arc<std::sync::atomic::AtomicUsize>,
        ) {
            let log = Arc::new(Mutex::new(Vec::new()));
            let clears = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            (
                Self {
                    applied: log.clone(),
                    clears: clears.clone(),
                },
                log,
                clears,
            )
        }
    }

    impl Enforcer for FakeEnforcer {
        fn apply(&mut self, policy: &BlockPolicy) -> Result<(), String> {
            self.applied.lock().unwrap().push(policy.clone());
            Ok(())
        }
        fn clear(&mut self) -> Result<(), String> {
            self.clears
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
        fn health(&self) -> crate::dto::HealthDto {
            crate::dto::HealthDto {
                website_blocking: "test".into(),
                app_blocking: "test".into(),
                layers: vec![],
            }
        }
        fn apply_commitment(&mut self, _: &[String]) -> Result<(), String> {
            Ok(())
        }
        fn clear_commitment(&mut self) -> Result<(), String> {
            Ok(())
        }
        fn commitment_status(&self) -> Option<CommitmentStatusWire> {
            None
        }
    }

    impl AppState {
        /// Test-only constructor: accepts a pre-opened in-memory `Database`
        /// so tests never touch the filesystem.
        fn open_with_db(db: Database, enforcer: Box<dyn Enforcer>) -> Result<Self, String> {
            let device_id = db.device_id().map_err(|e| e.to_string())?;
            if db.list_policy_ids().map_err(|e| e.to_string())?.is_empty() {
                let starter = Profile::new("Deep Work");
                db.save_profile(&starter).map_err(|e| e.to_string())?;
            }
            let commitment = enforcer.commitment_status();
            Ok(Self::from_parts(db, enforcer, device_id, commitment))
        }
    }

    /// Helper: create an AppState with a profile and an active session on it.
    /// Returns (state, profile_id, enforcer_log).
    fn setup() -> (AppState, String, Arc<Mutex<Vec<BlockPolicy>>>) {
        setup_mode("normal")
    }

    fn setup_mode(mode: &str) -> (AppState, String, Arc<Mutex<Vec<BlockPolicy>>>) {
        let db = Database::open_in_memory().expect("in-memory DB");
        let (fake, log) = FakeEnforcer::new();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();

        // Create a profile and start a session on it.
        state
            .create_profile("Test Profile".into(), PolicyMode::Blocklist)
            .unwrap();

        // The seeded "Deep Work" profile may exist too; find "Test Profile".
        let snap = state.snapshot().unwrap();
        let profile_id = snap
            .profiles
            .iter()
            .find(|p| p.name == "Test Profile")
            .expect("profile should exist")
            .id
            .clone();

        state.start_session(&profile_id, 60, mode).unwrap();

        // start_session calls enforcer.apply — clear that from the log so
        // assertions below only see re-applies triggered by add_domain.
        log.lock().unwrap().clear();

        (state, profile_id, log)
    }

    #[test]
    fn a_pull_never_changes_or_deletes_the_profile_a_running_session_uses() {
        let (mut state, profile_id, _log) = setup();
        let before = state.sync_read_local().unwrap();

        // The account changed it (a site removed) and then deleted it.
        let mut weaker = before.clone();
        let p = weaker
            .profiles
            .iter_mut()
            .find(|p| p.policy.id == profile_id)
            .unwrap();
        p.policy.domains.clear();
        p.policy.name = "Renamed elsewhere".into();
        state.sync_write_local(&before, &weaker).unwrap();
        let mut gone = weaker.clone();
        gone.profiles.retain(|p| p.policy.id != profile_id);
        state.sync_write_local(&before, &gone).unwrap();

        let kept = state
            .sync_profile(&profile_id)
            .unwrap()
            .expect("still here");
        assert_eq!(kept.policy.name, "Test Profile");
        assert!(
            kept.policy.updated_at
                > before
                    .profiles
                    .iter()
                    .find(|p| p.policy.id == profile_id)
                    .unwrap()
                    .policy
                    .updated_at
        );

        // Another profile is not protected.
        let other = before
            .profiles
            .iter()
            .find(|p| p.policy.id != profile_id)
            .unwrap()
            .policy
            .id
            .clone();
        let mut gone2 = before.clone();
        gone2.profiles.retain(|p| p.policy.id != other);
        state.sync_write_local(&before, &gone2).unwrap();
        assert!(state.sync_profile(&other).unwrap().is_none());
    }

    #[test]
    fn add_domain_reapplies_during_active_session() {
        let (mut state, profile_id, log) = setup();

        state
            .add_domain(&profile_id, "example.com")
            .expect("add_domain should succeed");

        let applies = log.lock().unwrap();
        assert_eq!(
            applies.len(),
            1,
            "enforcer.apply should have been called exactly once after add_domain"
        );
        assert!(
            applies[0].domains.iter().any(|d| d.domain == "example.com"),
            "the re-applied policy must contain the newly added domain"
        );
    }

    #[test]
    fn remove_domain_rejected_during_active_session() {
        let (mut state, profile_id, _log) = setup();

        // Add a domain first so there's something to remove.
        state.add_domain(&profile_id, "example.com").unwrap();

        // Find its rule ID.
        let snap = state.snapshot().unwrap();
        let profile = snap.profiles.iter().find(|p| p.id == profile_id).unwrap();
        let rule_id = profile
            .domains
            .iter()
            .find(|d| d.domain == "example.com")
            .expect("domain should exist")
            .id
            .clone();

        let result = state.remove_domain(&profile_id, &rule_id);
        assert!(
            result.is_err(),
            "remove_domain should be rejected during an active session"
        );
        assert!(
            result.unwrap_err().contains("cantRemove"),
            "error message should mention removing blocks"
        );
    }

    #[test]
    fn remove_application_rejected_during_active_session() {
        let (mut state, profile_id, _log) = setup();

        state
            .add_application(&profile_id, "notepad.exe".into(), "Notepad".into())
            .unwrap();

        let snap = state.snapshot().unwrap();
        let profile = snap.profiles.iter().find(|p| p.id == profile_id).unwrap();
        let rule_id = profile
            .applications
            .iter()
            .find(|a| a.native_identifier == "notepad.exe")
            .expect("app should exist")
            .id
            .clone();

        let result = state.remove_application(&profile_id, &rule_id);
        assert!(
            result.is_err(),
            "remove_application should be rejected during an active session"
        );
    }

    #[test]
    fn finish_session_clears_shield() {
        let (mut state, _profile_id, _log) = setup();
        state
            .simulate_block("app".into(), "Notepad".into())
            .unwrap();
        assert!(state.snapshot().unwrap().shield.is_some());

        state.end_session_normal().unwrap();

        assert!(
            state.snapshot().unwrap().shield.is_none(),
            "the shield must not outlive the session it was raised for"
        );
    }

    #[test]
    fn begin_unlock_clears_shield() {
        let (mut state, _profile_id, _log) = setup_mode("strict");
        state
            .simulate_block("app".into(), "Notepad".into())
            .unwrap();
        assert!(state.snapshot().unwrap().shield.is_some());

        state.begin_unlock().unwrap();

        let snap = state.snapshot().unwrap();
        assert!(snap.unlock.is_some(), "unlock flow should be open");
        assert!(
            snap.shield.is_none(),
            "the shield covers the screen and has no unlock UI, so it must step aside"
        );
    }

    // ---- Cheat day, passes, schedules --------------------------------------

    fn cheat_now() -> CheatDay {
        let now = Utc::now();
        CheatDay {
            start_at: now - ChronoDuration::hours(1),
            end_at: now + ChronoDuration::hours(5),
            created_at: now - ChronoDuration::days(3),
        }
    }

    fn app_profile(state: &mut AppState) -> String {
        state
            .create_profile("Apps".into(), PolicyMode::Blocklist)
            .unwrap();
        let id = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.name == "Apps")
            .unwrap()
            .id
            .clone();
        state
            .add_application(&id, r"C:\Apps\Chat.exe".into(), "Chat".into())
            .unwrap();
        id
    }

    #[test]
    fn a_session_started_on_a_cheat_day_runs_paused_and_applies_nothing() {
        let db = Database::open_in_memory().unwrap();
        let (fake, log, _) = FakeEnforcer::counting();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.db.set_cheat_day(Some(&cheat_now())).unwrap();

        state.start_session(&id, 60, "locked").unwrap();

        assert!(
            log.lock().unwrap().is_empty(),
            "nothing is blocked on a cheat day"
        );
        let snap = state.snapshot().unwrap();
        assert!(snap.session.unwrap().paused);
        assert!(
            state.check_foreground_app(r"C:\Apps\Chat.exe").is_none(),
            "apps aren't closed either"
        );
    }

    #[test]
    fn ending_a_cheat_day_early_puts_the_running_session_back() {
        let db = Database::open_in_memory().unwrap();
        let (fake, log, _) = FakeEnforcer::counting();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.db.set_cheat_day(Some(&cheat_now())).unwrap();
        state.start_session(&id, 60, "normal").unwrap();

        state.cancel_cheat_day().unwrap(); // a live one ends now

        assert_eq!(log.lock().unwrap().len(), 1, "the session is applied again");
        assert!(!state.snapshot().unwrap().session.unwrap().paused);
        assert!(state.check_foreground_app(r"C:\Apps\Chat.exe").is_some());
    }

    const IG: &str = "c:\\apps\\ig.exe";

    fn used(state: &AppState, ms: i64) {
        let day = daily_limit::day_key(Utc::now(), &Local);
        state.db.add_limit_usage(IG, &day, ms).unwrap();
    }

    #[test]
    fn a_limited_app_is_closed_once_its_time_is_used_up_and_not_before() {
        let (mut state, _, _) = setup();
        state.set_limit(IG, "Instagram", 15).unwrap();
        assert_eq!(state.check_foreground_app("C:\\Apps\\IG.exe"), None);
        used(&state, 14 * 60_000);
        assert_eq!(state.check_foreground_app("C:\\Apps\\IG.exe"), None);
        used(&state, 60_000);
        assert_eq!(
            state.check_foreground_app("C:\\Apps\\IG.exe"),
            Some("Instagram".to_string())
        );
        assert_eq!(state.shield.as_ref().unwrap().target_kind, "limit");
        // Another app is untouched, and the snapshot reports the limit as used up.
        assert_eq!(state.check_foreground_app("C:\\Apps\\other.exe"), None);
        let dto = state.snapshot().unwrap().limits;
        assert!(dto[0].used_up && dto[0].minutes == 15);
    }

    #[test]
    fn a_cheat_day_pauses_limits() {
        let (mut state, _, _) = setup();
        state.set_limit(IG, "Instagram", 15).unwrap();
        used(&state, 20 * 60_000);
        state.db.set_cheat_day(Some(&cheat_now())).unwrap();
        assert_eq!(state.check_foreground_app("C:\\Apps\\IG.exe"), None);
    }

    #[test]
    fn loosening_a_limit_waits_for_midnight_and_removing_it_does_too() {
        let (mut state, _, _) = setup();
        state.set_limit(IG, "Instagram", 30).unwrap();
        state.set_limit(IG, "Instagram", 60).unwrap();
        let l = &state.snapshot().unwrap().limits[0];
        assert_eq!((l.minutes, l.pending), (30, Some(60)));
        used(&state, 30 * 60_000);
        state.set_limit(IG, "Instagram", 0).unwrap(); // used up: removal waits
        let l = &state.snapshot().unwrap().limits[0];
        assert_eq!((l.minutes, l.pending), (30, Some(0)));
        state.set_limit(IG, "Instagram", 15).unwrap(); // tightening applies at once
        assert_eq!(state.snapshot().unwrap().limits[0].minutes, 15);
        assert!(state.set_limit(IG, "Instagram", 7).is_err());
    }

    #[test]
    fn removing_a_limit_with_time_left_is_at_once() {
        let (mut state, _, _) = setup();
        state.set_limit(IG, "Instagram", 30).unwrap();
        used(&state, 10 * 60_000);
        state.set_limit(IG, "Instagram", 0).unwrap();
        assert!(state.snapshot().unwrap().limits.is_empty());
    }

    #[test]
    fn time_in_front_is_counted_to_the_limited_app() {
        let (mut state, _, _) = setup();
        state.set_limit(IG, "Instagram", 30).unwrap();
        let t0 = Utc::now();
        state.note_foreground("C:\\Apps\\IG.exe", t0 - ChronoDuration::seconds(20));
        state.note_foreground("C:\\Apps\\other.exe", t0);
        let day = daily_limit::day_key(t0, &Local);
        assert_eq!(state.db.limit_used_ms(IG, &day).unwrap(), 20_000);
        // Time after that is nobody's.
        state.note_foreground("C:\\Apps\\other.exe", t0 + ChronoDuration::seconds(30));
        assert_eq!(state.db.limit_used_ms(IG, &day).unwrap(), 20_000);
    }

    #[test]
    fn a_cheat_day_that_starts_mid_session_lifts_enforcement_on_the_next_tick() {
        let db = Database::open_in_memory().unwrap();
        let (fake, _log, clears) = FakeEnforcer::counting();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.start_session(&id, 60, "locked").unwrap();
        let before = clears.load(std::sync::atomic::Ordering::SeqCst);

        state.db.set_cheat_day(Some(&cheat_now())).unwrap();
        state.reconcile_enforcement();

        assert_eq!(clears.load(std::sync::atomic::Ordering::SeqCst), before + 1);
        state.reconcile_enforcement(); // idempotent
        assert_eq!(clears.load(std::sync::atomic::Ordering::SeqCst), before + 1);
    }

    #[test]
    fn a_restart_mid_session_reapplies_it() {
        let db = Database::open_in_memory().unwrap();
        let (fake, log, _) = FakeEnforcer::counting();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.start_session(&id, 60, "normal").unwrap();
        log.lock().unwrap().clear();
        state.session_enforced = false; // what a fresh process starts with

        state.reconcile_enforcement();

        assert_eq!(log.lock().unwrap().len(), 1);
    }

    #[test]
    fn a_pass_lets_a_blocked_app_stay_open_twice_per_session() {
        let db = Database::open_in_memory().unwrap();
        let (fake, _) = FakeEnforcer::new();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.start_session(&id, 60, "strict").unwrap();

        assert!(state.check_foreground_app(r"C:\Apps\Chat.exe").is_some());
        assert_eq!(state.snapshot().unwrap().shield.unwrap().passes_left, 2);
        state.use_pass().unwrap();
        assert!(state.snapshot().unwrap().shield.is_none());
        assert!(
            state.check_foreground_app(r"c:\apps\chat.EXE").is_none(),
            "open for the pass, matched case-insensitively"
        );

        // A second block (another app's pass can't be taken for this one): force the shield and spend the second pass.
        state.passes.clear();
        assert!(state.check_foreground_app(r"C:\Apps\Chat.exe").is_some());
        assert_eq!(state.snapshot().unwrap().shield.unwrap().passes_left, 1);
        state.use_pass().unwrap();

        state.passes.clear();
        assert!(state.check_foreground_app(r"C:\Apps\Chat.exe").is_some());
        assert_eq!(state.snapshot().unwrap().shield.unwrap().passes_left, 0);
        assert!(state.use_pass().unwrap_err().contains("noPasses"));
    }

    #[test]
    fn locked_and_bedtime_sessions_offer_no_pass() {
        let db = Database::open_in_memory().unwrap();
        let (fake, _) = FakeEnforcer::new();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state.start_session(&id, 60, "locked").unwrap();
        assert!(state.check_foreground_app(r"C:\Apps\Chat.exe").is_some());
        assert_eq!(state.snapshot().unwrap().shield.unwrap().passes_left, 0);
        assert!(state.use_pass().is_err());
    }

    #[test]
    fn a_simulated_block_has_nothing_to_pass() {
        let (mut state, _id, _log) = setup();
        state
            .simulate_block("app".into(), "Notepad".into())
            .unwrap();
        assert_eq!(state.snapshot().unwrap().shield.unwrap().passes_left, 0);
        assert!(state.use_pass().is_err());
    }

    fn all_day_schedule(policy_id: &str, mode: &str) -> ScheduleDto {
        // A window that contains now, whatever time the test runs: it started 5 minutes ago and lasts 2 hours.
        let minute_of_day = {
            use chrono::Timelike;
            let t = Local::now();
            (t.hour() * 60 + t.minute()) as i64
        };
        let start = (minute_of_day - 5).rem_euclid(1440);
        ScheduleDto {
            id: String::new(),
            name: "Work".into(),
            days: vec![0, 1, 2, 3, 4, 5, 6],
            start_minute: start,
            end_minute: (start + 120) % 1440,
            policy_id: policy_id.into(),
            mode: mode.into(),
            enabled: true,
        }
    }

    #[test]
    fn a_due_schedule_starts_a_session_once_and_not_again_after_it_is_ended() {
        let db = Database::open_in_memory().unwrap();
        let (fake, log, _) = FakeEnforcer::counting();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state
            .save_schedule(all_day_schedule(&id, "normal"))
            .unwrap();

        state.schedule_tick();
        let session = state
            .snapshot()
            .unwrap()
            .session
            .expect("the schedule started a session");
        assert_eq!(session.profile_id, id);
        assert_eq!(
            state.db.most_recent_session().unwrap().unwrap().origin,
            SessionOrigin::Schedule
        );
        assert_eq!(log.lock().unwrap().len(), 1);

        state.end_session_normal().unwrap();
        state.schedule_tick(); // the window was already begun: ending it early sticks
        assert!(state.snapshot().unwrap().session.is_none());
    }

    #[test]
    fn schedules_wait_for_a_running_session_and_for_a_cheat_day() {
        let db = Database::open_in_memory().unwrap();
        let (fake, _) = FakeEnforcer::new();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        let id = app_profile(&mut state);
        state
            .save_schedule(all_day_schedule(&id, "normal"))
            .unwrap();

        state.db.set_cheat_day(Some(&cheat_now())).unwrap();
        state.schedule_tick();
        assert!(
            state.snapshot().unwrap().session.is_none(),
            "nothing starts on a cheat day"
        );

        state.cancel_cheat_day().unwrap();
        let other = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.name == "Deep Work")
            .unwrap()
            .id
            .clone();
        state.start_session(&other, 30, "normal").unwrap();
        state.schedule_tick();
        assert_eq!(
            state.snapshot().unwrap().session.unwrap().profile_id,
            other,
            "the running session holds"
        );
    }

    #[test]
    fn a_schedule_needs_a_name_a_day_and_a_real_profile() {
        let (mut state, id, _log) = setup();
        let mut s = all_day_schedule(&id, "normal");
        s.name = " ".into();
        assert!(state.save_schedule(s).is_err());
        let mut s = all_day_schedule(&id, "normal");
        s.days.clear();
        assert!(state.save_schedule(s).is_err());
        let s = all_day_schedule("missing", "normal");
        assert!(state.save_schedule(s).is_err());
        let s = all_day_schedule(&id, "weird");
        assert!(state.save_schedule(s).is_err());
    }

    #[test]
    fn a_cheat_day_can_only_be_planned_a_day_ahead() {
        let (mut state, _id, _log) = setup();
        let soon = (Utc::now() + ChronoDuration::hours(2)).to_rfc3339();
        assert!(state.schedule_cheat_day(&soon).is_err());
        let ok = state.snapshot().unwrap().cheat_options[0].clone();
        state.schedule_cheat_day(&ok).unwrap();
        assert!(state.snapshot().unwrap().cheat_day.unwrap().upcoming);
        // One a week: a day two days from it is refused.
        let first = DateTime::parse_from_rfc3339(&ok)
            .unwrap()
            .with_timezone(&Utc);
        assert!(state
            .schedule_cheat_day(&(first + ChronoDuration::days(2)).to_rfc3339())
            .is_err());
        state.cancel_cheat_day().unwrap();
        assert!(
            state.snapshot().unwrap().cheat_day.is_none(),
            "cancelling a planned day removes it"
        );
    }
    // ---- whitelist mode -------------------------------------------------------

    /// An allowlist profile with `apps` allowed (before any session), and its id.
    fn allow_profile(state: &mut AppState, apps: &[&str]) -> String {
        state
            .create_profile("Only these".into(), PolicyMode::Allowlist)
            .unwrap();
        let id = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.name == "Only these")
            .unwrap()
            .id
            .clone();
        for app in apps {
            state
                .add_application(&id, (*app).into(), "App".into())
                .unwrap();
        }
        id
    }

    fn fresh() -> (AppState, Arc<Mutex<Vec<BlockPolicy>>>) {
        let db = Database::open_in_memory().unwrap();
        let (fake, log) = FakeEnforcer::new();
        (AppState::open_with_db(db, Box::new(fake)).unwrap(), log)
    }

    #[test]
    fn a_whitelist_session_closes_unlisted_apps_and_keeps_listed_ones() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\Code.exe"]);
        state.start_session(&id, 60, "normal").unwrap();

        assert_eq!(
            state
                .check_foreground_app(r"C:\Games\Steam\steam.exe")
                .as_deref(),
            Some("steam"),
            "named by its file, no rule has a name for it"
        );
        assert!(state.check_foreground_app(r"c:\apps\CODE.exe").is_none());
    }

    #[test]
    fn a_whitelist_never_closes_nowfocus_or_the_windows_directory() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\Code.exe"]);
        state.start_session(&id, 60, "normal").unwrap();

        let own = own_exe().expect("test binary has a path");
        assert!(state.check_foreground_app(&own).is_none());
        let explorer = format!(r"{}\explorer.exe", system_root());
        assert!(state.check_foreground_app(&explorer).is_none());
    }

    #[test]
    fn a_pass_reopens_an_app_a_whitelist_closed() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\Code.exe"]);
        state.start_session(&id, 60, "strict").unwrap();

        assert!(state.check_foreground_app(r"C:\Games\Game.exe").is_some());
        state.use_pass().unwrap();
        assert!(state.check_foreground_app(r"c:\games\game.EXE").is_none());
        assert!(
            state.check_foreground_app(r"C:\Games\Other.exe").is_some(),
            "a pass is for the one app"
        );
    }

    #[test]
    fn a_whitelist_with_no_app_cannot_start_and_closes_nothing() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[]);
        assert!(state
            .start_session(&id, 60, "normal")
            .unwrap_err()
            .contains("allowOne"));

        // The same list reaching a running session some other way (a sync pull, a schedule) must still close nothing.
        let now = Utc::now();
        let session = FocusSession::new(&id, now, now + ChronoDuration::hours(1), "dev");
        state.db.save_session(&session).unwrap();
        assert!(state.check_foreground_app(r"C:\Games\Game.exe").is_none());
    }

    #[test]
    fn the_enforcer_is_given_the_mode_with_the_policy() {
        let (mut state, log) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\Code.exe"]);
        state.start_session(&id, 60, "normal").unwrap();
        let applied = log.lock().unwrap();
        assert_eq!(applied.len(), 1);
        assert_eq!(
            applied[0].mode,
            PolicyMode::Allowlist,
            "the service decides what the mode means for the hosts file"
        );
    }

    #[test]
    fn websites_cannot_be_added_to_a_whitelist() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[]);
        assert!(state
            .add_domain(&id, "example.com")
            .unwrap_err()
            .contains("whitelistNoSites"));
    }

    #[test]
    fn a_running_whitelist_can_shrink_but_not_grow_or_empty() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\A.exe", r"C:\Apps\B.exe"]);
        state.start_session(&id, 60, "normal").unwrap();

        assert!(state
            .add_application(&id, r"C:\Apps\C.exe".into(), "C".into())
            .unwrap_err()
            .contains("cantAllowMore"));

        let rules = |s: &mut AppState| {
            s.snapshot()
                .unwrap()
                .profiles
                .iter()
                .find(|p| p.id == id)
                .unwrap()
                .applications
                .clone()
        };
        let first = rules(&mut state)[0].id.clone();
        state.remove_application(&id, &first).unwrap();
        let last = rules(&mut state)[0].id.clone();
        assert!(state
            .remove_application(&id, &last)
            .unwrap_err()
            .contains("cantRemoveLast"));
    }

    #[test]
    fn an_idle_whitelist_can_be_edited_freely_and_a_blocklist_keeps_its_rule() {
        let (mut state, _) = fresh();
        let id = allow_profile(&mut state, &[r"C:\Apps\A.exe"]);
        state
            .add_application(&id, r"C:\Apps\B.exe".into(), "B".into())
            .unwrap();
        let rule = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.id == id)
            .unwrap()
            .applications[0]
            .id
            .clone();
        state.remove_application(&id, &rule).unwrap();

        let blocks = app_profile(&mut state);
        state.start_session(&blocks, 60, "normal").unwrap();
        let rule = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.id == blocks)
            .unwrap()
            .applications[0]
            .id
            .clone();
        assert!(state
            .remove_application(&blocks, &rule)
            .unwrap_err()
            .contains("cantRemove"));
    }

    #[test]
    fn the_profile_reports_its_mode() {
        let (mut state, _) = fresh();
        allow_profile(&mut state, &[]);
        let modes: Vec<_> = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .map(|p| (p.name.clone(), p.mode))
            .collect();
        assert!(modes.contains(&("Only these".to_string(), PolicyMode::Allowlist)));
        assert!(modes.contains(&("Deep Work".to_string(), PolicyMode::Blocklist)));
    }

    #[test]
    fn a_joined_whitelist_session_needs_an_app_for_this_pc() {
        let (mut state, _) = fresh();
        let empty = allow_profile(&mut state, &[]);
        let now = Utc::now();
        let remote = |pid: &str| now_focus_sync::session::RemoteSession {
            id: "00000000-0000-4000-8000-0000000000aa".into(),
            policy_id: pid.into(),
            status: "active".into(),
            mode: EnforcementMode::Normal,
            start_at: now,
            end_at: now + ChronoDuration::hours(1),
            started_on: None,
            raw: serde_json::json!({}),
        };
        assert!(
            !state.sync_join(&remote(&empty)).unwrap(),
            "no Windows app on the list: don't join"
        );

        state
            .add_application(&empty, r"C:\Apps\Code.exe".into(), "Code".into())
            .unwrap();
        assert!(state.sync_join(&remote(&empty)).unwrap());
    }
    #[test]
    fn the_language_is_remembered_and_checked() {
        let (mut state, _, _) = setup();
        assert_eq!(state.language(), "system");
        state.set_language("ar").unwrap();
        assert_eq!(state.language(), "ar");
        assert_eq!(state.snapshot().unwrap().language, "ar");
        assert!(state.set_language("fr").is_err());
    }

    #[test]
    fn either_unlock_sentence_unlocks() {
        assert!(unlock_matches(UNLOCK_SENTENCE));
        assert!(unlock_matches(&format!("  {UNLOCK_SENTENCE_AR}  ")));
        assert!(!unlock_matches("I am choosing"));
    }

    #[test]
    fn the_unlock_sentences_match_what_the_ui_shows() {
        // The UI displays the sentence from its dictionary and the backend compares it: they must be the same text.
        assert!(include_str!("../../src/i18n/en.ts").contains(UNLOCK_SENTENCE));
        assert!(include_str!("../../src/i18n/ar.ts").contains(UNLOCK_SENTENCE_AR));
    }
    fn state_with_profile() -> (AppState, String) {
        let db = Database::open_in_memory().expect("in-memory DB");
        let (fake, _log) = FakeEnforcer::new();
        let mut state = AppState::open_with_db(db, Box::new(fake)).unwrap();
        state
            .create_profile("Feeds".into(), PolicyMode::Blocklist)
            .unwrap();
        let id = state
            .snapshot()
            .unwrap()
            .profiles
            .iter()
            .find(|p| p.name == "Feeds")
            .expect("profile should exist")
            .id
            .clone();
        (state, id)
    }

    #[test]
    fn feed_rules_are_live_only_while_a_session_runs_and_cannot_be_switched_off_in_it() {
        let (mut state, id) = state_with_profile();
        state.toggle_feed(&id, "shorts").unwrap();
        assert!(
            state.live_feeds().is_empty(),
            "no session, nothing to enforce"
        );

        state.start_session(&id, 60, "normal").unwrap();
        assert_eq!(state.live_feeds(), vec!["shorts".to_string()]);

        state.toggle_feed(&id, "reels").unwrap(); // switching one on only adds a block
        assert_eq!(
            state.live_feeds(),
            vec!["shorts".to_string(), "reels".to_string()]
        );

        assert_eq!(
            state.toggle_feed(&id, "shorts"),
            Err("cantRemove".to_string()),
            "switching one off would loosen the running session"
        );
        assert!(state.live_feeds().contains(&"shorts".to_string()));
    }

    #[test]
    fn a_feed_rule_the_address_check_cannot_see_is_never_live() {
        let (mut state, id) = state_with_profile();
        state.toggle_feed(&id, "xfy").unwrap(); // synced from Android; a section of a page, not an address
        state.start_session(&id, 60, "normal").unwrap();
        assert!(state.live_feeds().is_empty());
    }

    #[test]
    fn the_profile_screen_lists_only_the_feed_rules_this_pc_can_enforce() {
        let (mut state, id) = state_with_profile();
        state.toggle_feed(&id, "xfy").unwrap();
        let snap = state.snapshot().unwrap();
        let p = snap.profiles.iter().find(|p| p.id == id).unwrap();
        let keys: Vec<&str> = p.feeds.iter().map(|f| f.feed_key.as_str()).collect();
        assert_eq!(keys, ["shorts", "reels", "fbreels"]);
    }
}
