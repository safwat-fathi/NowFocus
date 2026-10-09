//! The Windows app's lossy `Profile` / `BedtimeSettings` <-> the server's `data` (services/api/WIRE_FORMAT.md,
//! sections 4 and 5).
//!
//! The one rule that matters: policies are last-write-wins as a whole, so an edit is MERGED into the last
//! server JSON, never rebuilt from the Windows model. Windows owns the name, the mode (set when a profile is created,
//! never changed), the domain rules, the apps with
//! `platform: "windows"` and the five feed names it can express; every other field and rule (Android's
//! apps, `YT_RELATED`, `TT_FOR_YOU`, `categories`, unknown fields) is carried over untouched.

use std::collections::{BTreeMap, BTreeSet};

use chrono::{DateTime, Utc};
use now_focus_core::block_policy::{FeedRule, NotificationMode, PolicyMode};
use now_focus_core::{
    domain_validation, ApplicationRule, BedtimeSettings, BlockPolicy, DomainRule, Profile,
};
use serde_json::{json, Value};

pub const PLATFORM: &str = "windows";
/// The feed keys Windows can express, as the wire's `partial` names.
pub const FEEDS: [(&str, &str); 5] = [
    ("shorts", "YT_SHORTS"),
    ("ythome", "YT_HOME"),
    ("xfy", "X_FOR_YOU"),
    ("reels", "IG_REELS"),
    ("fbreels", "FB_REELS"),
];

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str)
}
fn b(v: &Value, key: &str, default: bool) -> bool {
    v.get(key).and_then(Value::as_bool).unwrap_or(default)
}
fn arr<'a>(v: &'a Value, key: &str) -> &'a [Value] {
    v.get(key)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

/// The key two spellings of one domain share; unparseable input falls back to trimmed lowercase.
fn domain_key(d: &str) -> String {
    domain_validation::normalize(d).unwrap_or_else(|| d.trim().to_lowercase())
}

/// A mode this build knows (a missing one is a blocklist). A future mode is kept but never enforced:
/// guessing its meaning could do the opposite of what the user set.
pub fn supported(raw: &Value) -> bool {
    matches!(s(raw, "mode"), None | Some("blocklist") | Some("allowlist"))
}

fn mode_of(raw: &Value) -> PolicyMode {
    match s(raw, "mode") {
        Some("allowlist") => PolicyMode::Allowlist,
        _ => PolicyMode::Blocklist,
    }
}

fn mode_str(mode: PolicyMode) -> &'static str {
    match mode {
        PolicyMode::Blocklist => "blocklist",
        PolicyMode::Allowlist => "allowlist",
    }
}

/// `existing` (the profile we already hold) lends its rule ids so a pull doesn't churn them.
pub fn to_local(raw: &Value, existing: Option<&Profile>) -> Option<Profile> {
    let id = s(raw, "id")?.to_lowercase();
    let mut domains = Vec::new();
    for r in arr(raw, "domainRules") {
        let Some(domain) = s(r, "domain").and_then(domain_validation::normalize) else {
            continue;
        };
        let kept = existing.and_then(|p| p.policy.domains.iter().find(|d| d.domain == domain));
        domains.push(DomainRule {
            id: kept
                .map(|d| d.id.clone())
                .or_else(|| s(r, "id").map(String::from))
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            domain,
            include_subdomains: b(r, "includeSubdomains", true),
            enabled: b(r, "enabled", true),
        });
    }
    let mut applications = Vec::new();
    for r in arr(raw, "applicationRules") {
        if s(r, "platform") != Some(PLATFORM) {
            continue;
        }
        let Some(ident) = s(r, "nativeIdentifier").filter(|i| !i.is_empty()) else {
            continue;
        };
        let kept = existing.and_then(|p| {
            p.policy
                .applications
                .iter()
                .find(|a| a.native_identifier.eq_ignore_ascii_case(ident))
        });
        applications.push(ApplicationRule {
            id: kept
                .map(|a| a.id.clone())
                .or_else(|| s(r, "id").map(String::from))
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            platform: PLATFORM.to_string(),
            native_identifier: ident.to_string(),
            display_name: s(r, "displayName").unwrap_or(ident).to_string(),
            enabled: b(r, "enabled", true),
        });
    }
    let partial: Vec<&str> = arr(raw, "partial")
        .iter()
        .filter_map(Value::as_str)
        .collect();
    let feed_rules = FEEDS
        .iter()
        .filter(|(_, name)| partial.contains(name))
        .map(|(key, _)| FeedRule {
            id: existing
                .and_then(|p| p.feed_rules.iter().find(|f| f.feed_key == *key))
                .map(|f| f.id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            profile_id: id.clone(),
            feed_key: key.to_string(),
            enabled: true,
        })
        .collect();

    let now = Utc::now();
    Some(Profile {
        policy: BlockPolicy {
            id,
            name: s(raw, "name").unwrap_or("").to_string(),
            mode: mode_of(raw),
            domains,
            applications,
            categories: existing
                .map(|p| p.policy.categories.clone())
                .unwrap_or_default(),
            notification_policy: existing
                .map(|p| p.policy.notification_policy)
                .unwrap_or(NotificationMode::Normal),
            created_at: existing.map(|p| p.policy.created_at).unwrap_or(now),
            updated_at: existing.map(|p| p.policy.updated_at).unwrap_or(now),
            revision: existing.map(|p| p.policy.revision).unwrap_or(1),
        },
        feed_rules,
    })
}

type Canon = (
    String,
    PolicyMode,
    BTreeMap<String, (bool, bool)>,
    BTreeMap<String, String>,
    BTreeSet<String>,
);

fn canon(p: &Profile) -> Canon {
    (
        p.policy.name.clone(),
        p.policy.mode,
        p.policy
            .domains
            .iter()
            .map(|d| (domain_key(&d.domain), (d.include_subdomains, d.enabled)))
            .collect(),
        p.policy
            .applications
            .iter()
            .map(|a| (a.native_identifier.to_lowercase(), a.display_name.clone()))
            .collect(),
        p.feed_rules
            .iter()
            .filter(|f| f.enabled)
            .map(|f| f.feed_key.clone())
            .collect(),
    )
}

/// Same meaning, ignoring ids, order and the spelling of domains.
pub fn same(a: &Profile, b: &Profile) -> bool {
    a.policy.id.to_lowercase() == b.policy.id.to_lowercase() && canon(a) == canon(b)
}

/// A stable string for the content of `p`.
pub fn canonical(p: &Profile) -> String {
    format!("{}\u{1}{:?}", p.policy.id.to_lowercase(), canon(p))
}

/// `raw` is the last server record (`None` for a brand-new profile). Never mutates it.
pub fn merge(local: &Profile, raw: Option<&Value>) -> Value {
    let mut out = raw.cloned().unwrap_or_else(|| json!({}));
    out["id"] = json!(local.policy.id.to_lowercase());
    out["mode"] = json!(mode_str(local.policy.mode));
    out["name"] = json!(local.policy.name);
    let existing_domains: Vec<Value> = arr(&out, "domainRules").to_vec();
    out["domainRules"] = Value::Array(merge_domains(&local.policy.domains, &existing_domains));
    let existing_apps: Vec<Value> = arr(&out, "applicationRules").to_vec();
    out["applicationRules"] = Value::Array(merge_apps(&local.policy.applications, &existing_apps));
    merge_partial(&local.feed_rules, &mut out);
    out
}

fn merge_domains(local: &[DomainRule], existing: &[Value]) -> Vec<Value> {
    let mut seen = BTreeSet::new();
    let mut rules = Vec::new();
    for r in existing {
        let Some(key) = s(r, "domain").map(domain_key) else {
            rules.push(r.clone()); // not a rule we can read: keep it verbatim
            continue;
        };
        // Windows imports every domain rule (disabled ones too), so a rule it no longer has was removed here.
        if let Some(mine) = local.iter().find(|d| domain_key(&d.domain) == key) {
            let mut r = r.clone();
            r["includeSubdomains"] = json!(mine.include_subdomains);
            r["enabled"] = json!(mine.enabled);
            rules.push(r);
            seen.insert(key);
        }
    }
    for d in local {
        let key = domain_key(&d.domain);
        if domain_validation::normalize(&d.domain).is_some() && seen.insert(key.clone()) {
            rules.push(json!({ "id": d.id, "domain": key, "includeSubdomains": d.include_subdomains, "enabled": d.enabled }));
        }
    }
    rules
}

fn merge_apps(local: &[ApplicationRule], existing: &[Value]) -> Vec<Value> {
    let mut seen = BTreeSet::new();
    let mut rules = Vec::new();
    for r in existing {
        if s(r, "platform") != Some(PLATFORM) {
            rules.push(r.clone()); // another platform's app rule: never ours to touch
            continue;
        }
        let Some(ident) = s(r, "nativeIdentifier") else {
            rules.push(r.clone());
            continue;
        };
        if let Some(mine) = local
            .iter()
            .find(|a| a.native_identifier.eq_ignore_ascii_case(ident))
        {
            let mut r = r.clone();
            r["displayName"] = json!(mine.display_name);
            r["enabled"] = json!(mine.enabled);
            rules.push(r);
            seen.insert(ident.to_lowercase());
        }
    }
    for a in local {
        if seen.insert(a.native_identifier.to_lowercase()) {
            rules.push(json!({
                "id": a.id, "platform": PLATFORM, "nativeIdentifier": a.native_identifier,
                "displayName": a.display_name, "enabled": a.enabled,
            }));
        }
    }
    rules
}

fn merge_partial(feeds: &[FeedRule], out: &mut Value) {
    let known: Vec<&str> = FEEDS.iter().map(|(_, n)| *n).collect();
    // Names this build doesn't own (YT_RELATED, TT_FOR_YOU, a newer one, a non-string) belong to someone else.
    let mut next: Vec<Value> = arr(out, "partial")
        .iter()
        .filter(|v| !v.as_str().is_some_and(|n| known.contains(&n)))
        .cloned()
        .collect();
    let had = out.get("partial").is_some_and(|v| !v.is_null());
    for (key, name) in FEEDS {
        if feeds.iter().any(|f| f.enabled && f.feed_key == key) {
            next.push(json!(name));
        }
    }
    if next.is_empty() && !had {
        return;
    }
    out["partial"] = Value::Array(next);
}

/// Bedtime is a singleton (`bedtime_settings`, id `default`). Android's `quietNotifications` travels as an
/// extra field that `merge` carries over.
pub mod bedtime {
    use super::*;

    pub fn default() -> BedtimeSettings {
        BedtimeSettings::default()
    }

    pub fn to_local(raw: &Value) -> BedtimeSettings {
        let d = default();
        let n = |key: &str, fallback: i64| raw.get(key).and_then(Value::as_i64).unwrap_or(fallback);
        BedtimeSettings {
            enabled: b(raw, "enabled", d.enabled),
            wind_down_minute: n("windDownMinute", d.wind_down_minute),
            sleep_minute: n("sleepMinute", d.sleep_minute),
            wake_minute: n("wakeMinute", d.wake_minute),
            lock_at_sleep: b(raw, "lockAtSleep", d.lock_at_sleep),
            policy_id: s(raw, "policyId").map(str::to_lowercase),
        }
    }

    pub fn merge(local: &BedtimeSettings, raw: Option<&Value>) -> Value {
        let mut out = raw.cloned().unwrap_or_else(|| json!({}));
        out["enabled"] = json!(local.enabled);
        out["windDownMinute"] = json!(local.wind_down_minute);
        out["sleepMinute"] = json!(local.sleep_minute);
        out["wakeMinute"] = json!(local.wake_minute);
        out["lockAtSleep"] = json!(local.lock_at_sleep);
        out["policyId"] = local
            .policy_id
            .as_ref()
            .map(|p| json!(p.to_lowercase()))
            .unwrap_or(Value::Null);
        out
    }

    pub fn same(a: &BedtimeSettings, b: &BedtimeSettings) -> bool {
        let lower = |s: &BedtimeSettings| BedtimeSettings {
            policy_id: s.policy_id.as_ref().map(|p| p.to_lowercase()),
            ..s.clone()
        };
        lower(a) == lower(b)
    }

    pub fn canonical(b: &BedtimeSettings) -> String {
        format!(
            "{:?}",
            BedtimeSettings {
                policy_id: b.policy_id.as_ref().map(|p| p.to_lowercase()),
                ..b.clone()
            }
        )
    }
}

/// RFC3339 with a timezone, as the wire requires.
pub fn iso(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &Value) -> BTreeSet<String> {
        arr(v, "partial")
            .iter()
            .filter_map(Value::as_str)
            .map(String::from)
            .collect()
    }

    #[test]
    fn partial_names_round_trip_and_the_ones_windows_cannot_express_survive() {
        let raw = json!({
            "id": "p1", "name": "Deep work", "mode": "blocklist",
            "partial": ["YT_SHORTS", "FB_REELS", "YT_RELATED", "TT_FOR_YOU", "X_FOR_YOU"],
        });
        let local = to_local(&raw, None).expect("a profile");
        let keys: Vec<&str> = local
            .feed_rules
            .iter()
            .map(|f| f.feed_key.as_str())
            .collect();
        assert_eq!(
            keys,
            ["shorts", "xfy", "fbreels"],
            "FB_REELS is Windows' own since the URL guard"
        );

        let back = names(&merge(&local, Some(&raw)));
        assert_eq!(
            back,
            names(&raw),
            "an untouched profile pushes the same list"
        );

        let mut off = local.clone();
        for f in off
            .feed_rules
            .iter_mut()
            .filter(|f| f.feed_key == "fbreels")
        {
            f.enabled = false;
        }
        let after = names(&merge(&off, Some(&raw)));
        assert!(!after.contains("FB_REELS"), "{after:?}");
        for kept in ["YT_SHORTS", "YT_RELATED", "TT_FOR_YOU", "X_FOR_YOU"] {
            assert!(after.contains(kept), "{kept} lost: {after:?}");
        }
    }
}
