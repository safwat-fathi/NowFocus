//! One sync pass: pull everything new and apply it, then push what changed here. Passes never overlap. Network
//! errors propagate untouched (the caller decides when to retry); local data is only ever changed through
//! [`logic`], inside [`Host::transact`].

use std::collections::HashSet;
use std::sync::Mutex;

use chrono::Utc;

use crate::api::{ApiError, ApiPort};
use crate::logic;
use crate::model::*;
use crate::session::{self, SessionHost};

/// The local side of sync. `transact` must be one atomic read-modify-write of profiles, bedtime and the sync
/// state together (the app does it under its state lock).
pub trait Host: SessionHost {
    fn transact(&self, f: &mut dyn FnMut(Local) -> Local) -> Result<(), String>;
    /// Profiles something still points at (bedtime's profile, the current session).
    fn referenced_policy_ids(&self) -> Result<HashSet<String>, String>;
}

/// `rejected` = changes the server refused that are still outstanding (not just in this pass).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SyncReport {
    pub pulled: usize,
    pub pushed: usize,
    pub rejected: usize,
}

#[derive(Debug, Clone)]
pub enum SyncError {
    Api(ApiError),
    Host(String),
}

impl From<ApiError> for SyncError {
    fn from(e: ApiError) -> Self {
        SyncError::Api(e)
    }
}

impl From<String> for SyncError {
    fn from(e: String) -> Self {
        SyncError::Host(e)
    }
}

const MAX_PAGES: usize = 1000;
const MAX_PUSH_ROUNDS: usize = 3;
const MAX_CHANGES_PER_PUSH: usize = 100; // the server's per-request limit

pub struct Engine<H: Host, A: ApiPort> {
    host: H,
    api: A,
    lock: Mutex<()>,
}

impl<H: Host, A: ApiPort> Engine<H, A> {
    pub fn new(host: H, api: A) -> Self {
        Self {
            host,
            api,
            lock: Mutex::new(()),
        }
    }

    pub fn host(&self) -> &H {
        &self.host
    }

    fn read<R>(&self, f: impl Fn(&Local) -> R) -> Result<R, String> {
        let mut out = None;
        self.host.transact(&mut |l| {
            out = Some(f(&l));
            l
        })?;
        Ok(out.expect("transact ran"))
    }

    pub fn sync_once(&self) -> Result<SyncReport, SyncError> {
        let _guard = self.lock.lock().unwrap();
        let mut report = SyncReport::default();
        let mut session_records: Vec<ServerRecord> = Vec::new();

        // 1. Pull until caught up. Each page and its cursor are applied in one atomic step.
        for _ in 0..MAX_PAGES {
            let Some(cursor) = self.read(|l| l.state.user_id.as_ref().map(|_| l.state.cursor))?
            else {
                return Ok(SyncReport::default()); // signed out: no network at all
            };
            let page = match self.api.pull(cursor, 500) {
                Ok(p) => p,
                // The cursor is older than the server's history: start over and reconcile from the full set.
                Err(ApiError::Http { status: 410, .. }) => {
                    self.host.transact(&mut |mut l| {
                        l.state.cursor = 0;
                        l
                    })?;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            self.host.transact(&mut |l| {
                if l.state.user_id.is_none() {
                    l
                } else {
                    logic::apply_pulled(&l, &page.changes, page.cursor).local
                }
            })?;
            report.pulled += page.changes.len();
            session_records.extend(page.changes.iter().filter(|r| r.typ == SESSION).cloned());
            if !page.has_more || page.cursor <= cursor {
                break;
            }
        }
        // Even with nothing new: a session that had to wait for the running one to end is joined now.
        if self.read(|l| l.state.user_id.is_some())? {
            session::on_pulled(&self.host, &session_records, Utc::now())?;
        }

        // 2. The first pull after linking is complete: decide what the account already has versus what only this PC has.
        let referenced = self.host.referenced_policy_ids()?;
        self.host.transact(&mut |l| {
            if l.state.user_id.is_some() && !l.state.initial_pull_done {
                logic::finish_initial_pull(&l, &referenced)
            } else {
                l
            }
        })?;

        // 3. Push. A stale answer can leave something new to send (a rebased edit), so look again, a few times at most.
        for _ in 0..MAX_PUSH_ROUNDS {
            let now = Utc::now();
            // Profiles go before the session that points at them.
            let mut plan = self.read(|l| logic::plan_push(l, now))?;
            if self.read(|l| l.state.user_id.is_some() && l.state.initial_pull_done)? {
                plan.extend(session::plan(&self.host, now)?);
            }
            if plan.is_empty() {
                break;
            }
            for chunk in plan.chunks(MAX_CHANGES_PER_PUSH) {
                let results = self.api.push(chunk)?;
                // Sessions have their own bookkeeping: `logic` must never see their outcomes.
                let (session_sent, other_sent): (Vec<Outgoing>, Vec<Outgoing>) =
                    chunk.iter().cloned().partition(|o| o.typ == SESSION);
                let (session_results, other_results): (Vec<PushOutcome>, Vec<PushOutcome>) =
                    results.into_iter().partition(|r| r.typ == SESSION);
                self.host.transact(&mut |l| {
                    if l.state.user_id.is_none() {
                        l
                    } else {
                        logic::apply_push_results(&l, &other_sent, &other_results).local
                    }
                })?;
                if !session_sent.is_empty() {
                    session::on_pushed(&self.host, &session_sent, &session_results, Utc::now())?;
                }
                report.pushed += chunk.len();
            }
        }
        report.rejected = self.read(|l| logic::rejected_count(&l.state))?;
        Ok(report)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::iso;
    use chrono::{DateTime, Duration};
    use now_focus_core::{
        ApplicationRule, BedtimeSettings, DomainRule, FocusSession, FocusSessionStatus, Profile,
        SessionOrigin,
    };
    use serde_json::{json, Value};
    use std::collections::HashMap;
    use std::sync::Arc;

    // ---- a minimal server: last-write-wins records, a sequence, sessions stored as pushed

    /// data, updated_at, seq, revision
    type Stored = (Value, DateTime<Utc>, i64, i64);
    type Pc = (FakeHost, Engine<FakeHost, FakeApi>);

    #[derive(Default)]
    struct Server {
        seq: i64,
        records: HashMap<(String, String), Stored>,
        calls: usize,
    }

    #[derive(Clone, Default)]
    struct FakeApi(Arc<Mutex<Server>>);

    fn rec(typ: &str, id: &str, r: &Stored) -> ServerRecord {
        ServerRecord {
            typ: typ.into(),
            id: id.into(),
            data: r.0.clone(),
            deleted: false,
            revision: r.3,
            updated_at: r.1,
        }
    }

    impl ApiPort for FakeApi {
        fn pull(&self, cursor: i64, _limit: u32) -> Result<Page, ApiError> {
            let mut s = self.0.lock().unwrap();
            s.calls += 1;
            let mut changes: Vec<_> = s
                .records
                .iter()
                .filter(|(_, v)| v.2 > cursor)
                .map(|(k, v)| (v.2, rec(&k.0, &k.1, v)))
                .collect();
            changes.sort_by_key(|c| c.0);
            Ok(Page {
                changes: changes.into_iter().map(|c| c.1).collect(),
                cursor: s.seq,
                has_more: false,
            })
        }

        fn push(&self, changes: &[Outgoing]) -> Result<Vec<PushOutcome>, ApiError> {
            let mut s = self.0.lock().unwrap();
            s.calls += 1;
            let mut out = Vec::new();
            for c in changes {
                let key = (c.typ.clone(), c.id.to_lowercase());
                let data = c.data.clone().unwrap_or_else(|| json!({}));
                let status = match s.records.get(&key).cloned() {
                    Some(prev) if c.typ != SESSION && c.updated_at <= prev.1 => ("stale", prev),
                    Some(prev) if prev.0 == data => ("stale", prev),
                    other => {
                        s.seq += 1;
                        let revision = other.map(|p| p.3 + 1).unwrap_or(1);
                        let v = (data, c.updated_at, s.seq, revision);
                        s.records.insert(key.clone(), v.clone());
                        ("applied", v)
                    }
                };
                out.push(PushOutcome {
                    typ: c.typ.clone(),
                    id: c.id.clone(),
                    status: status.0.into(),
                    record: Some(rec(&c.typ, &c.id, &status.1)),
                    code: None,
                });
            }
            Ok(out)
        }
    }

    // ---- a fake PC

    struct Inner {
        local: Local,
        session: Option<FocusSession>,
        kv: HashMap<String, String>,
        join_enabled: bool,
    }

    #[derive(Clone)]
    struct FakeHost(Arc<Mutex<Inner>>);

    impl FakeHost {
        fn new(name: &str) -> (Self, Engine<FakeHost, FakeApi>) {
            let _ = name;
            let seed = Profile::new("Deep Work");
            let host = FakeHost(Arc::new(Mutex::new(Inner {
                local: Local {
                    profiles: vec![seed],
                    bedtime: BedtimeSettings::default(),
                    bedtime_updated_at: None,
                    state: SyncState::default(),
                },
                session: None,
                kv: HashMap::new(),
                join_enabled: true,
            })));
            (host.clone(), Engine::new(host, FakeApi::default()))
        }

        fn signed_in(&self, user: &str) {
            let mut i = self.0.lock().unwrap();
            i.local.state = logic::link(&i.local.state, user);
        }

        fn edit(&self, f: impl FnOnce(&mut Local)) {
            f(&mut self.0.lock().unwrap().local);
        }

        fn profiles(&self) -> Vec<Profile> {
            self.0.lock().unwrap().local.profiles.clone()
        }
    }

    impl SessionHost for FakeHost {
        fn local_session(&self) -> Result<Option<FocusSession>, String> {
            let mut s = self.0.lock().unwrap().session.clone();
            if let Some(x) = s.as_mut() {
                now_focus_core::session_engine::evaluate_state(x, Utc::now());
            }
            Ok(s)
        }
        fn profile(&self, id: &str) -> Result<Option<Profile>, String> {
            Ok(self
                .0
                .lock()
                .unwrap()
                .local
                .profiles
                .iter()
                .find(|p| p.policy.id == id)
                .cloned())
        }
        fn join_enabled(&self) -> Result<bool, String> {
            Ok(self.0.lock().unwrap().join_enabled)
        }
        fn join(&self, r: &session::RemoteSession) -> Result<bool, String> {
            let mut i = self.0.lock().unwrap();
            if !i.local.profiles.iter().any(|p| p.policy.id == r.policy_id) {
                return Ok(false);
            }
            let mut s = FocusSession::new(&r.policy_id, r.start_at, r.end_at, "dev");
            s.id = r.id.clone();
            s.enforcement_mode = r.mode;
            s.origin = SessionOrigin::Remote;
            i.session = Some(s);
            Ok(true)
        }
        fn end_local(&self, id: &str) -> Result<(), String> {
            let mut i = self.0.lock().unwrap();
            if let Some(s) = i.session.as_mut().filter(|s| s.id == id) {
                s.status = FocusSessionStatus::Cancelled;
                s.cancelled_at = Some(Utc::now());
            }
            Ok(())
        }
        fn extend_local(&self, id: &str, end: DateTime<Utc>) -> Result<(), String> {
            let mut i = self.0.lock().unwrap();
            if let Some(s) = i.session.as_mut().filter(|s| s.id == id) {
                s.end_at = end;
            }
            Ok(())
        }
        fn device_name(&self) -> String {
            "Test PC".into()
        }
        fn kv_get(&self, k: &str) -> Result<Option<String>, String> {
            Ok(self.0.lock().unwrap().kv.get(k).cloned())
        }
        fn kv_set(&self, k: &str, v: &str) -> Result<(), String> {
            self.0.lock().unwrap().kv.insert(k.into(), v.into());
            Ok(())
        }
    }

    impl Host for FakeHost {
        fn transact(&self, f: &mut dyn FnMut(Local) -> Local) -> Result<(), String> {
            let mut i = self.0.lock().unwrap();
            let next = f(i.local.clone());
            i.local = next;
            Ok(())
        }
        fn referenced_policy_ids(&self) -> Result<HashSet<String>, String> {
            let i = self.0.lock().unwrap();
            Ok(i.local
                .bedtime
                .policy_id
                .iter()
                .cloned()
                .chain(i.session.iter().map(|s| s.policy_id.clone()))
                .collect())
        }
    }

    /// Two PCs on one account and one server.
    fn pair() -> (Pc, Pc) {
        let (ha, _) = FakeHost::new("a");
        let (hb, _) = FakeHost::new("b");
        let api = FakeApi::default();
        let ea = Engine::new(ha.clone(), api.clone());
        let eb = Engine::new(hb.clone(), api);
        ha.signed_in("user");
        hb.signed_in("user");
        ((ha, ea), (hb, eb))
    }

    fn touch(p: &mut Profile) {
        p.policy.updated_at = Utc::now() - Duration::seconds(60);
    }

    #[test]
    fn a_signed_out_pc_makes_no_network_call() {
        let (_h, e) = FakeHost::new("a");
        assert_eq!(e.sync_once().unwrap(), SyncReport::default());
        assert_eq!(e.api.0.lock().unwrap().calls, 0);
    }

    #[test]
    fn a_profile_made_on_one_pc_reaches_the_other_and_the_untouched_starter_is_not_duplicated() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            let p = &mut l.profiles[0];
            p.policy.domains.push(DomainRule::new("youtube.com"));
            p.policy
                .applications
                .push(ApplicationRule::new(r"C:\Games\steam.exe", "Steam"));
            touch(p);
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();

        let b = hb.profiles();
        assert_eq!(
            b.len(),
            1,
            "B's untouched starter was replaced by the account's profile, not added beside it"
        );
        assert_eq!(b[0].policy.domains[0].domain, "youtube.com");
        assert_eq!(b[0].policy.applications[0].display_name, "Steam");
        assert_eq!(b[0].policy.id, ha.profiles()[0].policy.id);
    }

    #[test]
    fn an_edit_on_the_other_pc_comes_back_and_the_later_edit_wins() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            l.profiles[0].policy.domains.push(DomainRule::new("a.com"));
            touch(&mut l.profiles[0]);
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();

        // B edits later than A; A pulls it.
        hb.edit(|l| {
            l.profiles[0].policy.name = "Focus".into();
            l.profiles[0].policy.updated_at = Utc::now() - Duration::seconds(30);
        });
        eb.sync_once().unwrap();
        ea.sync_once().unwrap();
        assert_eq!(ha.profiles()[0].policy.name, "Focus");

        // Both edit offline; A's is later, so it wins everywhere.
        ha.edit(|l| {
            l.profiles[0].policy.name = "A wins".into();
            l.profiles[0].policy.updated_at = Utc::now() - Duration::seconds(10);
        });
        hb.edit(|l| {
            l.profiles[0].policy.name = "B loses".into();
            l.profiles[0].policy.updated_at = Utc::now() - Duration::seconds(20);
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert_eq!(hb.profiles()[0].policy.name, "A wins");
    }

    #[test]
    fn an_edit_here_keeps_another_platforms_rules_and_unknown_fields() {
        let ((ha, ea), (_hb, eb)) = pair();
        ha.edit(|l| {
            l.profiles[0].policy.domains.push(DomainRule::new("a.com"));
            touch(&mut l.profiles[0]);
        });
        ea.sync_once().unwrap();
        // Android adds its own app rule, a partial rule Windows can't express, and an unknown field.
        let id = ha.profiles()[0].policy.id.clone();
        {
            let mut s = ea.api.0.lock().unwrap();
            let key = ("policy".to_string(), id.clone());
            let v = s.records.get_mut(&key).unwrap();
            v.0["applicationRules"].as_array_mut().unwrap().push(json!({ "id": "x", "platform": "android", "nativeIdentifier": "com.insta", "displayName": "Instagram", "enabled": true }));
            v.0["partial"] = json!(["YT_RELATED", "YT_SHORTS"]);
            v.0["futureField"] = json!(7);
            s.seq += 1;
            let seq = s.seq;
            s.records.get_mut(&key).unwrap().2 = seq;
        }
        eb.sync_once().unwrap(); // B now holds the server's JSON as its base
                                 // B renames the profile and enables a feed.
        let (hb, _) = (_hb, ());
        hb.edit(|l| {
            l.profiles[0].policy.name = "Renamed".into();
            l.profiles[0].policy.updated_at = Utc::now() - Duration::seconds(30);
        });
        eb.sync_once().unwrap();

        let s = ea.api.0.lock().unwrap();
        let data = &s.records[&("policy".to_string(), id)].0;
        assert_eq!(data["name"], "Renamed");
        assert_eq!(data["futureField"], 7, "unknown fields survive");
        assert!(
            data["applicationRules"]
                .as_array()
                .unwrap()
                .iter()
                .any(|r| r["platform"] == "android"),
            "Android's app rule survives"
        );
        assert!(
            data["partial"]
                .as_array()
                .unwrap()
                .contains(&json!("YT_RELATED")),
            "names Windows can't express survive"
        );
    }

    const ALLOW_ID: &str = "00000000-0000-4000-8000-000000000001";

    fn put_allowlist(ea: &Engine<FakeHost, FakeApi>, data: serde_json::Value) {
        let mut s = ea.api.0.lock().unwrap();
        s.seq += 1;
        let seq = s.seq;
        s.records.insert(
            ("policy".into(), ALLOW_ID.into()),
            (data, Utc::now(), seq, 1),
        );
    }

    #[test]
    fn an_allowlist_profile_is_imported_with_its_windows_apps_and_mode() {
        let ((_ha, ea), (hb, eb)) = pair();
        put_allowlist(
            &ea,
            json!({
                "id": ALLOW_ID, "name": "Only these", "mode": "allowlist", "domainRules": [],
                "applicationRules": [
                    { "id": "r1", "platform": "windows", "nativeIdentifier": "C:\\Apps\\Code.exe", "displayName": "Code", "enabled": true },
                    { "id": "r2", "platform": "android", "nativeIdentifier": "com.android.chrome", "enabled": true },
                ],
            }),
        );
        eb.sync_once().unwrap();
        let p = hb
            .profiles()
            .into_iter()
            .find(|p| p.policy.name == "Only these")
            .expect("imported");
        assert_eq!(p.policy.mode, now_focus_core::PolicyMode::Allowlist);
        assert_eq!(
            p.policy.applications.len(),
            1,
            "only this platform's rules are local"
        );
    }

    #[test]
    fn editing_an_allowlist_keeps_its_mode_and_the_other_platforms_apps() {
        let ((_ha, ea), (hb, eb)) = pair();
        put_allowlist(
            &ea,
            json!({
                "id": ALLOW_ID, "name": "Only these", "mode": "allowlist", "domainRules": [],
                "applicationRules": [{ "id": "r2", "platform": "android", "nativeIdentifier": "com.android.chrome", "enabled": true }],
            }),
        );
        eb.sync_once().unwrap();
        hb.edit(|l| {
            let p = l
                .profiles
                .iter_mut()
                .find(|p| p.policy.name == "Only these")
                .unwrap();
            p.policy
                .applications
                .push(now_focus_core::ApplicationRule::new(
                    r"C:\Apps\Code.exe",
                    "Code",
                ));
            p.policy.updated_at = Utc::now();
        });
        eb.sync_once().unwrap();
        let s = ea.api.0.lock().unwrap();
        let (data, ..) = &s.records[&("policy".to_string(), ALLOW_ID.to_string())];
        assert_eq!(data["mode"], "allowlist");
        let ids: Vec<_> = data["applicationRules"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["nativeIdentifier"].as_str().unwrap().to_string())
            .collect();
        assert!(
            ids.contains(&"com.android.chrome".to_string()),
            "Android's rule survives"
        );
        assert!(
            ids.contains(&r"C:\Apps\Code.exe".to_string()),
            "Windows' rule was pushed"
        );
    }

    #[test]
    fn a_mode_this_build_does_not_know_is_kept_but_never_imported() {
        let ((_ha, ea), (hb, eb)) = pair();
        put_allowlist(
            &ea,
            json!({ "id": ALLOW_ID, "name": "From the future", "mode": "quarantine", "domainRules": [] }),
        );
        eb.sync_once().unwrap();
        assert!(hb
            .profiles()
            .iter()
            .all(|p| p.policy.name != "From the future"));
    }

    #[test]
    fn bedtime_syncs_and_the_later_edit_wins() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            l.bedtime = BedtimeSettings {
                enabled: true,
                wind_down_minute: 21 * 60,
                ..BedtimeSettings::default()
            };
            l.bedtime_updated_at = Some(Utc::now());
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert!(hb.0.lock().unwrap().local.bedtime.enabled);
        assert_eq!(hb.0.lock().unwrap().local.bedtime.wind_down_minute, 21 * 60);
    }

    // ---- sessions

    fn start_on(h: &FakeHost, mode: EnforcementMode, minutes: i64) -> String {
        let pid = h.profiles()[0].policy.id.clone();
        let mut s = FocusSession::new(
            &pid,
            Utc::now() - Duration::minutes(1),
            Utc::now() + Duration::minutes(minutes),
            "dev",
        );
        s.enforcement_mode = mode;
        let id = s.id.clone();
        h.0.lock().unwrap().session = Some(s);
        id
    }
    use now_focus_core::EnforcementMode;

    #[test]
    fn a_session_started_on_one_pc_starts_on_the_other_and_ending_it_ends_both() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            l.profiles[0].policy.domains.push(DomainRule::new("a.com"));
            touch(&mut l.profiles[0]);
        });
        ea.sync_once().unwrap(); // the profile goes up first
        eb.sync_once().unwrap();
        let id = start_on(&ha, EnforcementMode::Normal, 60);
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();

        let joined = hb.0.lock().unwrap().session.clone().expect("B joined");
        assert_eq!(joined.id, id);
        assert_eq!(joined.origin, SessionOrigin::Remote);

        // A cancels; B follows on its next pass.
        {
            let mut i = ha.0.lock().unwrap();
            let s = i.session.as_mut().unwrap();
            s.status = FocusSessionStatus::Cancelled;
            s.cancelled_at = Some(Utc::now());
        }
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert_eq!(
            hb.0.lock().unwrap().session.as_ref().unwrap().status,
            FocusSessionStatus::Cancelled
        );
    }

    #[test]
    fn a_session_waits_behind_a_running_one_and_is_joined_when_it_ends() {
        let ((ha, ea), (hb, eb)) = pair();
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        start_on(&hb, EnforcementMode::Normal, 10); // B is busy
        let a_id = start_on(&ha, EnforcementMode::Normal, 120);
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert_ne!(
            hb.0.lock().unwrap().session.as_ref().unwrap().id,
            a_id,
            "B keeps its own"
        );

        // B's session ends; the next pass joins A's.
        {
            let mut i = hb.0.lock().unwrap();
            let s = i.session.as_mut().unwrap();
            s.status = FocusSessionStatus::Cancelled;
            s.cancelled_at = Some(Utc::now());
        }
        eb.sync_once().unwrap();
        assert_eq!(hb.0.lock().unwrap().session.as_ref().unwrap().id, a_id);
    }

    #[test]
    fn nothing_joins_when_the_user_turned_it_off_or_the_profile_is_unknown() {
        let ((ha, ea), (hb, eb)) = pair();
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        hb.0.lock().unwrap().join_enabled = false;
        start_on(&ha, EnforcementMode::Locked, 60);
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert!(hb.0.lock().unwrap().session.is_none());
    }

    #[test]
    fn bedtime_and_schedule_sessions_are_never_pushed() {
        let ((ha, ea), (_hb, eb)) = pair();
        ea.sync_once().unwrap();
        let id = start_on(&ha, EnforcementMode::Normal, 60);
        ha.0.lock().unwrap().session.as_mut().unwrap().origin = SessionOrigin::Schedule;
        ea.sync_once().unwrap();
        assert!(!ea
            .api
            .0
            .lock()
            .unwrap()
            .records
            .contains_key(&("session".into(), id)));
        let _ = (iso(Utc::now()), eb);
    }

    /// Wraps the fake server to script the two answers it never gives on its own.
    struct Scripted {
        inner: FakeApi,
        gone_once: Mutex<bool>,
        in_use: bool,
    }

    impl ApiPort for Scripted {
        fn pull(&self, cursor: i64, limit: u32) -> Result<Page, ApiError> {
            if cursor > 0 && std::mem::take(&mut *self.gone_once.lock().unwrap()) {
                return Err(ApiError::Http {
                    status: 410,
                    code: None,
                    message: "cursor too old".into(),
                });
            }
            self.inner.pull(cursor, limit)
        }
        fn push(&self, changes: &[Outgoing]) -> Result<Vec<PushOutcome>, ApiError> {
            if !self.in_use {
                return self.inner.push(changes);
            }
            let s = self.inner.0.lock().unwrap();
            Ok(changes
                .iter()
                .map(|c| {
                    let prev = s
                        .records
                        .get(&(c.typ.clone(), c.id.to_lowercase()))
                        .unwrap();
                    PushOutcome {
                        typ: c.typ.clone(),
                        id: c.id.clone(),
                        status: "rejected".into(),
                        record: Some(rec(&c.typ, &c.id, prev)),
                        code: Some("policy_in_use".into()),
                    }
                })
                .collect())
        }
    }

    #[test]
    fn a_410_on_pull_restarts_from_cursor_zero_and_reconciles() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            l.profiles[0]
                .policy
                .domains
                .push(DomainRule::new("youtube.com"));
            touch(&mut l.profiles[0]);
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();
        assert!(hb.0.lock().unwrap().local.state.cursor > 0);

        let gone = Engine::new(
            hb.clone(),
            Scripted {
                inner: ea.api.clone(),
                gone_once: Mutex::new(true),
                in_use: false,
            },
        );
        let report = gone.sync_once().expect("recovers from the 410");
        assert!(report.pulled >= 1, "a full pull ran: {report:?}");
        assert_eq!(hb.profiles().len(), 1);
        assert_eq!(hb.profiles()[0].policy.domains[0].domain, "youtube.com");
    }

    #[test]
    fn policy_in_use_restores_the_servers_copy_and_stops_resending() {
        let ((ha, ea), (hb, eb)) = pair();
        ha.edit(|l| {
            l.profiles[0]
                .policy
                .domains
                .push(DomainRule::new("youtube.com"));
            touch(&mut l.profiles[0]);
        });
        ea.sync_once().unwrap();
        eb.sync_once().unwrap();

        // B loosens the profile; the server (a session is running on it) refuses.
        hb.edit(|l| {
            l.profiles[0].policy.domains.clear();
            l.profiles[0].policy.updated_at = Utc::now();
        });
        let strict = Engine::new(
            hb.clone(),
            Scripted {
                inner: ea.api.clone(),
                gone_once: Mutex::new(false),
                in_use: true,
            },
        );
        let report = strict.sync_once().expect("sync");
        assert_eq!(report.rejected, 0, "{report:?}");
        assert_eq!(hb.profiles()[0].policy.domains[0].domain, "youtube.com");
        assert_eq!(
            strict.sync_once().unwrap().pushed,
            0,
            "nothing left to resend"
        );
    }

    // ---- against a real server (set SYNC_IT_URL, e.g. a local services/api on a *_test database)

    struct MemAuth(Mutex<Option<crate::api::StoredAuth>>);

    impl crate::api::AuthStore for MemAuth {
        fn load(&self) -> Option<crate::api::StoredAuth> {
            self.0.lock().unwrap().clone()
        }
        fn save(&self, auth: &crate::api::StoredAuth) -> Result<(), String> {
            *self.0.lock().unwrap() = Some(auth.clone());
            Ok(())
        }
        fn clear(&self) {
            *self.0.lock().unwrap() = None;
        }
    }

    #[test]
    fn live_two_pcs_converge_through_the_real_api() {
        let Ok(url) = std::env::var("SYNC_IT_URL") else {
            return;
        };
        let email = format!("win-{}@example.com", uuid::Uuid::new_v4().simple());
        let pw = "correct horse battery staple";
        let api = |name: &str| {
            let _ = name;
            crate::api::Api::new(
                &url,
                "NowFocus-Windows/it",
                Box::new(MemAuth(Mutex::new(None))),
            )
        };

        let (ha, _) = FakeHost::new("a");
        let (hb, _) = FakeHost::new("b");
        let (api_a, api_b) = (api("a"), api("b"));
        let acct = api_a.register(&email, pw, "PC A").expect("register");
        api_b.login(&email, pw, "PC B").expect("login");
        ha.signed_in(&acct.user_id);
        hb.signed_in(&acct.user_id);
        let (ea, eb) = (
            Engine::new(ha.clone(), api_a),
            Engine::new(hb.clone(), api_b),
        );

        // A's starter profile gets a site and goes up.
        ha.edit(|l| {
            l.profiles[0]
                .policy
                .domains
                .push(DomainRule::new("youtube.com"));
            l.profiles[0]
                .policy
                .applications
                .push(ApplicationRule::new(r"C:\Games\steam.exe", "Steam"));
            touch(&mut l.profiles[0]);
            l.bedtime = BedtimeSettings {
                enabled: true,
                ..BedtimeSettings::default()
            };
            l.bedtime_updated_at = Some(Utc::now() - Duration::seconds(5));
        });
        let report = ea.sync_once().expect("A syncs");
        assert!(report.pushed >= 2, "{report:?}");
        eb.sync_once().expect("B syncs");
        let b = hb.profiles();
        assert_eq!(b.len(), 1, "B's untouched starter was replaced");
        assert_eq!(b[0].policy.domains[0].domain, "youtube.com");
        assert_eq!(b[0].policy.applications[0].display_name, "Steam");
        assert!(hb.0.lock().unwrap().local.bedtime.enabled);

        // A starts a session; B joins it; A cancels; B follows.
        let id = start_on(&ha, EnforcementMode::Normal, 60);
        ea.sync_once().expect("A pushes the session");
        eb.sync_once().expect("B pulls it");
        assert_eq!(
            hb.0.lock().unwrap().session.as_ref().map(|s| s.id.clone()),
            Some(id)
        );
        {
            let mut i = ha.0.lock().unwrap();
            let s = i.session.as_mut().unwrap();
            s.status = FocusSessionStatus::Cancelled;
            s.cancelled_at = Some(Utc::now());
        }
        ea.sync_once().expect("A pushes the cancel");
        eb.sync_once().expect("B pulls the cancel");
        assert_eq!(
            hb.0.lock().unwrap().session.as_ref().unwrap().status,
            FocusSessionStatus::Cancelled
        );

        // A second sync with nothing changed sends nothing.
        assert_eq!(ea.sync_once().expect("idle").pushed, 0);
        ea.api.delete_account(pw).expect("cleanup");
    }

    /// An Android-shaped policy and bedtime reach a PC, a PC edit goes back, and everything the PC can't express
    /// (other platforms' rules, unknown partials, categories, unknown fields) is still on the server verbatim.
    #[test]
    fn live_android_shaped_data_survives_a_windows_edit() {
        let Ok(url) = std::env::var("SYNC_IT_URL") else {
            return;
        };
        let email = format!("win-android-{}@example.com", uuid::Uuid::new_v4().simple());
        let pw = "correct horse battery staple";
        let api = || {
            crate::api::Api::new(
                &url,
                "NowFocus-Windows/it",
                Box::new(MemAuth(Mutex::new(None))),
            )
        };
        let (phone, api_pc) = (api(), api());
        let acct = phone.register(&email, pw, "Phone").expect("register");
        api_pc.login(&email, pw, "PC").expect("login");

        let pid = uuid::Uuid::new_v4().to_string();
        let t = Utc::now() - Duration::seconds(30);
        let out = |typ: &str, id: &str, data: Value| Outgoing {
            typ: typ.into(),
            id: id.into(),
            updated_at: t,
            data: Some(data),
            deleted: false,
            fingerprint: String::new(),
        };
        let res = phone
            .push(&[
                out(
                    "policy",
                    &pid,
                    json!({
                        "id": pid, "name": "Android work", "mode": "blocklist",
                        "domainRules": [
                            {"id": uuid::Uuid::new_v4().to_string(), "domain": "reddit.com", "includeSubdomains": false, "enabled": true},
                        ],
                        "applicationRules": [
                            {"id": uuid::Uuid::new_v4().to_string(), "platform": "android", "nativeIdentifier": "com.reddit.frontpage", "displayName": "Reddit", "enabled": true},
                            {"id": uuid::Uuid::new_v4().to_string(), "platform": "windows", "nativeIdentifier": r"C:\Games\steam.exe", "displayName": "Steam", "enabled": true},
                        ],
                        "partial": ["YT_RELATED", "FB_REELS", "YT_SHORTS", "TT_FOR_YOU"],
                        "categories": ["social"], "notificationPolicy": "quiet",
                        "futureField": {"keep": true},
                    }),
                ),
                out(
                    "bedtime_settings",
                    "default",
                    json!({"enabled": true, "windDownMinute": 1300, "sleepMinute": 1400, "wakeMinute": 400,
                           "lockAtSleep": false, "policyId": null, "quietNotifications": true}),
                ),
            ])
            .expect("phone pushes");
        assert!(res.iter().all(|r| r.status == "applied"), "{res:?}");

        let (hb, _) = FakeHost::new("pc");
        hb.signed_in(&acct.user_id);
        let eb = Engine::new(hb.clone(), api_pc);
        eb.sync_once().expect("PC pulls");
        let p = hb.profiles();
        assert_eq!(p.len(), 1, "the untouched starter was replaced: {p:?}");
        assert_eq!(p[0].policy.domains.len(), 1);
        assert_eq!(
            p[0].policy.applications.len(),
            1,
            "only the Windows app rule is imported"
        );
        assert!(hb.0.lock().unwrap().local.bedtime.enabled);

        hb.edit(|l| {
            l.profiles[0].policy.domains.push(DomainRule::new("x.com"));
            l.profiles[0].policy.updated_at = Utc::now(); // newer than the phone's push, or last-write-wins keeps the phone's
        });
        eb.sync_once().expect("PC pushes");

        let page = phone.pull(0, 500).expect("phone pulls");
        let rec = |typ: &str| {
            page.changes
                .iter()
                .find(|c| c.typ == typ)
                .expect(typ)
                .data
                .clone()
        };
        let pol = rec("policy");
        assert_eq!(pol["futureField"], json!({"keep": true}));
        assert_eq!(pol["categories"], json!(["social"]));
        assert_eq!(pol["notificationPolicy"], json!("quiet"));
        let partial = pol["partial"].to_string();
        assert!(
            partial.contains("YT_RELATED")
                && partial.contains("FB_REELS")
                && partial.contains("TT_FOR_YOU"),
            "{partial}"
        );
        let apps = pol["applicationRules"].to_string();
        assert!(
            apps.contains("com.reddit.frontpage"),
            "Android's app rule survived: {apps}"
        );
        let doms = pol["domainRules"].to_string();
        assert!(doms.contains("x.com"), "the PC's new site arrived: {doms}");
        assert!(doms.contains("\"includeSubdomains\":false"), "{doms}");
        assert_eq!(rec("bedtime_settings")["quietNotifications"], json!(true));
        phone.delete_account(pw).expect("cleanup");
    }
}
