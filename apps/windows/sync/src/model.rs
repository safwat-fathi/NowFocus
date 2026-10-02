use std::collections::HashMap;

use chrono::{DateTime, Utc};
use now_focus_core::{BedtimeSettings, Profile};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const POLICY: &str = "policy";
pub const BEDTIME: &str = "bedtime_settings";
pub const BEDTIME_ID: &str = "default";
pub const SESSION: &str = "session";

/// A server record as it arrives from pull, or as the "current" copy inside a stale push result.
#[derive(Debug, Clone)]
pub struct ServerRecord {
    pub typ: String,
    pub id: String,
    pub data: Value,
    pub deleted: bool,
    pub revision: i64,
    pub updated_at: DateTime<Utc>,
}

/// One change to push. `data` is `None` for a tombstone. `updated_at` is when the user made the change.
#[derive(Debug, Clone)]
pub struct Outgoing {
    pub typ: String,
    pub id: String,
    pub updated_at: DateTime<Utc>,
    pub data: Option<Value>,
    pub deleted: bool,
    pub fingerprint: String,
}

#[derive(Debug, Clone)]
pub struct PushOutcome {
    pub typ: String,
    pub id: String,
    pub status: String,
    pub record: Option<ServerRecord>,
    pub code: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Page {
    pub changes: Vec<ServerRecord>,
    pub cursor: i64,
    pub has_more: bool,
}

/// What the app knows about one synced record: the server's last `data` (`raw`), and whether the server copy
/// can be represented here. Whether it is *dirty* is always recomputed from the data, never stored.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct Meta {
    /// `None` = this record never reached the server.
    pub raw: Option<Value>,
    pub revision: i64,
    /// False for server records this app can't represent (allowlist profiles): kept in `raw`, never shown.
    #[serde(default = "yes")]
    pub imported: bool,
    /// Fingerprint of a payload the server rejected, so it isn't resent until the user changes something.
    pub rejected: Option<String>,
}

fn yes() -> bool {
    true
}

impl Meta {
    pub fn fresh(raw: Value, revision: i64) -> Self {
        Self {
            raw: Some(raw),
            revision,
            imported: true,
            rejected: None,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct SyncState {
    /// The account this device is linked to; `None` = never signed in, so nothing is synced at all.
    pub user_id: Option<String>,
    pub cursor: i64,
    /// False until the first full pull after linking has been applied; nothing is uploaded before that.
    pub initial_pull_done: bool,
    pub policies: HashMap<String, Meta>,
    pub bedtime: Option<Meta>,
}

/// Everything the sync rules read and write in one atomic step.
#[derive(Debug, Clone)]
pub struct Local {
    pub profiles: Vec<Profile>,
    pub bedtime: BedtimeSettings,
    /// When the user last changed bedtime here; the push time of an edit, and the loser of a conflict.
    pub bedtime_updated_at: Option<DateTime<Utc>>,
    pub state: SyncState,
}

#[derive(Debug, Clone)]
pub struct Applied {
    pub local: Local,
    pub bedtime_changed: bool,
    pub rejected: usize,
}
