use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Port of apps/macos/NowFocusCore/FocusSession.swift. Same field names
/// (snake_case per Rust convention), same defaults.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FocusSessionStatus {
    Scheduled,
    Active,
    Completed,
    Cancelled,
    Expired,
    Error,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EnforcementMode {
    Normal,
    Strict,
    Locked,
}

/// `BedtimeWinddown` is unused until the Phase 5 Bedtime Wind-Down milestone;
/// it exists now only so the persisted schema doesn't need a migration later.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionType {
    Focus,
    BedtimeWinddown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NotificationMode {
    Normal,
    Quiet,
    Silent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FocusSession {
    pub id: String,
    pub policy_id: String,
    pub session_type: SessionType,
    pub start_at: DateTime<Utc>,
    pub end_at: DateTime<Utc>,
    pub status: FocusSessionStatus,
    pub enforcement_mode: EnforcementMode,
    pub notification_mode: NotificationMode,
    pub created_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub cancelled_at: Option<DateTime<Utc>>,
    pub device_id: String,
    pub revision: i64,
}

impl FocusSession {
    /// Mirrors `FocusSession.init` in the Swift source: same defaults
    /// (status starts `.active`, not `.scheduled` — the daemon protocol
    /// isn't session-aware, so callers create sessions already running).
    pub fn new(
        policy_id: impl Into<String>,
        start_at: DateTime<Utc>,
        end_at: DateTime<Utc>,
        device_id: impl Into<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            policy_id: policy_id.into(),
            session_type: SessionType::Focus,
            start_at,
            end_at,
            status: FocusSessionStatus::Active,
            enforcement_mode: EnforcementMode::Normal,
            notification_mode: NotificationMode::Normal,
            created_at: Utc::now(),
            completed_at: None,
            cancelled_at: None,
            device_id: device_id.into(),
            revision: 1,
        }
    }
}
