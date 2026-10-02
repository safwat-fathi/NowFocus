//! Port of apps/android/.../Passes.kt. "I need WhatsApp for a client" without ending the session: a pass opens
//! one blocked app for five minutes, twice per session. Normal and Strict only: a Locked session has no exit
//! by definition (and Bedtime runs as Locked). A pass is not a cancel, so it never goes through the unlock
//! flow; this is its own gate, in one place.

use crate::focus_session::{EnforcementMode, SessionType};

pub const DURATION_SECS: i64 = 5 * 60;
pub const MAX_PER_SESSION: usize = 2;

pub fn passes_left(mode: EnforcementMode, session_type: SessionType, used: usize) -> usize {
    if mode == EnforcementMode::Locked || session_type == SessionType::BedtimeWinddown {
        0
    } else {
        MAX_PER_SESSION.saturating_sub(used)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_and_strict_get_two_locked_and_bedtime_none() {
        assert_eq!(
            passes_left(EnforcementMode::Normal, SessionType::Focus, 0),
            2
        );
        assert_eq!(
            passes_left(EnforcementMode::Strict, SessionType::Focus, 1),
            1
        );
        assert_eq!(
            passes_left(EnforcementMode::Strict, SessionType::Focus, 2),
            0
        );
        assert_eq!(
            passes_left(EnforcementMode::Strict, SessionType::Focus, 5),
            0
        );
        assert_eq!(
            passes_left(EnforcementMode::Locked, SessionType::Focus, 0),
            0
        );
        assert_eq!(
            passes_left(EnforcementMode::Normal, SessionType::BedtimeWinddown, 0),
            0
        );
    }
}
