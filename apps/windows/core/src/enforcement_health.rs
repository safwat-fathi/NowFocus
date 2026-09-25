use serde::{Deserialize, Serialize};

/// Port of apps/macos/NowFocusCore/EnforcementHealth.swift. Note this is the
/// real 3-field `PlatformCapabilities` that shipped, not the 9-field version
/// sketched in focus_app_technical_architecture.md §10 — match what's
/// actually built, not the aspirational spec.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EnforcementStatus {
    Unknown,
    Active,
    Degraded,
    Unavailable,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct PlatformCapabilities {
    pub can_block_websites: bool,
    pub can_block_applications: bool,
    pub is_privileged_service_running: bool,
}
