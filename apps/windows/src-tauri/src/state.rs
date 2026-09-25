use std::path::Path;

use chrono::{DateTime, Duration as ChronoDuration, Utc};
use now_focus_core::block_policy::FeedRule;
use now_focus_core::{
    domain_validation, session_engine, ApplicationRule, DomainRule, EnforcementMode, FocusSession,
    FocusSessionStatus, Profile,
};

use crate::dto::{
    AppStateDto, ApplicationRuleDto, DomainRuleDto, FeedRuleDto, ProfileDto, SessionDto, ShieldDto,
    StatsDto, UnlockStateDto,
};
use crate::enforcer::Enforcer;

/// How long the "say it, then wait" pause lasts in Strict mode. The mockup's
/// `NowFocus PC.dc.html` exposed this as a designer-only prop
/// (`unlockWait`, 5-120s, default 30) for previewing different values; the
/// real app has no such setting yet, so it's a fixed constant matching that
/// default. Promote it to a real user preference if that's ever asked for.
const UNLOCK_WAIT: ChronoDuration = ChronoDuration::seconds(30);

const UNLOCK_SENTENCE: &str = "I am choosing to end this focus session early.";

/// Feed keys the design defines, with their labels/sub-copy — matches the
/// `FEEDS` table in the fetched NowFocus PC.dc.html script. Not enforced
/// until the Phase 5 browser-extension milestone; stored now so profile
/// data doesn't need a migration later.
const FEED_DEFS: &[(&str, &str, &str)] = &[
    ("shorts", "YouTube Shorts", "Videos and search still work"),
    ("reels", "Instagram Reels & Explore", "DMs stay open"),
    ("xfy", "X \u{201c}For You\u{201d} feed", "Following tab only"),
    ("ythome", "YouTube home recommendations", "Opens straight to search"),
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

        Ok(Self { db, enforcer, device_id, unlock: None, shield: None })
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
                self.db.record_event(&session.id, event, None).map_err(|e| e.to_string())?;
            }
            if !session_engine::is_active(&session, Utc::now()) {
                self.enforcer.clear()?;
                self.unlock = None;
                self.shield = None;
            }
        }
        Ok(Some(session))
    }

    // ---- Snapshot ----------------------------------------------------

    pub fn snapshot(&mut self) -> Result<AppStateDto, String> {
        let session = self.recover()?;
        let profiles = self.db.list_profiles().map_err(|e| e.to_string())?;
        let profile_dtos = profiles.iter().map(profile_to_dto).collect();

        let session_dto = match &session {
            Some(s) if session_engine::is_active(s, Utc::now()) => {
                let profile_name =
                    profiles.iter().find(|p| p.policy.id == s.policy_id).map(|p| p.policy.name.clone()).unwrap_or_default();
                Some(session_to_dto(s, &profile_name))
            }
            _ => None,
        };

        let unlock_dto = match (&self.unlock, &session) {
            (Some(flow), Some(s)) => Some(unlock_to_dto(flow, s)),
            _ => None,
        };

        let today_start = Utc::now().date_naive().and_hms_opt(0, 0, 0).unwrap().and_utc();
        let recent_sessions = self.db.sessions_since(today_start).map_err(|e| e.to_string())?;
        let today_minutes: i64 = recent_sessions
            .iter()
            .map(|s| {
                let end = if session_engine::is_active(s, Utc::now()) { Utc::now() } else { s.end_at.min(Utc::now()) };
                (end - s.start_at).num_minutes().max(0)
            })
            .sum();
        let sessions_completed =
            self.db.count_events_since("SESSION_COMPLETED", today_start).map_err(|e| e.to_string())?;
        let block_attempts =
            self.db.count_events_since("BLOCK_ATTEMPT", today_start).map_err(|e| e.to_string())?;

        Ok(AppStateDto {
            profiles: profile_dtos,
            session: session_dto,
            unlock: unlock_dto,
            shield: self.shield.clone(),
            health: self.enforcer.health(),
            stats: StatsDto {
                today_minutes,
                sessions_completed,
                sessions_started: recent_sessions.len() as i64,
                block_attempts_today: block_attempts,
            },
        })
    }

    // ---- Profiles ------------------------------------------------------

    pub fn create_profile(&mut self, name: String) -> Result<(), String> {
        let profile = Profile::new(name);
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    pub fn rename_profile(&mut self, profile_id: &str, name: String) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        profile.policy.name = name;
        profile.policy.updated_at = Utc::now();
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    /// Returns `Err` with mockup-matching copy for an invalid or duplicate
    /// domain ("That doesn't look like a website..." / "already on the
    /// list") rather than a raw validation error — this becomes the
    /// `domErr` field the Profiles screen shows inline.
    pub fn add_domain(&mut self, profile_id: &str, raw: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        let Some(domain) = domain_validation::normalize(raw) else {
            return Err("That doesn't look like a website. Try something like example.com".to_string());
        };
        if profile.policy.domains.iter().any(|d| d.domain == domain) {
            return Err(format!("{domain} is already on the list"));
        }
        profile.policy.domains.push(DomainRule::new(domain));
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    pub fn remove_domain(&mut self, profile_id: &str, rule_id: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        profile.policy.domains.retain(|d| d.id != rule_id);
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    /// `native_identifier`/`display_name` come from a real OS file picker on
    /// the frontend (Tauri's dialog plugin), not a hardcoded pool like the
    /// mockup's `APPS` table.
    pub fn add_application(&mut self, profile_id: &str, native_identifier: String, display_name: String) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        if profile.policy.applications.iter().any(|a| a.native_identifier == native_identifier) {
            return Err(format!("{display_name} is already on the list"));
        }
        profile.policy.applications.push(ApplicationRule::new(native_identifier, display_name));
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    pub fn remove_application(&mut self, profile_id: &str, rule_id: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        profile.policy.applications.retain(|a| a.id != rule_id);
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    pub fn toggle_feed(&mut self, profile_id: &str, feed_key: &str) -> Result<(), String> {
        let mut profile = self.require_profile(profile_id)?;
        if let Some(rule) = profile.feed_rules.iter_mut().find(|f| f.feed_key == feed_key) {
            rule.enabled = !rule.enabled;
        } else {
            profile.feed_rules.push(FeedRule {
                id: uuid::Uuid::new_v4().to_string(),
                profile_id: profile_id.to_string(),
                feed_key: feed_key.to_string(),
                enabled: true,
            });
        }
        self.db.save_profile(&profile).map_err(|e| e.to_string())
    }

    fn require_profile(&self, profile_id: &str) -> Result<Profile, String> {
        self.db
            .get_profile(profile_id)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "That profile no longer exists".to_string())
    }

    // ---- Sessions ------------------------------------------------------

    pub fn start_session(&mut self, profile_id: &str, duration_minutes: i64, mode: &str) -> Result<(), String> {
        if self.recover()?.is_some_and(|s| session_engine::is_active(&s, Utc::now())) {
            return Err("A session is already running".to_string());
        }
        let profile = self.require_profile(profile_id)?;
        let mode = parse_mode(mode)?;
        let now = Utc::now();
        let mut session = FocusSession::new(profile_id, now, now + ChronoDuration::minutes(duration_minutes), &self.device_id);
        session.enforcement_mode = mode;

        self.enforcer.apply(&profile.policy)?;
        self.db.save_session(&session).map_err(|e| e.to_string())?;
        self.db.record_event(&session.id, "SESSION_STARTED", None).map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Normal mode only — the frontend shouldn't offer this button in
    /// strict/locked mode, but the backend re-checks anyway (the same
    /// "don't trust the caller already validated it" rule domain validation
    /// follows at the enforcement boundary).
    pub fn end_session_normal(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("No session is running")?;
        if session.enforcement_mode != EnforcementMode::Normal {
            return Err("This session isn't in Normal mode".to_string());
        }
        self.finish_session(session, "SESSION_CANCELLED")
    }

    fn finish_session(&mut self, mut session: FocusSession, event: &str) -> Result<(), String> {
        session.status = FocusSessionStatus::Cancelled;
        session.cancelled_at = Some(Utc::now());
        self.enforcer.clear()?;
        self.db.save_session(&session).map_err(|e| e.to_string())?;
        self.db.record_event(&session.id, event, None).map_err(|e| e.to_string())?;
        self.unlock = None;
        Ok(())
    }

    // ---- Unlock flow (Strict/Locked "end early") ------------------------

    pub fn begin_unlock(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("No session is running")?;
        if session.enforcement_mode == EnforcementMode::Normal {
            return self.end_session_normal();
        }
        self.unlock = Some(UnlockFlow { typed: String::new(), wait_started_at: None });
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
        if flow.typed.trim() != UNLOCK_SENTENCE {
            return Err("Keep going, match it exactly".to_string());
        }
        flow.wait_started_at = Some(Utc::now());
        Ok(())
    }

    /// Only reachable once the wait has actually elapsed — the backend, not
    /// just a disabled frontend button, is what enforces that a locked
    /// session can't be ended early at all (checked via `enforcement_mode`).
    pub fn confirm_unlock(&mut self) -> Result<(), String> {
        let session = self.recover()?.ok_or("No session is running")?;
        if session.enforcement_mode == EnforcementMode::Locked {
            return Err("This session is locked until it ends".to_string());
        }
        let flow = self.unlock.as_ref().ok_or("Not in the unlock flow")?;
        let started = flow.wait_started_at.ok_or("The pause hasn't started yet")?;
        if Utc::now() - started < UNLOCK_WAIT {
            return Err("The pause isn't over yet".to_string());
        }
        self.finish_session(session, "SESSION_CANCELLED")
    }

    // ---- Shield (blocked-app/site overlay) ------------------------------

    /// Dev-only affordance for exercising the Shield screen without the
    /// real Win32 foreground hook, which Phase 4 adds. Once that lands, it
    /// calls this same path instead of the frontend triggering it directly.
    pub fn simulate_block(&mut self, target_kind: String, target_name: String) -> Result<(), String> {
        let session = self.recover()?.ok_or("No session is running")?;
        self.shield = Some(ShieldDto { target_kind, target_name });
        self.db.record_event(&session.id, "BLOCK_ATTEMPT", None).map_err(|e| e.to_string())
    }

    pub fn dismiss_shield(&mut self) {
        self.shield = None;
    }
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
        domains: profile.policy.domains.iter().map(|d| DomainRuleDto { id: d.id.clone(), domain: d.domain.clone() }).collect(),
        applications: profile
            .policy
            .applications
            .iter()
            .map(|a| ApplicationRuleDto { id: a.id.clone(), display_name: a.display_name.clone(), native_identifier: a.native_identifier.clone() })
            .collect(),
        feeds: FEED_DEFS
            .iter()
            .map(|(key, label, sub)| FeedRuleDto {
                feed_key: key.to_string(),
                label: label.to_string(),
                sub: sub.to_string(),
                enabled: profile.feed_rules.iter().any(|f| f.feed_key == *key && f.enabled),
            })
            .collect(),
    }
}

fn format_remaining(remaining: ChronoDuration) -> String {
    let total = remaining.num_seconds().max(0);
    let h = total / 3600;
    let m = (total % 3600) / 60;
    let s = total % 60;
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m:02}:{s:02}") }
}

fn session_to_dto(session: &FocusSession, profile_name: &str) -> SessionDto {
    let now = Utc::now();
    let remaining = (session.end_at - now).max(ChronoDuration::zero());
    let total = (session.end_at - session.start_at).num_milliseconds().max(1);
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
    }
}

fn unlock_to_dto(flow: &UnlockFlow, session: &FocusSession) -> UnlockStateDto {
    let matches = flow.typed.trim() == UNLOCK_SENTENCE;
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
