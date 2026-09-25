use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Port of apps/macos/NowFocusCore/BlockPolicy.swift.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PolicyMode {
    Blocklist,
    Allowlist,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainRule {
    pub id: String,
    pub domain: String,
    pub include_subdomains: bool,
    pub enabled: bool,
}

impl DomainRule {
    /// `domain` should already be `domain_validation::normalize`d by the
    /// caller; this constructor doesn't re-validate (the enforcement
    /// boundary in `service::network_enforcer` does, independently).
    pub fn new(domain: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            domain: domain.into(),
            include_subdomains: true,
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApplicationRule {
    pub id: String,
    pub platform: String,
    /// Normalized executable identity/path on Windows (see
    /// focus_app_technical_architecture.md §53 — Android uses a package
    /// name, iOS an opaque Family Controls token instead).
    pub native_identifier: String,
    pub display_name: String,
    pub enabled: bool,
}

impl ApplicationRule {
    pub fn new(native_identifier: impl Into<String>, display_name: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            platform: "windows".to_string(),
            native_identifier: native_identifier.into(),
            display_name: display_name.into(),
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NotificationMode {
    Normal,
    Quiet,
    Silent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlockPolicy {
    pub id: String,
    pub name: String,
    pub mode: PolicyMode,
    pub domains: Vec<DomainRule>,
    pub applications: Vec<ApplicationRule>,
    pub categories: Vec<String>,
    pub notification_policy: NotificationMode,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub revision: i64,
}

impl BlockPolicy {
    pub fn new(name: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: Uuid::new_v4().to_string(),
            name: name.into(),
            mode: PolicyMode::Blocklist,
            domains: Vec::new(),
            applications: Vec::new(),
            categories: Vec::new(),
            notification_policy: NotificationMode::Normal,
            created_at: now,
            updated_at: now,
            revision: 1,
        }
    }
}

/// New for Windows — neither macOS nor Android has this yet. Wraps a
/// `BlockPolicy` with the design's "feeds only" toggles: block a specific
/// feed surface (e.g. youtube.com/shorts) while leaving the rest of the
/// site (search, regular videos) reachable. Hosts-file blocking can't do
/// this — it's all-or-nothing per domain — so `feed_rules` is stored now
/// but only enforced once the Phase 5 browser-extension milestone lands
/// (declarativeNetRequest can match on URL path, hosts-file editing can't).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedRule {
    pub id: String,
    pub profile_id: String,
    /// One of the design's feed keys: "shorts", "reels", "xfy", "ythome".
    pub feed_key: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    pub policy: BlockPolicy,
    pub feed_rules: Vec<FeedRule>,
}

impl Profile {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            policy: BlockPolicy::new(name),
            feed_rules: Vec::new(),
        }
    }
}
