import Foundation

/// The "partial blocking" surfaces, identical on every platform: same identifiers, order and labels as Android's
/// `PartialRule`. Android enforces all seven by reading the screen. A Mac cannot see inside a page, so it
/// enforces only the three that have their own address (`enforceable`, see `FeedURLMatcher`): it reads the front
/// browser tab's address and closes the tab. The other four are carried through sync untouched.
public enum FeedRules {
    public struct Rule: Identifiable {
        public let id: String
        public let label: String
        public let detail: String
    }

    public static let all: [Rule] = [
        Rule(id: "YT_SHORTS", label: "YouTube Shorts", detail: "Backs out of the Shorts player and tab"),
        Rule(id: "YT_HOME", label: "YouTube Home feed", detail: "Hides recommended videos on the Home tab"),
        Rule(id: "YT_RELATED", label: "YouTube up next / related", detail: "Hides the list under a playing video"),
        Rule(id: "FB_REELS", label: "Facebook Reels", detail: "Backs out of the Reels tab and viewer"),
        Rule(id: "IG_REELS", label: "Instagram Reels & Explore", detail: "Backs out of the Reels tab, viewer and Explore tab"),
        Rule(id: "X_FOR_YOU", label: "X \u{201C}For you\u{201D} feed", detail: "Hides the For you timeline; Following stays open"),
        Rule(id: "TT_FOR_YOU", label: "TikTok feed", detail: "Hides the For You and Following feed; Inbox, Explore and Me stay open"),
    ]

    /// The rules a Mac enforces, in the order the profile screen lists them.
    public static let enforceable = ["YT_SHORTS", "IG_REELS", "FB_REELS"]
}
