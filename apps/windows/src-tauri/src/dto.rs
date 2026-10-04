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
    /// `"blocklist"` or `"allowlist"`; fixed when the profile is created.
    pub mode: now_focus_core::PolicyMode,
    pub domains: Vec<DomainRuleDto>,
    pub applications: Vec<ApplicationRuleDto>,
    pub feeds: Vec<FeedRuleDto>,
}

/// One daily limit as the Limits screen shows it.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LimitDto {
    pub key: String,
    pub label: String,
    pub is_site: bool,
    /// Minutes in force now; 0 = none.
    pub minutes: u32,
    pub used_minutes: i64,
    pub used_up: bool,
    /// "removed at midnight" / "60 min from midnight" while a change waits.
    pub pending: Option<String>,
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
    /// A cheat day is pausing this session's blocking.
    pub paused: bool,
    /// A Bedtime wind-down: the shield names it as bedtime, not a focus session.
    pub bedtime: bool,
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
pub struct TargetCountDto {
    pub name: String,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsDto {
    /// 0..=100 for this week, or None with no sessions.
    pub focus_score: Option<i64>,
    /// Counts-only text for "Copy this week".
    pub week_summary: String,
    pub today_minutes: i64,
    pub sessions_completed: i64,
    pub sessions_started: i64,
    pub block_attempts_today: i64,
    /// Minutes focused per day, Monday..Sunday, this week (7 entries).
    pub week_minutes: Vec<i64>,
    pub streak_days: i64,
    /// 0.0..=1.0 (completed / started) this week.
    pub completion_rate: f64,
    /// Most-turned-away targets this week, most frequent first.
    pub top_targets: Vec<TargetCountDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommitmentDto {
    pub domains: Vec<String>,
    pub end_at: String,
    pub can_cancel_now: bool,
    pub remaining_secs: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BedtimeDto {
    pub enabled: bool,
    pub wind_down_minute: i64,
    pub sleep_minute: i64,
    pub wake_minute: i64,
    pub lock_at_sleep: bool,
    pub policy_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShieldDto {
    pub target_kind: String,
    pub target_name: String,
    /// Passes the running session still has for this app (0 = none offered).
    pub passes_left: usize,
    /// For a daily-limit shield: the minutes the limit allowed (the screen says "your N minutes"). 0 otherwise.
    pub limit_minutes: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScheduleDto {
    /// Empty for a new schedule.
    pub id: String,
    pub name: String,
    /// 0 = Monday .. 6 = Sunday: the days the window starts on.
    pub days: Vec<u8>,
    pub start_minute: i64,
    pub end_minute: i64,
    pub policy_id: String,
    pub mode: String,
    pub enabled: bool,
    #[serde(default)]
    pub days_label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CheatDayDto {
    pub start_at: String,
    pub end_at: String,
    pub active: bool,
    pub upcoming: bool,
}

/// What the Devices screen shows about the optional account. Signed out, the app makes no network calls.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatusDto {
    pub signed_in: bool,
    pub email: Option<String>,
    pub syncing: bool,
    pub last_synced_at: Option<String>,
    /// Human-readable, e.g. "Offline. Will retry." None when all is well.
    pub problem: Option<String>,
    /// Changes the server refused (they stay on this PC; editing them again retries).
    pub rejected: usize,
    /// Whether this PC joins sessions started on the account's other devices.
    pub join_remote: bool,
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
    /// `Some` while a 14-day commitment is in effect (cached; refreshed on
    /// apply/clear and the 30s tick, never per-snapshot — see AppState).
    pub commitment: Option<CommitmentDto>,
    pub bedtime: BedtimeDto,
    pub schedules: Vec<ScheduleDto>,
    pub cheat_day: Option<CheatDayDto>,
    pub limits: Vec<LimitDto>,
    /// Day starts (RFC3339) a cheat day can still be planned for.
    pub cheat_options: Vec<String>,
    pub sync: SyncStatusDto,
}
