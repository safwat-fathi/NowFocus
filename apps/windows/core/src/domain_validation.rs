/// Port of apps/macos/NowFocusCore/DomainValidation.swift. This is a trust
/// boundary, not a convenience formatter: `service::network_enforcer` writes
/// the result straight into the Windows hosts file, so anything that isn't a
/// plausible bare hostname — no scheme, path, whitespace, or characters that
/// could inject extra lines — must come back `None`. Call this again at the
/// point of writing, not just here or in the UI (see commit d0dd7df on the
/// macOS side, which is exactly this lesson: the UI validating doesn't help
/// against a caller that skips it and talks to the enforcement boundary
/// directly).
const ALLOWED: &str = "abcdefghijklmnopqrstuvwxyz0123456789.-";

/// Returns a normalized lowercase hostname, or `None` if the input isn't a
/// plausible bare domain.
pub fn normalize(raw: &str) -> Option<String> {
    let mut domain = raw.trim().to_lowercase();

    for prefix in ["https://", "http://"] {
        if let Some(stripped) = domain.strip_prefix(prefix) {
            domain = stripped.to_string();
        }
    }
    if let Some(stripped) = domain.strip_prefix("www.") {
        domain = stripped.to_string();
    }

    // Strip anything after the host itself: path, port, query, fragment.
    if let Some(cut) = domain.find(|c| "/:?#".contains(c)) {
        domain.truncate(cut);
    }

    let ok = !domain.is_empty()
        && domain.contains('.')
        && !domain.starts_with('.')
        && !domain.ends_with('.')
        && !domain.starts_with('-')
        && !domain.ends_with('-')
        && domain.chars().all(|c| ALLOWED.contains(c));

    ok.then_some(domain)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Ported verbatim from apps/macos/test_domain_validation.swift (git
    // commit a69d2bd) — same cases, same expectations.

    #[test]
    fn bare_domain_passes_through() {
        assert_eq!(normalize("youtube.com"), Some("youtube.com".to_string()));
    }

    #[test]
    fn strips_scheme_and_www() {
        assert_eq!(
            normalize("https://www.youtube.com"),
            Some("youtube.com".to_string())
        );
    }

    #[test]
    fn strips_path_and_query() {
        assert_eq!(
            normalize("youtube.com/watch?v=abc"),
            Some("youtube.com".to_string())
        );
    }

    #[test]
    fn case_folds_and_strips_path() {
        assert_eq!(
            normalize("HTTP://Reddit.COM/r/all"),
            Some("reddit.com".to_string())
        );
    }

    #[test]
    fn trims_surrounding_whitespace() {
        assert_eq!(
            normalize("  twitter.com  "),
            Some("twitter.com".to_string())
        );
    }

    #[test]
    fn rejects_newline_and_space_injection() {
        // Would otherwise let a caller inject an extra hosts-file line.
        assert_eq!(normalize("evil.com\n1.2.3.4 bank.com"), None);
    }

    #[test]
    fn rejects_non_domain_text() {
        assert_eq!(normalize("not a domain"), None);
    }

    #[test]
    fn rejects_empty_string() {
        assert_eq!(normalize(""), None);
    }

    #[test]
    fn rejects_leading_hyphen() {
        assert_eq!(normalize("-bad.com"), None);
    }
}
