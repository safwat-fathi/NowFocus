//! Wire types sent to the React UI. Kept separate from `now_focus_core`'s
//! domain types because these carry UI-computed fields (remaining time as a
//! display string, progress percent) that don't belong in the persisted
//! model. `camelCase` on the wire to match apps/windows/src/types.ts —
//! keep the two in sync by hand.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainRuleDto {
    pub id: String,
    pub domain: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplicationRuleDto {
    pub id: String,
    pub display_name: String,
    pub native_identifier: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedRuleDto {
    pub feed_key: String,
    pub label: String,
    pub sub: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileDto {
    pub id: String,
    pub name: String,
    pub domains: Vec<DomainRuleDto>,
    pub applications: Vec<ApplicationRuleDto>,
    pub feeds: Vec<FeedRuleDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionDto {
    pub id: String,
    pub profile_id: String,
    pub profile_name: String,
    /// "normal" | "strict" | "locked"
    pub mode: String,
    /// "active" | "completed" (mirrors FocusSessionStatus, lowercased)
    pub status: String,
    pub start_at: String,
    pub end_at: String,
    pub remaining_ms: i64,
    pub remaining_label: String,
    pub progress_pct: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UnlockStateDto {
    /// "typing" | "waiting"
    pub phase: String,
    pub sentence: String,
    pub typed: String,
    pub matches: bool,
    pub wait_remaining_ms: i64,
    pub wait_total_ms: i64,
    pub locked_mode: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceLayerDto {
    pub name: String,
    /// "running" | "on" | "missing" | "off"
    pub state: String,
    pub healthy: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthDto {
    /// "active" | "degraded" | "unavailable" | "unknown"
    pub website_blocking: String,
    pub app_blocking: String,
    pub layers: Vec<DeviceLayerDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsDto {
    pub today_minutes: i64,
    pub sessions_completed: i64,
    pub sessions_started: i64,
    pub block_attempts_today: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShieldDto {
    pub target_kind: String,
    pub target_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppStateDto {
    pub profiles: Vec<ProfileDto>,
    pub session: Option<SessionDto>,
    pub unlock: Option<UnlockStateDto>,
    pub shield: Option<ShieldDto>,
    pub health: HealthDto,
    pub stats: StatsDto,
}
