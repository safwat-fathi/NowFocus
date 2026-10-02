//! Cross-device sessions (services/api/WIRE_FORMAT.md section 6): a port of Android's `SessionWire` and
//! `RepositorySessionSync`. Only user-started focus sessions travel: Bedtime and schedules run on each device
//! from its own settings, so syncing them too would start them N times.
//!
//! Joining enforces on this PC something another device decided, so it is bounded: a day at most (the server
//! enforces the same cap), never already over, never from a device the user switched off, and only for a
//! profile this PC already has (the live policy is what Windows enforces, and the account's profiles sync first).

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use now_focus_core::session_engine;
use now_focus_core::{
    EnforcementMode, FocusSession, FocusSessionStatus, Profile, SessionOrigin, SessionType,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::model::*;
use crate::wire::{self, iso, FEEDS};

pub const MAX_MS: i64 = 24 * 3_600_000;
const KV_KEY: &str = "session_sync";

/// A session some device pushed, reduced to what this PC needs to enforce it.
#[derive(Debug, Clone)]
pub struct RemoteSession {
    pub id: String,
    pub policy_id: String,
    pub status: String,
    pub mode: EnforcementMode,
    pub start_at: DateTime<Utc>,
    pub end_at: DateTime<Utc>,
    pub started_on: Option<String>,
    /// The server's JSON. A status change is pushed by merging into this, so another device's extras survive.
    pub raw: Value,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Decision {
    /// Start enforcing it here.
    Join,
    /// Worth joining, but a session is already running here: keep it and join when this one ends.
    Defer,
    /// The session running here was cancelled elsewhere.
    EndLocal,
    /// The session running here was extended elsewhere.
    Extend(DateTime<Utc>),
    Ignore,
}

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str)
}

fn time(v: &Value, key: &str) -> Option<DateTime<Utc>> {
    s(v, key)
        .and_then(|t| DateTime::parse_from_rfc3339(t).ok())
        .map(|t| t.with_timezone(&Utc))
}

pub fn parse(r: &ServerRecord) -> Option<RemoteSession> {
    if r.typ != SESSION || r.deleted {
        return None;
    }
    let o = &r.data;
    if s(o, "sessionType") != Some("focus") || s(o, "source").unwrap_or("user") != "user" {
        return None;
    }
    let mode = match s(o, "enforcementMode")? {
        "normal" => EnforcementMode::Normal,
        "strict" => EnforcementMode::Strict,
        "locked" => EnforcementMode::Locked,
        _ => return None,
    };
    Some(RemoteSession {
        id: r.id.to_lowercase(),
        policy_id: s(o, "policyId")?.to_lowercase(),
        status: s(o, "status")?.to_string(),
        mode,
        start_at: time(o, "startAt")?,
        end_at: time(o, "endAt")?,
        started_on: s(o, "startedOn").map(String::from),
        raw: o.clone(),
    })
}

pub fn decide(
    remote: &RemoteSession,
    local: Option<&FocusSession>,
    now: DateTime<Utc>,
    join_enabled: bool,
) -> Decision {
    if let Some(l) = local.filter(|l| l.id == remote.id) {
        let active = l.status == FocusSessionStatus::Active;
        return if remote.status == "cancelled" && active {
            Decision::EndLocal
        } else if remote.end_at > l.end_at
            && (remote.end_at - l.start_at).num_milliseconds() <= MAX_MS
        {
            Decision::Extend(remote.end_at)
        } else {
            Decision::Ignore
        };
    }
    if !join_enabled {
        return Decision::Ignore;
    }
    let running =
        remote.status == "active" || (remote.status == "scheduled" && remote.start_at <= now);
    if !running
        || remote.end_at <= now
        || (remote.end_at - remote.start_at).num_milliseconds() > MAX_MS
    {
        return Decision::Ignore;
    }
    let busy = local.is_some_and(|l| session_engine::is_active(l, now));
    if busy {
        Decision::Defer
    } else {
        Decision::Join
    }
}

pub fn wire_status(st: FocusSessionStatus) -> &'static str {
    match st {
        FocusSessionStatus::Scheduled => "scheduled",
        FocusSessionStatus::Active => "active",
        FocusSessionStatus::Completed => "completed",
        FocusSessionStatus::Cancelled => "cancelled",
        FocusSessionStatus::Expired => "expired",
        FocusSessionStatus::Error => "error",
    }
}

fn wire_mode(m: EnforcementMode) -> &'static str {
    match m {
        EnforcementMode::Normal => "normal",
        EnforcementMode::Strict => "strict",
        EnforcementMode::Locked => "locked",
    }
}

/// The session as the server stores it. With `base` (a session joined from elsewhere) only what changes is touched.
pub fn to_wire(
    sess: &FocusSession,
    device_name: &str,
    profile: Option<&Profile>,
    base: Option<&Value>,
) -> Value {
    let mut o = base.cloned().unwrap_or_else(|| json!({}));
    o["id"] = json!(sess.id);
    o["policyId"] = json!(sess.policy_id.to_lowercase());
    o["sessionType"] = json!("focus");
    if o.get("source").is_none() {
        o["source"] = json!("user");
    }
    o["status"] = json!(wire_status(sess.status));
    o["enforcementMode"] = json!(wire_mode(sess.enforcement_mode));
    o["startAt"] = json!(iso(sess.start_at));
    o["endAt"] = json!(iso(sess.end_at));
    if let Some(c) = sess.cancelled_at {
        o["cancelledAt"] = json!(iso(c));
    }
    if base.is_none() {
        o["startedOn"] = json!(device_name);
        let (domains, apps, partial) = match profile {
            Some(p) => (
                p.policy
                    .domains
                    .iter()
                    .map(|d| json!({ "domain": d.domain, "includeSubdomains": d.include_subdomains, "enabled": d.enabled }))
                    .collect::<Vec<_>>(),
                p.policy
                    .applications
                    .iter()
                    .map(|a| json!({ "platform": wire::PLATFORM, "nativeIdentifier": a.native_identifier, "displayName": a.display_name, "enabled": a.enabled }))
                    .collect::<Vec<_>>(),
                FEEDS
                    .iter()
                    .filter(|(key, _)| p.feed_rules.iter().any(|f| f.enabled && f.feed_key == *key))
                    .map(|(_, name)| json!(name))
                    .collect::<Vec<_>>(),
            ),
            None => (vec![], vec![], vec![]),
        };
        o["policySnapshot"] =
            json!({ "domainRules": domains, "applicationRules": apps, "partial": partial });
    }
    o
}

/// What "already told the server" means: status, end and cancel time.
pub fn fingerprint(sess: &FocusSession) -> String {
    format!(
        "{}|{}|{}",
        wire_status(sess.status),
        sess.end_at.timestamp_millis(),
        sess.cancelled_at.map(|c| c.timestamp_millis()).unwrap_or(0)
    )
}

/// Only these ever leave the device.
pub fn syncs(sess: &FocusSession) -> bool {
    sess.session_type == SessionType::Focus && sess.origin != SessionOrigin::Schedule
}

// ---------------------------------------------------------------- bookkeeping

/// Which state of a session the server already has (`acked`, by fingerprint), the server's JSON for sessions we
/// joined or pushed (`raws`), and sessions worth joining that had to wait (`pending`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct SessionSyncState {
    #[serde(default)]
    pub acked: HashMap<String, String>,
    #[serde(default)]
    pub raws: HashMap<String, Value>,
    #[serde(default)]
    pub pending: HashMap<String, Value>,
}

impl SessionSyncState {
    /// Only what could still matter: the running session and anything waiting.
    pub fn pruned(mut self, keep: &[String]) -> Self {
        let pending: Vec<String> = self.pending.keys().cloned().collect();
        let wanted = |id: &String| keep.contains(id) || pending.contains(id);
        self.acked.retain(|k, _| wanted(k));
        self.raws.retain(|k, _| wanted(k));
        self
    }
}

pub trait SessionHost {
    /// The newest local session, status evaluated against the clock.
    fn local_session(&self) -> Result<Option<FocusSession>, String>;
    fn profile(&self, id: &str) -> Result<Option<Profile>, String>;
    fn join_enabled(&self) -> Result<bool, String>;
    /// Starts enforcing `remote` here. `false` when it can't (the profile isn't here, or it's an allowlist).
    fn join(&self, remote: &RemoteSession) -> Result<bool, String>;
    fn end_local(&self, id: &str) -> Result<(), String>;
    fn extend_local(&self, id: &str, end: DateTime<Utc>) -> Result<(), String>;
    fn device_name(&self) -> String;
    fn kv_get(&self, key: &str) -> Result<Option<String>, String>;
    fn kv_set(&self, key: &str, value: &str) -> Result<(), String>;
}

fn load(host: &dyn SessionHost) -> SessionSyncState {
    host.kv_get(KV_KEY)
        .ok()
        .flatten()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn store(host: &dyn SessionHost, st: &SessionSyncState) -> Result<(), String> {
    host.kv_set(
        KV_KEY,
        &serde_json::to_string(st).map_err(|e| e.to_string())?,
    )
}

/// Every `session` record from the pull (possibly none: also the moment to join one that was waiting).
pub fn on_pulled(
    host: &dyn SessionHost,
    records: &[ServerRecord],
    now: DateTime<Utc>,
) -> Result<(), String> {
    let join_enabled = host.join_enabled()?;
    let mut st = load(host);
    for remote in records.iter().filter_map(parse) {
        let local = host.local_session()?;
        match decide(&remote, local.as_ref(), now, join_enabled) {
            Decision::EndLocal => {
                if let Some(l) = local {
                    host.end_local(&l.id)?;
                    let mut ended = l;
                    ended.status = FocusSessionStatus::Cancelled;
                    ended.cancelled_at = Some(now);
                    st.acked.insert(ended.id.clone(), fingerprint(&ended)); // the server already knows
                }
            }
            Decision::Extend(end) => {
                if let Some(l) = local {
                    host.extend_local(&l.id, end)?;
                }
            }
            Decision::Join | Decision::Defer => {
                st.pending.insert(remote.id.clone(), remote.raw.clone());
            }
            Decision::Ignore => {}
        }
    }
    store(host, &st)?;
    join_waiting(host, now, join_enabled)
}

fn join_waiting(
    host: &dyn SessionHost,
    now: DateTime<Utc>,
    join_enabled: bool,
) -> Result<(), String> {
    let local = host.local_session()?;
    let mut st = load(host);
    let waiting: Vec<RemoteSession> = st
        .pending
        .values()
        .filter_map(|raw| {
            let id = s(raw, "id")?.to_string();
            parse(&ServerRecord {
                typ: SESSION.into(),
                id,
                data: raw.clone(),
                deleted: false,
                revision: 0,
                updated_at: now,
            })
        })
        .collect();
    let best = waiting
        .iter()
        .filter(|r| join_enabled && decide(r, local.as_ref(), now, true) == Decision::Join)
        .max_by_key(|r| r.end_at)
        .cloned();
    match best {
        None => {
            // Drop what is over, so the list can't grow forever.
            let live: Vec<String> = waiting
                .iter()
                .filter(|r| r.end_at > now)
                .map(|r| r.id.clone())
                .collect();
            st.pending.retain(|k, _| live.contains(k));
            store(host, &st)
        }
        Some(remote) => {
            if host.join(&remote)? {
                let joined_fp = format!("active|{}|0", remote.end_at.timestamp_millis());
                st.acked.insert(remote.id.clone(), joined_fp); // joining changes nothing the server must hear
                st.raws.insert(remote.id.clone(), remote.raw.clone());
                st.pending.remove(&remote.id);
            }
            store(host, &st)
        }
    }
}

pub fn plan(host: &dyn SessionHost, now: DateTime<Utc>) -> Result<Vec<Outgoing>, String> {
    let Some(sess) = host.local_session()?.filter(syncs) else {
        return Ok(vec![]);
    };
    let fp = fingerprint(&sess);
    let st = load(host);
    if st.acked.get(&sess.id) == Some(&fp) {
        return Ok(vec![]);
    }
    let profile = host.profile(&sess.policy_id)?;
    let data = to_wire(
        &sess,
        &host.device_name(),
        profile.as_ref(),
        st.raws.get(&sess.id),
    );
    Ok(vec![Outgoing {
        typ: SESSION.into(),
        id: sess.id,
        updated_at: now,
        data: Some(data),
        deleted: false,
        fingerprint: fp,
    }])
}

pub fn on_pushed(
    host: &dyn SessionHost,
    sent: &[Outgoing],
    results: &[PushOutcome],
    now: DateTime<Utc>,
) -> Result<(), String> {
    let keep: Vec<String> = host
        .local_session()?
        .map(|s| vec![s.id])
        .unwrap_or_default();
    let mut stale = Vec::new();
    for res in results {
        let Some(out) = sent
            .iter()
            .find(|o| o.id.to_lowercase() == res.id.to_lowercase())
        else {
            continue;
        };
        let mut st = load(host);
        // Applied, already known or refused: all mean "don't send this exact state again". A new state has a new fingerprint.
        st.acked
            .insert(out.id.to_lowercase(), out.fingerprint.clone());
        if res.status != "rejected" {
            if let Some(rec) = &res.record {
                st.raws.insert(out.id.to_lowercase(), rec.data.clone());
            }
        }
        store(host, &st.pruned(&keep))?;
        if res.status == "stale" {
            if let Some(rec) = &res.record {
                stale.push(rec.clone());
            }
        }
    }
    if stale.is_empty() {
        Ok(())
    } else {
        on_pulled(host, &stale, now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    fn now() -> DateTime<Utc> {
        DateTime::parse_from_rfc3339("2026-10-02T12:00:00Z")
            .unwrap()
            .with_timezone(&Utc)
    }

    #[allow(clippy::too_many_arguments)]
    fn record(
        id: &str,
        status: &str,
        mode: &str,
        typ: &str,
        source: Option<&str>,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> ServerRecord {
        let mut o = json!({
            "id": id, "policyId": "P1", "sessionType": typ, "status": status, "enforcementMode": mode,
            "startAt": iso(start), "endAt": iso(end), "startedOn": "Galaxy",
            "policySnapshot": { "domainRules": [{ "domain": "youtube.com", "enabled": true }], "applicationRules": [], "partial": [] },
        });
        if let Some(src) = source {
            o["source"] = json!(src);
        }
        ServerRecord {
            typ: SESSION.into(),
            id: id.into(),
            data: o,
            deleted: false,
            revision: 1,
            updated_at: now(),
        }
    }

    fn running(id: &str) -> ServerRecord {
        record(
            id,
            "active",
            "normal",
            "focus",
            Some("user"),
            now() - Duration::minutes(30),
            now() + Duration::hours(1),
        )
    }

    fn local(id: &str, status: FocusSessionStatus, end: DateTime<Utc>) -> FocusSession {
        let mut s = FocusSession::new("p1", now() - Duration::minutes(30), end, "dev");
        s.id = id.into();
        s.status = status;
        s
    }

    #[test]
    fn only_user_started_focus_sessions_are_read() {
        assert!(parse(&running("a")).is_some());
        assert_eq!(parse(&running("A")).unwrap().id, "a");
        assert!(parse(&record(
            "a",
            "active",
            "normal",
            "bedtime_winddown",
            Some("user"),
            now(),
            now()
        ))
        .is_none());
        assert!(parse(&record(
            "a",
            "active",
            "normal",
            "focus",
            Some("schedule"),
            now(),
            now()
        ))
        .is_none());
        assert!(parse(&record(
            "a",
            "active",
            "weird",
            "focus",
            Some("user"),
            now(),
            now()
        ))
        .is_none());
        assert!(parse(&record(
            "a",
            "active",
            "normal",
            "focus",
            None,
            now(),
            now()
        ))
        .is_some()); // no source means user
        assert!(parse(&ServerRecord {
            deleted: true,
            ..running("a")
        })
        .is_none());
    }

    #[test]
    fn joins_when_free_and_waits_behind_a_running_session() {
        let r = parse(&running("a")).unwrap();
        assert_eq!(decide(&r, None, now(), true), Decision::Join);
        assert_eq!(
            decide(
                &r,
                Some(&local(
                    "x",
                    FocusSessionStatus::Completed,
                    now() - Duration::minutes(1)
                )),
                now(),
                true
            ),
            Decision::Join
        );
        assert_eq!(
            decide(
                &r,
                Some(&local(
                    "x",
                    FocusSessionStatus::Active,
                    now() + Duration::hours(1)
                )),
                now(),
                true
            ),
            Decision::Defer
        );
        assert_eq!(decide(&r, None, now(), false), Decision::Ignore);
    }

    #[test]
    fn never_joins_what_is_over_not_started_or_longer_than_a_day() {
        let t = now();
        let d = |status, start, end| {
            decide(
                &parse(&record(
                    "a",
                    status,
                    "normal",
                    "focus",
                    Some("user"),
                    start,
                    end,
                ))
                .unwrap(),
                None,
                t,
                true,
            )
        };
        assert_eq!(
            d("active", t - Duration::hours(1), t - Duration::seconds(1)),
            Decision::Ignore
        );
        assert_eq!(
            d("completed", t - Duration::hours(1), t + Duration::hours(1)),
            Decision::Ignore
        );
        assert_eq!(
            d("cancelled", t - Duration::hours(1), t + Duration::hours(1)),
            Decision::Ignore
        );
        assert_eq!(
            d(
                "active",
                t - Duration::hours(1),
                t - Duration::hours(1) + Duration::hours(24) + Duration::seconds(1)
            ),
            Decision::Ignore
        );
        assert_eq!(
            d(
                "active",
                t - Duration::hours(1),
                t - Duration::hours(1) + Duration::hours(24)
            ),
            Decision::Join
        );
        assert_eq!(
            d("scheduled", t + Duration::hours(1), t + Duration::hours(2)),
            Decision::Ignore
        );
    }

    #[test]
    fn a_session_cancelled_or_extended_elsewhere_changes_the_same_session_here() {
        let mine = local("a", FocusSessionStatus::Active, now() + Duration::hours(1));
        let cancelled = parse(&record(
            "a",
            "cancelled",
            "normal",
            "focus",
            Some("user"),
            now() - Duration::minutes(30),
            now() + Duration::hours(1),
        ))
        .unwrap();
        assert_eq!(
            decide(&cancelled, Some(&mine), now(), true),
            Decision::EndLocal
        );
        assert_eq!(
            decide(
                &cancelled,
                Some(&local(
                    "b",
                    FocusSessionStatus::Active,
                    now() + Duration::hours(1)
                )),
                now(),
                true
            ),
            Decision::Ignore
        );

        let longer = parse(&record(
            "a",
            "active",
            "normal",
            "focus",
            Some("user"),
            now() - Duration::minutes(30),
            now() + Duration::hours(2),
        ))
        .unwrap();
        assert_eq!(
            decide(&longer, Some(&mine), now(), true),
            Decision::Extend(now() + Duration::hours(2))
        );
        let shorter = parse(&record(
            "a",
            "active",
            "normal",
            "focus",
            Some("user"),
            now() - Duration::minutes(30),
            now() + Duration::minutes(10),
        ))
        .unwrap();
        assert_eq!(decide(&shorter, Some(&mine), now(), true), Decision::Ignore);
    }

    #[test]
    fn a_started_session_goes_out_with_a_snapshot_and_a_joined_one_keeps_the_other_devices_extras()
    {
        let mut p = Profile::new("Deep Work");
        p.policy
            .domains
            .push(now_focus_core::DomainRule::new("youtube.com"));
        p.policy
            .applications
            .push(now_focus_core::ApplicationRule::new(r"C:\a.exe", "A"));
        let sess = local(
            "abc",
            FocusSessionStatus::Active,
            now() + Duration::hours(1),
        );
        let o = to_wire(&sess, "My PC", Some(&p), None);
        assert_eq!(o["sessionType"], "focus");
        assert_eq!(o["source"], "user");
        assert_eq!(o["startedOn"], "My PC");
        assert_eq!(
            o["policySnapshot"]["domainRules"][0]["domain"],
            "youtube.com"
        );
        assert_eq!(
            o["policySnapshot"]["applicationRules"][0]["platform"],
            "windows"
        );

        let mut base = running("r1").data;
        base["extraFromAndroid"] = json!("keep me");
        let mut cancelled = local(
            "r1",
            FocusSessionStatus::Cancelled,
            now() + Duration::hours(1),
        );
        cancelled.cancelled_at = Some(now());
        let o2 = to_wire(&cancelled, "My PC", Some(&p), Some(&base));
        assert_eq!(o2["status"], "cancelled");
        assert_eq!(o2["extraFromAndroid"], "keep me");
        assert_eq!(o2["startedOn"], "Galaxy");
        assert_eq!(o2["policySnapshot"], base["policySnapshot"]);
    }

    #[test]
    fn fingerprint_changes_when_what_the_server_should_hear_changes() {
        let s = local("a", FocusSessionStatus::Active, now() + Duration::hours(1));
        let mut cancelled = s.clone();
        cancelled.status = FocusSessionStatus::Cancelled;
        cancelled.cancelled_at = Some(now());
        assert_ne!(fingerprint(&s), fingerprint(&cancelled));
        let mut longer = s.clone();
        longer.end_at += Duration::minutes(1);
        assert_ne!(fingerprint(&s), fingerprint(&longer));
        assert_eq!(fingerprint(&s), fingerprint(&s.clone()));
    }

    #[test]
    fn bedtime_and_schedule_sessions_never_leave_the_device() {
        let mut s = local("a", FocusSessionStatus::Active, now() + Duration::hours(1));
        assert!(syncs(&s));
        s.origin = SessionOrigin::Remote;
        assert!(syncs(&s));
        s.origin = SessionOrigin::Schedule;
        assert!(!syncs(&s));
        s.origin = SessionOrigin::User;
        s.session_type = SessionType::BedtimeWinddown;
        assert!(!syncs(&s));
    }

    #[test]
    fn bookkeeping_survives_json_and_keeps_only_what_matters() {
        let mut st = SessionSyncState::default();
        st.acked.insert("a".into(), "fp".into());
        st.acked.insert("b".into(), "fp".into());
        st.pending.insert("c".into(), json!({}));
        let back: SessionSyncState =
            serde_json::from_str(&serde_json::to_string(&st).unwrap()).unwrap();
        assert_eq!(back, st);
        let pruned = st.pruned(&["a".to_string()]);
        assert!(pruned.acked.contains_key("a") && !pruned.acked.contains_key("b"));
        assert!(pruned.pending.contains_key("c"));
    }
}
