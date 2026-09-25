//! now-focus-core: the platform-neutral domain model, session state machine,
//! domain validation, and persistence shared by the Windows app and its
//! background service. Deliberately has no Windows-only dependencies — see
//! each module's doc comment for the macOS/Android source it ports from.
//! `cargo test` here is expected to pass on any host OS.

pub mod block_policy;
pub mod domain_validation;
pub mod enforcement_health;
pub mod focus_session;
pub mod persistence;
pub mod session_engine;

pub use block_policy::{ApplicationRule, BlockPolicy, DomainRule, FeedRule, PolicyMode, Profile};
pub use enforcement_health::{EnforcementStatus, PlatformCapabilities};
pub use focus_session::{
    EnforcementMode, FocusSession, FocusSessionStatus, NotificationMode, SessionType,
};
pub use persistence::{Database, PersistenceError};
