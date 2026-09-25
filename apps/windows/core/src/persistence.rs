use std::path::Path;

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection};

use crate::block_policy::{
    ApplicationRule, BlockPolicy, DomainRule, FeedRule, NotificationMode, PolicyMode, Profile,
};
use crate::focus_session::{
    EnforcementMode, FocusSession, FocusSessionStatus, NotificationMode as SessionNotificationMode,
    SessionType,
};

#[derive(Debug, thiserror::Error)]
pub enum PersistenceError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
    #[error("stored timestamp is not valid RFC3339: {0}")]
    BadTimestamp(#[from] chrono::ParseError),
    #[error("unknown enum value in database: {0}")]
    BadEnumValue(String),
}

pub type Result<T> = std::result::Result<T, PersistenceError>;

/// Port of apps/macos/NowFocusCore/Persistence/DatabaseManager.swift, minus
/// GRDB (this uses rusqlite directly). Schema mirrors
/// focus_app_technical_architecture.md §20.1, plus `feed_rules` for the
/// Windows-only "feeds only" toggles (see block_policy::FeedRule).
pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)?;
        Self::from_connection(conn)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::from_connection(Connection::open_in_memory()?)
    }

    fn from_connection(
        #[cfg_attr(not(debug_assertions), allow(unused_mut))] mut conn: Connection,
    ) -> Result<Self> {
        // Gate verbose per-statement tracing behind debug builds — logging
        // every SQL statement unconditionally in a release build was the
        // exact bug fixed in commit 3ae56dc on the macOS side.
        #[cfg(debug_assertions)]
        conn.trace(Some(|sql| eprintln!("[sql] {sql}")));

        conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS block_policies (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                mode TEXT NOT NULL,
                categories_json TEXT NOT NULL,
                notification_policy TEXT NOT NULL,
                revision INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS domain_rules (
                id TEXT PRIMARY KEY,
                policy_id TEXT NOT NULL REFERENCES block_policies(id),
                domain TEXT NOT NULL,
                include_subdomains INTEGER NOT NULL,
                enabled INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS application_rules (
                id TEXT PRIMARY KEY,
                policy_id TEXT NOT NULL REFERENCES block_policies(id),
                platform TEXT NOT NULL,
                native_identifier TEXT NOT NULL,
                display_name TEXT NOT NULL,
                enabled INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS feed_rules (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL REFERENCES block_policies(id),
                feed_key TEXT NOT NULL,
                enabled INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS focus_sessions (
                id TEXT PRIMARY KEY,
                policy_id TEXT NOT NULL,
                session_type TEXT NOT NULL,
                start_at TEXT NOT NULL,
                end_at TEXT NOT NULL,
                status TEXT NOT NULL,
                enforcement_mode TEXT NOT NULL,
                notification_mode TEXT NOT NULL,
                created_at TEXT NOT NULL,
                completed_at TEXT,
                cancelled_at TEXT,
                device_id TEXT NOT NULL,
                revision INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS session_events (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                occurred_at TEXT NOT NULL,
                metadata_json TEXT
            );
            CREATE TABLE IF NOT EXISTS device_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_id TEXT NOT NULL
            );
            ",
        )?;

        Ok(Self { conn })
    }

    /// A stable id for this PC, generated once and persisted. There's no
    /// pairing/sync backend yet (see the plan's "cross-device is honest, not
    /// stubbed" note), so this only ever identifies the local device — it's
    /// not shared or synced anywhere.
    pub fn device_id(&self) -> Result<String> {
        let existing = self
            .conn
            .query_row("SELECT device_id FROM device_settings WHERE id = 1", [], |r| r.get::<_, String>(0));
        match existing {
            Ok(id) => Ok(id),
            Err(rusqlite::Error::QueryReturnedNoRows) => {
                let id = uuid::Uuid::new_v4().to_string();
                self.conn.execute("INSERT INTO device_settings (id, device_id) VALUES (1, ?1)", params![id])?;
                Ok(id)
            }
            Err(e) => Err(e.into()),
        }
    }

    // ---- Policies / profiles ----------------------------------------

    pub fn save_profile(&self, profile: &Profile) -> Result<()> {
        self.save_policy(&profile.policy)?;
        self.conn.execute(
            "DELETE FROM feed_rules WHERE profile_id = ?1",
            params![profile.policy.id],
        )?;
        for f in &profile.feed_rules {
            self.conn.execute(
                "INSERT INTO feed_rules (id, profile_id, feed_key, enabled) VALUES (?1, ?2, ?3, ?4)",
                params![f.id, f.profile_id, f.feed_key, f.enabled],
            )?;
        }
        Ok(())
    }

    pub fn save_policy(&self, policy: &BlockPolicy) -> Result<()> {
        let categories_json = serde_json::to_string(&policy.categories).unwrap_or_default();
        self.conn.execute(
            "INSERT INTO block_policies (id, name, mode, categories_json, notification_policy, revision, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
             ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, mode = excluded.mode, categories_json = excluded.categories_json,
                notification_policy = excluded.notification_policy, revision = excluded.revision,
                updated_at = excluded.updated_at",
            params![
                policy.id,
                policy.name,
                mode_to_str(policy.mode),
                categories_json,
                notification_mode_to_str(policy.notification_policy),
                policy.revision,
                policy.created_at.to_rfc3339(),
                policy.updated_at.to_rfc3339(),
            ],
        )?;

        self.conn.execute(
            "DELETE FROM domain_rules WHERE policy_id = ?1",
            params![policy.id],
        )?;
        for d in &policy.domains {
            self.conn.execute(
                "INSERT INTO domain_rules (id, policy_id, domain, include_subdomains, enabled) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![d.id, policy.id, d.domain, d.include_subdomains, d.enabled],
            )?;
        }

        self.conn.execute(
            "DELETE FROM application_rules WHERE policy_id = ?1",
            params![policy.id],
        )?;
        for a in &policy.applications {
            self.conn.execute(
                "INSERT INTO application_rules (id, policy_id, platform, native_identifier, display_name, enabled) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![a.id, policy.id, a.platform, a.native_identifier, a.display_name, a.enabled],
            )?;
        }

        Ok(())
    }

    pub fn get_policy(&self, id: &str) -> Result<Option<BlockPolicy>> {
        let row = self.conn.query_row(
            "SELECT name, mode, categories_json, notification_policy, revision, created_at, updated_at
             FROM block_policies WHERE id = ?1",
            params![id],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, i64>(4)?,
                    r.get::<_, String>(5)?,
                    r.get::<_, String>(6)?,
                ))
            },
        );

        let (name, mode, categories_json, notification_policy, revision, created_at, updated_at) =
            match row {
                Ok(v) => v,
                Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
                Err(e) => return Err(e.into()),
            };

        let mut stmt = self.conn.prepare(
            "SELECT id, domain, include_subdomains, enabled FROM domain_rules WHERE policy_id = ?1",
        )?;
        let domains = stmt
            .query_map(params![id], |r| {
                Ok(DomainRule {
                    id: r.get(0)?,
                    domain: r.get(1)?,
                    include_subdomains: r.get(2)?,
                    enabled: r.get(3)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        let mut stmt = self.conn.prepare(
            "SELECT id, platform, native_identifier, display_name, enabled FROM application_rules WHERE policy_id = ?1",
        )?;
        let applications = stmt
            .query_map(params![id], |r| {
                Ok(ApplicationRule {
                    id: r.get(0)?,
                    platform: r.get(1)?,
                    native_identifier: r.get(2)?,
                    display_name: r.get(3)?,
                    enabled: r.get(4)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        Ok(Some(BlockPolicy {
            id: id.to_string(),
            name,
            mode: str_to_mode(&mode)?,
            domains,
            applications,
            categories: serde_json::from_str(&categories_json).unwrap_or_default(),
            notification_policy: str_to_notification_mode(&notification_policy)?,
            created_at: parse_time(&created_at)?,
            updated_at: parse_time(&updated_at)?,
            revision,
        }))
    }

    pub fn list_policy_ids(&self) -> Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id FROM block_policies ORDER BY created_at ASC")?;
        let ids = stmt
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(ids)
    }

    pub fn get_feed_rules(&self, profile_id: &str) -> Result<Vec<FeedRule>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, feed_key, enabled FROM feed_rules WHERE profile_id = ?1")?;
        let rows = stmt
            .query_map(params![profile_id], |r| {
                Ok(FeedRule {
                    id: r.get(0)?,
                    profile_id: profile_id.to_string(),
                    feed_key: r.get(1)?,
                    enabled: r.get(2)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(PersistenceError::from)?;
        Ok(rows)
    }

    pub fn get_profile(&self, id: &str) -> Result<Option<Profile>> {
        let Some(policy) = self.get_policy(id)? else { return Ok(None) };
        let feed_rules = self.get_feed_rules(id)?;
        Ok(Some(Profile { policy, feed_rules }))
    }

    pub fn list_profiles(&self) -> Result<Vec<Profile>> {
        self.list_policy_ids()?
            .into_iter()
            .filter_map(|id| self.get_profile(&id).transpose())
            .collect()
    }

    // ---- Sessions ------------------------------------------------------

    pub fn save_session(&self, session: &FocusSession) -> Result<()> {
        self.conn.execute(
            "INSERT INTO focus_sessions
                (id, policy_id, session_type, start_at, end_at, status, enforcement_mode, notification_mode,
                 created_at, completed_at, cancelled_at, device_id, revision)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
             ON CONFLICT(id) DO UPDATE SET
                status = excluded.status, completed_at = excluded.completed_at,
                cancelled_at = excluded.cancelled_at, revision = excluded.revision",
            params![
                session.id,
                session.policy_id,
                session_type_to_str(session.session_type),
                session.start_at.to_rfc3339(),
                session.end_at.to_rfc3339(),
                status_to_str(session.status),
                enforcement_mode_to_str(session.enforcement_mode),
                session_notification_mode_to_str(session.notification_mode),
                session.created_at.to_rfc3339(),
                session.completed_at.map(|d| d.to_rfc3339()),
                session.cancelled_at.map(|d| d.to_rfc3339()),
                session.device_id,
                session.revision,
            ],
        )?;
        Ok(())
    }

    /// The recovery read: whatever session was persisted most recently,
    /// regardless of its stored status. The caller runs it through
    /// `session_engine::evaluate_state` against the current time — this
    /// function does not decide activity, it only returns durable state.
    pub fn most_recent_session(&self) -> Result<Option<FocusSession>> {
        let row = self.conn.query_row(
            "SELECT id, policy_id, session_type, start_at, end_at, status, enforcement_mode, notification_mode,
                    created_at, completed_at, cancelled_at, device_id, revision
             FROM focus_sessions ORDER BY created_at DESC LIMIT 1",
            [],
            Self::row_to_session,
        );

        match row {
            Ok(session) => Ok(Some(session?)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    fn row_to_session(r: &rusqlite::Row) -> rusqlite::Result<Result<FocusSession>> {
        Ok((|| -> Result<FocusSession> {
            Ok(FocusSession {
                id: r.get(0)?,
                policy_id: r.get(1)?,
                session_type: str_to_session_type(&r.get::<_, String>(2)?)?,
                start_at: parse_time(&r.get::<_, String>(3)?)?,
                end_at: parse_time(&r.get::<_, String>(4)?)?,
                status: str_to_status(&r.get::<_, String>(5)?)?,
                enforcement_mode: str_to_enforcement_mode(&r.get::<_, String>(6)?)?,
                notification_mode: str_to_session_notification_mode(&r.get::<_, String>(7)?)?,
                created_at: parse_time(&r.get::<_, String>(8)?)?,
                completed_at: r
                    .get::<_, Option<String>>(9)?
                    .map(|s| parse_time(&s))
                    .transpose()?,
                cancelled_at: r
                    .get::<_, Option<String>>(10)?
                    .map(|s| parse_time(&s))
                    .transpose()?,
                device_id: r.get(11)?,
                revision: r.get(12)?,
            })
        })())
    }

    pub fn record_event(
        &self,
        session_id: &str,
        event_type: &str,
        metadata_json: Option<&str>,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO session_events (id, session_id, type, occurred_at, metadata_json) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![uuid::Uuid::new_v4().to_string(), session_id, event_type, Utc::now().to_rfc3339(), metadata_json],
        )?;
        Ok(())
    }

    /// Sessions created at or after `since`, most recent first. Backs the
    /// Stats screen's "today" figures — real numbers from real rows, not the
    /// mockup's hardcoded "6 days" / "2h 10m more than last week". Filters
    /// on `start_at`, not `created_at` — those happen to coincide today
    /// (every session is created at the moment it starts), but `start_at`
    /// is the one that stays correct once scheduled-for-later sessions
    /// exist (Phase 5+): a session scheduled yesterday for today should
    /// count as today's, not yesterday's.
    pub fn sessions_since(&self, since: DateTime<Utc>) -> Result<Vec<FocusSession>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, policy_id, session_type, start_at, end_at, status, enforcement_mode, notification_mode,
                    created_at, completed_at, cancelled_at, device_id, revision
             FROM focus_sessions WHERE start_at >= ?1 ORDER BY start_at DESC",
        )?;
        let mut sessions = Vec::new();
        for row in stmt.query_map(params![since.to_rfc3339()], Self::row_to_session)? {
            sessions.push(row??);
        }
        Ok(sessions)
    }

    pub fn count_events_since(&self, event_type: &str, since: DateTime<Utc>) -> Result<i64> {
        self.conn
            .query_row(
                "SELECT COUNT(*) FROM session_events WHERE type = ?1 AND occurred_at >= ?2",
                params![event_type, since.to_rfc3339()],
                |r| r.get(0),
            )
            .map_err(Into::into)
    }
}

fn parse_time(s: &str) -> Result<DateTime<Utc>> {
    Ok(DateTime::parse_from_rfc3339(s)?.with_timezone(&Utc))
}

fn mode_to_str(m: PolicyMode) -> &'static str {
    match m {
        PolicyMode::Blocklist => "blocklist",
        PolicyMode::Allowlist => "allowlist",
    }
}
fn str_to_mode(s: &str) -> Result<PolicyMode> {
    match s {
        "blocklist" => Ok(PolicyMode::Blocklist),
        "allowlist" => Ok(PolicyMode::Allowlist),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

fn notification_mode_to_str(m: NotificationMode) -> &'static str {
    match m {
        NotificationMode::Normal => "normal",
        NotificationMode::Quiet => "quiet",
        NotificationMode::Silent => "silent",
    }
}
fn str_to_notification_mode(s: &str) -> Result<NotificationMode> {
    match s {
        "normal" => Ok(NotificationMode::Normal),
        "quiet" => Ok(NotificationMode::Quiet),
        "silent" => Ok(NotificationMode::Silent),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

fn session_notification_mode_to_str(m: SessionNotificationMode) -> &'static str {
    match m {
        SessionNotificationMode::Normal => "normal",
        SessionNotificationMode::Quiet => "quiet",
        SessionNotificationMode::Silent => "silent",
    }
}
fn str_to_session_notification_mode(s: &str) -> Result<SessionNotificationMode> {
    match s {
        "normal" => Ok(SessionNotificationMode::Normal),
        "quiet" => Ok(SessionNotificationMode::Quiet),
        "silent" => Ok(SessionNotificationMode::Silent),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

fn status_to_str(s: FocusSessionStatus) -> &'static str {
    match s {
        FocusSessionStatus::Scheduled => "scheduled",
        FocusSessionStatus::Active => "active",
        FocusSessionStatus::Completed => "completed",
        FocusSessionStatus::Cancelled => "cancelled",
        FocusSessionStatus::Expired => "expired",
        FocusSessionStatus::Error => "error",
    }
}
fn str_to_status(s: &str) -> Result<FocusSessionStatus> {
    match s {
        "scheduled" => Ok(FocusSessionStatus::Scheduled),
        "active" => Ok(FocusSessionStatus::Active),
        "completed" => Ok(FocusSessionStatus::Completed),
        "cancelled" => Ok(FocusSessionStatus::Cancelled),
        "expired" => Ok(FocusSessionStatus::Expired),
        "error" => Ok(FocusSessionStatus::Error),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

fn enforcement_mode_to_str(m: EnforcementMode) -> &'static str {
    match m {
        EnforcementMode::Normal => "normal",
        EnforcementMode::Strict => "strict",
        EnforcementMode::Locked => "locked",
    }
}
fn str_to_enforcement_mode(s: &str) -> Result<EnforcementMode> {
    match s {
        "normal" => Ok(EnforcementMode::Normal),
        "strict" => Ok(EnforcementMode::Strict),
        "locked" => Ok(EnforcementMode::Locked),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

fn session_type_to_str(t: SessionType) -> &'static str {
    match t {
        SessionType::Focus => "focus",
        SessionType::BedtimeWinddown => "bedtime_winddown",
    }
}
fn str_to_session_type(s: &str) -> Result<SessionType> {
    match s {
        "focus" => Ok(SessionType::Focus),
        "bedtime_winddown" => Ok(SessionType::BedtimeWinddown),
        other => Err(PersistenceError::BadEnumValue(other.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session_engine;
    use chrono::Duration;

    #[test]
    fn policy_round_trips_with_its_rules() {
        let db = Database::open_in_memory().unwrap();
        let mut policy = BlockPolicy::new("Deep Work");
        policy.domains.push(DomainRule::new("youtube.com"));
        policy.applications.push(ApplicationRule::new(
            r"C:\Program Files\Steam\steam.exe",
            "Steam",
        ));

        db.save_policy(&policy).unwrap();
        let loaded = db
            .get_policy(&policy.id)
            .unwrap()
            .expect("policy should exist");

        assert_eq!(loaded.name, "Deep Work");
        assert_eq!(loaded.domains.len(), 1);
        assert_eq!(loaded.domains[0].domain, "youtube.com");
        assert_eq!(loaded.applications.len(), 1);
        assert_eq!(loaded.applications[0].display_name, "Steam");
    }

    #[test]
    fn profile_round_trips_with_feed_rules() {
        let db = Database::open_in_memory().unwrap();
        let mut profile = Profile::new("Deep Work");
        profile.policy.domains.push(DomainRule::new("youtube.com"));
        profile.feed_rules.push(crate::block_policy::FeedRule {
            id: uuid::Uuid::new_v4().to_string(),
            profile_id: profile.policy.id.clone(),
            feed_key: "shorts".to_string(),
            enabled: true,
        });

        db.save_profile(&profile).unwrap();
        let loaded = db.get_profile(&profile.policy.id).unwrap().expect("profile should exist");
        assert_eq!(loaded.feed_rules.len(), 1);
        assert_eq!(loaded.feed_rules[0].feed_key, "shorts");

        let all = db.list_profiles().unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].policy.name, "Deep Work");
    }

    #[test]
    fn device_id_is_generated_once_and_then_stable() {
        let db = Database::open_in_memory().unwrap();
        let first = db.device_id().unwrap();
        let second = db.device_id().unwrap();
        assert_eq!(first, second);
        assert!(!first.is_empty());
    }

    #[test]
    fn the_full_loop_start_persist_recover_expire() {
        let db = Database::open_in_memory().unwrap();
        let policy = BlockPolicy::new("Study");
        db.save_policy(&policy).unwrap();

        // start
        let now = Utc::now();
        let session = FocusSession::new(&policy.id, now, now + Duration::minutes(25), "this-pc");
        db.save_session(&session).unwrap();
        db.record_event(&session.id, "SESSION_STARTED", None)
            .unwrap();

        // persist + recover: reload as if the app just restarted
        let mut recovered = db
            .most_recent_session()
            .unwrap()
            .expect("a session should be persisted");
        assert_eq!(recovered.id, session.id);
        assert!(session_engine::is_active(&recovered, now));

        // expire: recovery recomputes from end_at, not a UI countdown
        let after_end = session.end_at + Duration::seconds(1);
        session_engine::evaluate_state(&mut recovered, after_end);
        assert_eq!(recovered.status, FocusSessionStatus::Completed);
        assert!(!session_engine::is_active(&recovered, after_end));

        db.save_session(&recovered).unwrap();
        db.record_event(&recovered.id, "SESSION_COMPLETED", None)
            .unwrap();

        let final_state = db.most_recent_session().unwrap().unwrap();
        assert_eq!(final_state.status, FocusSessionStatus::Completed);
    }

    #[test]
    fn sessions_since_and_event_counts_are_real_numbers() {
        let db = Database::open_in_memory().unwrap();
        let policy = BlockPolicy::new("Study");
        db.save_policy(&policy).unwrap();

        let now = Utc::now();
        let old = FocusSession::new(&policy.id, now - Duration::days(10), now - Duration::days(10) + Duration::minutes(25), "this-pc");
        db.save_session(&old).unwrap();

        let today = FocusSession::new(&policy.id, now, now + Duration::minutes(25), "this-pc");
        db.save_session(&today).unwrap();
        db.record_event(&today.id, "SESSION_STARTED", None).unwrap();
        db.record_event(&today.id, "BLOCK_ATTEMPT", None).unwrap();
        db.record_event(&today.id, "BLOCK_ATTEMPT", None).unwrap();

        let since = now - Duration::hours(1);
        let recent = db.sessions_since(since).unwrap();
        assert_eq!(recent.len(), 1, "the 10-day-old session shouldn't count as recent");
        assert_eq!(recent[0].id, today.id);

        assert_eq!(db.count_events_since("BLOCK_ATTEMPT", since).unwrap(), 2);
        assert_eq!(db.count_events_since("SESSION_STARTED", since).unwrap(), 1);
    }
}
