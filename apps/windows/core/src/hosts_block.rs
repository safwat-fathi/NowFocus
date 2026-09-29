//! Pure hosts-file region editing, shared by session and commitment blocking so
//! each owns an independent marker-delimited region. Ported from the region
//! logic in apps/macos/NowFocusDaemon/NetworkEnforcer.swift (and this app's
//! service::network_enforcer): removing or rewriting one region never touches
//! another's, which is what keeps a focus-session ending from wiping a live
//! 14-day commitment (macOS's real bug, fixed by the marker split).
//!
//! The service does domain validation at its write boundary and passes the
//! already-validated `127.0.0.1 <domain>` body lines here; this module does
//! only text-region surgery, no validation.

/// Remove the region between `start_marker` and `end_marker` (markers included),
/// leaving the rest untouched. A no-op when the region is absent.
pub fn remove_region(content: &str, start_marker: &str, end_marker: &str) -> String {
    let mut inside = false;
    let mut out: Vec<&str> = Vec::new();
    for line in content.lines() {
        if line == start_marker {
            inside = true;
            continue;
        }
        if line == end_marker {
            inside = false;
            continue;
        }
        if !inside {
            out.push(line);
        }
    }
    out.join("\n").trim().to_string()
}

/// Replace (or insert) the marked region with `body_lines`. When `body_lines`
/// is empty the region is removed, never left as empty markers.
pub fn with_region(
    content: &str,
    start_marker: &str,
    end_marker: &str,
    body_lines: &[String],
) -> String {
    let base = remove_region(content, start_marker, end_marker);
    if body_lines.is_empty() {
        return base;
    }
    let mut out = if base.is_empty() {
        String::new()
    } else {
        format!("{base}\n\n")
    };
    out.push_str(start_marker);
    out.push('\n');
    for line in body_lines {
        out.push_str(line);
        out.push('\n');
    }
    out.push_str(end_marker);
    out.push('\n');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const S_START: &str = "### FOCUS APP BLOCK START ###";
    const S_END: &str = "### FOCUS APP BLOCK END ###";
    const C_START: &str = "### FOCUS COMMITMENT BLOCK START ###";
    const C_END: &str = "### FOCUS COMMITMENT BLOCK END ###";

    #[test]
    fn remove_region_strips_only_the_marked_region() {
        let content = format!("127.0.0.1 localhost\n\n{S_START}\n127.0.0.1 youtube.com\n{S_END}\n");
        assert_eq!(
            remove_region(&content, S_START, S_END),
            "127.0.0.1 localhost"
        );
    }

    #[test]
    fn a_session_clear_leaves_a_live_commitment_intact() {
        // The macOS layer-independence self-check, ported.
        let base = "127.0.0.1 localhost";
        let with_commit = with_region(base, C_START, C_END, &["127.0.0.1 x.com".to_string()]);
        let both = with_region(
            &with_commit,
            S_START,
            S_END,
            &["127.0.0.1 y.com".to_string()],
        );
        assert!(both.contains("x.com") && both.contains("y.com"));

        let after_session_clear = remove_region(&both, S_START, S_END);
        assert!(
            after_session_clear.contains("x.com"),
            "commitment must survive a session clear"
        );
        assert!(
            !after_session_clear.contains("y.com"),
            "session block must be gone"
        );
        assert!(after_session_clear.contains("localhost"));
    }

    #[test]
    fn reapplying_a_region_does_not_stack_markers() {
        let base = "127.0.0.1 localhost";
        let once = with_region(base, S_START, S_END, &["127.0.0.1 x.com".to_string()]);
        let twice = with_region(&once, S_START, S_END, &["127.0.0.1 y.com".to_string()]);
        assert_eq!(twice.matches(S_START).count(), 1);
        assert!(twice.contains("y.com") && !twice.contains("x.com"));
    }

    #[test]
    fn empty_body_removes_the_region_without_empty_markers() {
        let base = "127.0.0.1 localhost";
        let applied = with_region(base, S_START, S_END, &["127.0.0.1 x.com".to_string()]);
        let cleared = with_region(&applied, S_START, S_END, &[]);
        assert_eq!(cleared, base);
        assert!(!cleared.contains(S_START));
    }
}
