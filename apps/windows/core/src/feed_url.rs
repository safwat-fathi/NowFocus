//! Which feed rule, if any, a browser address bar is showing. Desktop "partial blocking" has no view of
//! the page, only of the address, so only surfaces that have their own URL can be enforced here:
//! YouTube Shorts, Instagram Reels / Explore and Facebook Reels. Port of
//! apps/macos/NowFocusCore/FeedURLMatcher.swift: keep the two tables and their test cases identical.
//!
//! The input is whatever the browser reports for its address bar. Chromium hides the scheme and a leading
//! `www.`, the user can be mid-edit, and a hostile page can pick look-alike hosts (`notyoutube.com`,
//! `youtube.com.evil.com`, `youtube.com@evil.com`), so the host must be exactly the site or a subdomain of it.

use crate::domain_validation::host_matches;

/// `feed_key` of the rules that desktop can enforce, in UI order (see `FEED_DEFS` in the app's state.rs).
pub const URL_FEEDS: [&str; 3] = ["shorts", "reels", "fbreels"];

/// The feed key whose page `address` is, or `None` for any other address or for text that is not a URL.
pub fn feed_for_url(address: &str) -> Option<&'static str> {
    let lower = address.trim().to_lowercase();
    let rest = lower
        .strip_prefix("https://")
        .or_else(|| lower.strip_prefix("http://"))
        .unwrap_or(&lower);
    // The host ends at the first of / ? #; whatever follows (minus query and fragment) is the path.
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let (authority, tail) = rest.split_at(end);
    let host = authority.split_once(':').map_or(authority, |(h, port)| {
        // A port is digits only; anything else after a colon is not a host we recognise.
        if port.chars().all(|c| c.is_ascii_digit()) {
            h
        } else {
            ""
        }
    });
    if host.is_empty()
        || !host
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
    {
        return None;
    }
    let path = tail.find(['?', '#']).map_or(tail, |i| &tail[..i]);
    let path = if path.is_empty() { "/" } else { path };

    if host_matches(host, "youtube.com") {
        return under(path, &["/shorts"]).then_some("shorts");
    }
    if host_matches(host, "instagram.com") {
        return under(path, &["/reels", "/reel", "/explore"]).then_some("reels");
    }
    if host_matches(host, "facebook.com") {
        return under(path, &["/reels", "/reel"]).then_some("fbreels");
    }
    None
}

/// `path` is `prefix` itself or something below it (`/shorts` and `/shorts/x`, not `/shortsfoo`).
fn under(path: &str, prefixes: &[&str]) -> bool {
    prefixes
        .iter()
        .any(|p| path == *p || path.strip_prefix(p).is_some_and(|r| r.starts_with('/')))
}

/// One action per page: after closing a tab, ignore matches for [`Self::COOLDOWN_MS`] so the next poll,
/// which may still read the old address while the tab is closing, cannot close a second tab.
#[derive(Default)]
pub struct CloseGate {
    last: Option<u64>,
}

impl CloseGate {
    pub const COOLDOWN_MS: u64 = 1_500;

    pub fn allow(&mut self, now_ms: u64) -> bool {
        if self
            .last
            .is_some_and(|t| now_ms.saturating_sub(t) < Self::COOLDOWN_MS)
        {
            return false;
        }
        self.last = Some(now_ms);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Keep these cases identical to scripts/CoreChecks/main.swift ("Feed URLs").
    #[test]
    fn matches_the_url_rules() {
        let cases = [
            ("https://www.youtube.com/shorts/abc123", Some("shorts")),
            ("youtube.com/shorts/abc123", Some("shorts")),
            ("m.youtube.com/shorts/abc123?feature=share", Some("shorts")),
            ("YouTube.com/Shorts", Some("shorts")),
            ("youtube.com:443/shorts/x", Some("shorts")),
            ("https://www.instagram.com/reels/", Some("reels")),
            ("instagram.com/reel/Cxyz/", Some("reels")),
            ("instagram.com/explore/", Some("reels")),
            ("instagram.com/explore", Some("reels")),
            ("https://www.facebook.com/reel/123", Some("fbreels")),
            ("web.facebook.com/reels", Some("fbreels")),
            ("m.facebook.com/reels/?x=1#top", Some("fbreels")),
        ];
        for (url, want) in cases {
            assert_eq!(feed_for_url(url), want, "{url}");
        }
    }

    #[test]
    fn ignores_everything_else() {
        let cases = [
            "https://www.youtube.com/",
            "youtube.com/watch?v=abc",
            "youtube.com/feed/subscriptions",
            "youtube.com/shortsfoo",
            "youtube.com/@creator/shorts", // a channel's Shorts tab is not the Shorts player
            "youtube.com/watch?v=x&next=/shorts/y",
            "instagram.com/",
            "instagram.com/explorer",
            "instagram.com/someone/",
            "facebook.com/",
            "facebook.com/watch/?v=1",
            "facebook.com/reelsfoo",
            "notyoutube.com/shorts/a",
            "youtube.com.evil.com/shorts/a",
            "youtube.com@evil.com/shorts/a",
            "evil.com/youtube.com/shorts/a",
            "youtube.com:evil/shorts/a",
            "www.google.com/search?q=youtube.com/shorts",
            "youtube shorts",
            "",
            "   ",
            "https://",
        ];
        for url in cases {
            assert_eq!(feed_for_url(url), None, "{url}");
        }
    }

    #[test]
    fn every_url_feed_is_reachable() {
        for key in URL_FEEDS {
            let url = match key {
                "shorts" => "youtube.com/shorts/a",
                "reels" => "instagram.com/reels/",
                _ => "facebook.com/reel/1",
            };
            assert_eq!(feed_for_url(url), Some(key));
        }
    }

    #[test]
    fn close_gate_allows_one_action_per_cooldown() {
        let mut g = CloseGate::default();
        assert!(g.allow(10_000));
        assert!(!g.allow(10_000 + CloseGate::COOLDOWN_MS - 1));
        assert!(g.allow(10_000 + CloseGate::COOLDOWN_MS));
    }
}
