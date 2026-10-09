import Foundation

/// Which feed rule, if any, a browser tab's address is. A Mac cannot see inside a page, only its address, so only
/// surfaces with their own URL can be enforced here: YouTube Shorts, Instagram Reels / Explore and Facebook Reels.
/// Port of apps/windows/core/src/feed_url.rs: keep the two tables and their test cases identical.
///
/// The address comes from the browser (AppleScript), but a hostile page picks its own host, so a look-alike
/// (`notyoutube.com`, `youtube.com.evil.com`, `youtube.com@evil.com`) must not match: the host is exactly the
/// site or a subdomain of it, and the path is compared on segment boundaries.
public enum FeedURLMatcher {
    private static let hostCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")

    /// The wire name (`YT_SHORTS`, `IG_REELS`, `FB_REELS`) of the rule `address` is a page of, or nil.
    public static func rule(for address: String) -> String? {
        let lower = address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var rest = Substring(lower)
        for scheme in ["https://", "http://"] where rest.hasPrefix(scheme) {
            rest = rest.dropFirst(scheme.count)
            break
        }
        // The host ends at the first of / ? #; what follows (minus query and fragment) is the path.
        let end = rest.firstIndex(where: { "/?#".contains($0) }) ?? rest.endIndex
        let authority = rest[rest.startIndex..<end]
        let tail = rest[end...]

        var host = authority
        if let colon = authority.firstIndex(of: ":") {
            let port = authority[authority.index(after: colon)...]
            // A port is digits only; anything else after a colon is not a host we recognise.
            guard port.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            host = authority[authority.startIndex..<colon]
        }
        guard !host.isEmpty, host.unicodeScalars.allSatisfy({ hostCharacters.contains($0) }) else { return nil }

        let pathEnd = tail.firstIndex(where: { "?#".contains($0) }) ?? tail.endIndex
        var path = String(tail[tail.startIndex..<pathEnd])
        if path.isEmpty { path = "/" }

        if matches(host: host, site: "youtube.com") { return under(path, ["/shorts"]) ? "YT_SHORTS" : nil }
        if matches(host: host, site: "instagram.com") { return under(path, ["/reels", "/reel", "/explore"]) ? "IG_REELS" : nil }
        if matches(host: host, site: "facebook.com") { return under(path, ["/reels", "/reel"]) ? "FB_REELS" : nil }
        return nil
    }

    /// "youtube.com" covers "m.youtube.com" but not "notyoutube.com".
    private static func matches(host: Substring, site: String) -> Bool {
        let h = host.hasSuffix(".") ? String(host.dropLast()) : String(host)
        return h == site || h.hasSuffix("." + site)
    }

    /// `path` is a prefix itself or something below it (`/shorts` and `/shorts/x`, not `/shortsfoo`).
    private static func under(_ path: String, _ prefixes: [String]) -> Bool {
        prefixes.contains { path == $0 || path.hasPrefix($0 + "/") }
    }
}

/// One action per page: after closing a tab, ignore matches for `cooldown` so the next poll, which may still
/// read the old address while the tab is closing, cannot close a second tab.
public struct CloseGate {
    public static let cooldown: TimeInterval = 1.5
    private var last: Date?

    public init() {}

    public mutating func allow(now: Date = Date()) -> Bool {
        if let last, now.timeIntervalSince(last) < Self.cooldown { return false }
        last = now
        return true
    }
}
