//! now-focus-core: the platform-neutral domain model, session state machine,
//! domain validation, and persistence shared by the Windows app and its
//! background service. Deliberately has no Windows-only dependencies — see
//! each module's doc comment for the macOS/Android source it ports from.
//! `cargo test` here is expected to pass on any host OS.

pub mod allowlist;
pub mod bedtime_schedule;
pub mod block_policy;
pub mod cheat_day;
pub mod commitment;
pub mod daily_limit;
pub mod domain_validation;
pub mod enforcement_health;
pub mod focus_session;
pub mod history_stats;
pub mod hosts_block;
pub mod passes;
pub mod persistence;
pub mod schedule;
pub mod session_engine;

pub use bedtime_schedule::BedtimeSettings;
pub use block_policy::{ApplicationRule, BlockPolicy, DomainRule, FeedRule, PolicyMode, Profile};
pub use commitment::CommitmentState;
pub use enforcement_health::{EnforcementStatus, PlatformCapabilities};
pub use focus_session::{
    EnforcementMode, FocusSession, FocusSessionStatus, NotificationMode, SessionOrigin, SessionType,
};
pub use persistence::{Database, PersistenceError};
