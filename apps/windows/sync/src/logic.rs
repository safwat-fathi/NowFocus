//! The sync rules for profiles and bedtime, as pure functions over [`Local`]: a port of Android's `SyncLogic`.
//! The engine does the network; this decides what the data means.
//!
//! Invariants worth knowing:
//!  - A record is "dirty" when it differs from the server copy we last saw (or never reached the server).
//!    Dirtiness is always recomputed from the data. The time of the user's change is `updated_at` on the
//!    profile (bumped by every edit) and `bedtime_updated_at`.
//!  - Windows never deletes a profile; a profile the account deleted is removed here unless it was edited
//!    here after it was deleted there.
//!  - Conflicts use the server's own rule, last-write-wins on `updated_at`.
//!  - Nothing here ever touches a running session or the Commitment.

use std::collections::HashSet;

use chrono::{DateTime, Utc};
use now_focus_core::Profile;
use serde_json::Value;

use crate::model::*;
use crate::wire::{self, bedtime};

// ---------------------------------------------------------------- linking

/// Same account: resume. A different account (or none before): start clean so nothing of another account's
/// cursor or raw data leaks in.
pub fn link(state: &SyncState, user_id: &str) -> SyncState {
    if state.user_id.as_deref() == Some(user_id) {
        state.clone()
    } else {
        SyncState {
            user_id: Some(user_id.to_string()),
            ..SyncState::default()
        }
    }
}

/// After sign-out or deleting the account: the next link is a fresh merge. Local data stays.
pub fn unlink() -> SyncState {
    SyncState::default()
}

// ---------------------------------------------------------------- pull

pub fn apply_pulled(local: &Local, records: &[ServerRecord], cursor: i64) -> Applied {
    let mut cur = local.clone();
    let mut bedtime_changed = false;
    for r in records {
        match r.typ.as_str() {
            POLICY => cur = apply_policy_record(cur, r),
            BEDTIME => {
                let (next, changed) = apply_bedtime_record(cur, r);
                cur = next;
                bedtime_changed |= changed;
            }
            _ => {} // session is handled by the session half; shield_item and user_settings aren't synced here
        }
    }
    cur.state.cursor = cursor.max(cur.state.cursor);
    Applied {
        local: cur,
        bedtime_changed,
        rejected: 0,
    }
}

/// Run once, after the first full pull following a link. `referenced` are policy ids something still points at
/// (bedtime's profile, the current session): those are never dropped.
pub fn finish_initial_pull(local: &Local, referenced: &HashSet<String>) -> Local {
    let mut out = local.clone();
    let server_has_profiles = out
        .state
        .policies
        .values()
        .any(|m| m.raw.is_some() && m.imported);
    if server_has_profiles {
        // This PC's untouched starter profile would only duplicate the account's own.
        out.profiles.retain(|p| {
            let id = p.policy.id.to_lowercase();
            !(!out.state.policies.contains_key(&id)
                && is_untouched_seed(p)
                && !referenced.contains(&id))
        });
    }
    out.state.initial_pull_done = true;
    out
}

pub fn is_untouched_seed(p: &Profile) -> bool {
    p.policy.name == "Deep Work"
        && p.policy.domains.is_empty()
        && p.policy.applications.is_empty()
        && p.feed_rules.is_empty()
}

fn policy_dirty(p: &Profile, m: Option<&Meta>) -> bool {
    match m.and_then(|m| m.raw.as_ref()) {
        None => true,
        Some(raw) => wire::to_local(raw, None)
            .map(|server| !wire::same(p, &server))
            .unwrap_or(true),
    }
}

fn apply_policy_record(mut local: Local, r: &ServerRecord) -> Local {
    let id = r.id.to_lowercase();
    let m = local.state.policies.get(&id).cloned();
    let mine = local
        .profiles
        .iter()
        .find(|p| p.policy.id.to_lowercase() == id)
        .cloned();

    if r.deleted {
        match (&m, &mine) {
            (_, None) => {
                local.state.policies.remove(&id);
            }
            // No bookkeeping for a profile we hold: we can't tell whether it was edited here, so keep it (it goes up as new).
            (None, Some(_)) => {}
            (Some(meta), Some(p)) => {
                if policy_dirty(p, Some(meta))
                    && meta.raw.is_some()
                    && p.policy.updated_at > r.updated_at
                {
                    // Edited here after it was deleted there: the edit wins and is pushed as a new record.
                    local.state.policies.insert(
                        id,
                        Meta {
                            raw: None,
                            revision: r.revision,
                            ..meta.clone()
                        },
                    );
                } else {
                    local.state.policies.remove(&id);
                    local.profiles.retain(|p| p.policy.id.to_lowercase() != id);
                }
            }
        }
        return local;
    }

    if !wire::supported(&r.data) {
        // Keep the record (so it's never mistaken for a deletion) but don't import or enforce it.
        local.state.policies.insert(
            id,
            Meta {
                raw: Some(r.data.clone()),
                revision: r.revision,
                imported: false,
                rejected: None,
            },
        );
        return local;
    }
    let Some(mut incoming) = wire::to_local(&r.data, mine.as_ref()) else {
        return local;
    };
    incoming.policy.updated_at = r.updated_at;
    incoming.policy.revision = r.revision;
    let fresh = Meta::fresh(r.data.clone(), r.revision);

    match mine {
        None => {
            local.profiles.push(incoming);
            local.state.policies.insert(id, fresh);
        }
        Some(mine) => {
            // Present on both sides. No bookkeeping yet (first sync) means the server wins; otherwise a newer local edit wins.
            let local_wins = m.as_ref().is_some_and(|m| policy_dirty(&mine, Some(m)))
                && mine.policy.updated_at > r.updated_at;
            if local_wins {
                local.state.policies.insert(
                    id,
                    Meta {
                        raw: Some(r.data.clone()),
                        revision: r.revision,
                        ..m.unwrap_or_default()
                    },
                );
            } else {
                for p in &mut local.profiles {
                    if p.policy.id.to_lowercase() == id {
                        *p = incoming.clone();
                    }
                }
                local.state.policies.insert(id, fresh);
            }
        }
    }
    local
}

fn bedtime_dirty(local: &Local, m: Option<&Meta>) -> bool {
    match m.and_then(|m| m.raw.as_ref()) {
        None => !bedtime::same(&local.bedtime, &bedtime::default()), // never uploaded: dirty only when it isn't just the defaults
        Some(raw) => !bedtime::same(&local.bedtime, &bedtime::to_local(raw)),
    }
}

fn apply_bedtime_record(mut local: Local, r: &ServerRecord) -> (Local, bool) {
    if r.deleted {
        return (local, false);
    }
    let m = local.state.bedtime.clone();
    let incoming = bedtime::to_local(&r.data);
    let local_wins = m.as_ref().is_some_and(|m| bedtime_dirty(&local, Some(m)))
        && local.bedtime_updated_at.is_some_and(|t| t > r.updated_at);
    if local_wins {
        local.state.bedtime = Some(Meta {
            raw: Some(r.data.clone()),
            revision: r.revision,
            ..m.unwrap_or_default()
        });
        (local, false)
    } else {
        let changed = !bedtime::same(&local.bedtime, &incoming);
        local.bedtime = incoming;
        local.bedtime_updated_at = Some(r.updated_at);
        local.state.bedtime = Some(Meta::fresh(r.data.clone(), r.revision));
        (local, changed)
    }
}

// ---------------------------------------------------------------- push

pub fn plan_push(local: &Local, now: DateTime<Utc>) -> Vec<Outgoing> {
    if local.state.user_id.is_none() || !local.state.initial_pull_done {
        return Vec::new();
    }
    let mut out = Vec::new();
    for p in &local.profiles {
        let id = p.policy.id.to_lowercase();
        let m = local.state.policies.get(&id);
        if m.is_some_and(|m| !m.imported) || !policy_dirty(p, m) {
            continue;
        }
        let raw = m.and_then(|m| m.raw.as_ref());
        let fp = fingerprint(&wire::canonical(p), raw);
        if m.and_then(|m| m.rejected.as_deref()) == Some(fp.as_str()) {
            continue;
        }
        out.push(Outgoing {
            typ: POLICY.to_string(),
            id,
            updated_at: p.policy.updated_at.min(now),
            data: Some(wire::merge(p, raw)),
            deleted: false,
            fingerprint: fp,
        });
    }
    let bm = local.state.bedtime.as_ref();
    if bedtime_dirty(local, bm) {
        let raw = bm.and_then(|m| m.raw.as_ref());
        let fp = fingerprint(&bedtime::canonical(&local.bedtime), raw);
        if bm.and_then(|m| m.rejected.as_deref()) != Some(fp.as_str()) {
            out.push(Outgoing {
                typ: BEDTIME.to_string(),
                id: BEDTIME_ID.to_string(),
                updated_at: local.bedtime_updated_at.unwrap_or(now).min(now),
                data: Some(bedtime::merge(&local.bedtime, raw)),
                deleted: false,
                fingerprint: fp,
            });
        }
    }
    out
}

/// `sent` and `results` are matched by (type, id). A change the server didn't answer stays dirty and goes out again.
pub fn apply_push_results(local: &Local, sent: &[Outgoing], results: &[PushOutcome]) -> Applied {
    let mut cur = local.clone();
    let mut bedtime_changed = false;
    let mut rejected = 0;
    for res in results {
        let Some(out) = sent
            .iter()
            .find(|o| o.typ == res.typ && o.id.to_lowercase() == res.id.to_lowercase())
        else {
            continue;
        };
        let id = out.id.to_lowercase();
        // policy_in_use: a session is running on this profile and the edit would loosen it. The server's copy wins
        // outright, so the local edit is reverted (aged to the record's time, or "newer local edit wins" resends it).
        let in_use = res.status == "rejected"
            && res.code.as_deref() == Some("policy_in_use")
            && out.typ == POLICY
            && res.record.is_some();
        if let (true, Some(rec)) = (in_use, &res.record) {
            if let Some(p) = cur
                .profiles
                .iter_mut()
                .find(|p| p.policy.id.to_lowercase() == id)
            {
                p.policy.updated_at = rec.updated_at;
            }
        }
        let status = if in_use { "stale" } else { res.status.as_str() };
        match status {
            "applied" => {
                let Some(rec) = &res.record else { continue };
                match out.typ.as_str() {
                    POLICY => {
                        cur.state
                            .policies
                            .insert(id, Meta::fresh(rec.data.clone(), rec.revision));
                    }
                    BEDTIME => {
                        cur.state.bedtime = Some(Meta::fresh(rec.data.clone(), rec.revision))
                    }
                    _ => {}
                }
            }
            // The server already has this or newer: take its copy, unless a newer local edit slipped in meanwhile.
            "stale" => {
                if let Some(rec) = &res.record {
                    match out.typ.as_str() {
                        POLICY => cur = apply_policy_record(cur, rec),
                        BEDTIME => {
                            let (next, changed) = apply_bedtime_record(cur, rec);
                            cur = next;
                            bedtime_changed |= changed;
                        }
                        _ => {}
                    }
                }
            }
            _ => {
                // Rejected: remember the payload so it isn't resent until the user changes it.
                rejected += 1;
                match out.typ.as_str() {
                    POLICY => {
                        if let Some(m) = cur.state.policies.get_mut(&id) {
                            m.rejected = Some(out.fingerprint.clone());
                        } else {
                            cur.state.policies.insert(
                                id,
                                Meta {
                                    rejected: Some(out.fingerprint.clone()),
                                    ..Meta::default()
                                },
                            );
                        }
                    }
                    _ => {
                        let mut m = cur.state.bedtime.clone().unwrap_or_default();
                        m.rejected = Some(out.fingerprint.clone());
                        cur.state.bedtime = Some(m);
                    }
                }
            }
        }
    }
    Applied {
        local: cur,
        bedtime_changed,
        rejected,
    }
}

/// Changes the server refused that are still waiting for the user to edit them.
pub fn rejected_count(state: &SyncState) -> usize {
    state
        .policies
        .values()
        .filter(|m| m.rejected.is_some())
        .count()
        + usize::from(state.bedtime.as_ref().is_some_and(|m| m.rejected.is_some()))
}

/// Identifies "this content on top of this server base" (FNV-1a, so it stays the same across Rust versions).
fn fingerprint(content: &str, base: Option<&Value>) -> String {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in content
        .bytes()
        .chain([0u8])
        .chain(base.map(|v| v.to_string()).unwrap_or_default().bytes())
    {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("{hash:016x}")
}
