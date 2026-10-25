//! Free vs Pro. The server owns the plan; this app only keeps the last answer it got (see `docs/pro-launch.md`).

/// Off until Pro launches: while false every feature stays open, signed in or not. Flipped in the release that
/// goes out together with the API's `PRO_ENFORCED=1`.
pub const PRO_ENFORCED: bool = false;

/// `stored` is the last thing the account's sync said (`Some(true)` Pro, `Some(false)` Free), `None` when the
/// account has never been asked or nobody is signed in: both count as Free once the gate is enforced.
pub fn is_pro(enforced: bool, stored: Option<bool>) -> bool {
    !enforced || stored == Some(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn everything_is_open_while_the_gate_is_off() {
        for stored in [None, Some(false), Some(true)] {
            assert!(is_pro(false, stored));
        }
    }

    #[test]
    fn enforced_only_a_known_pro_account_counts() {
        assert!(is_pro(true, Some(true)));
        assert!(!is_pro(true, Some(false)));
        assert!(!is_pro(true, None), "signed out or not asked yet is Free");
    }

    #[test]
    #[allow(clippy::assertions_on_constants)] // a deliberate speed bump: flipping the constant should be a decision
    fn the_shipped_build_is_dormant() {
        assert!(
            !PRO_ENFORCED,
            "launching Pro? follow docs/pro-launch.md, then update this test"
        );
    }
}
