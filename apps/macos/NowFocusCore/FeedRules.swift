import Foundation

/// The six "partial blocking" surfaces, identical on every platform: same identifiers, order and labels
/// as Android's `PartialRule`. Only Android enforces them today (it reads the screen with its
/// accessibility service); a Mac has no equivalent, so here they are listed but not switchable.
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
    ]
}
