package app.getnowfocus.android

/** What to do when a rule's surface is on screen: back out of it, or hide just the list. */
enum class PartialAction { LEAVE, COVER }

enum class PartialRule(val label: String, val detail: String, val action: PartialAction) {
    YT_SHORTS("YouTube Shorts", "Backs out of the Shorts player and tab", PartialAction.LEAVE),
    YT_HOME("YouTube Home feed", "Hides recommended videos on the Home tab", PartialAction.COVER),
    YT_RELATED("YouTube up next / related", "Hides the list under a playing video", PartialAction.COVER),
    FB_REELS("Facebook Reels", "Backs out of the Reels tab and viewer", PartialAction.LEAVE),
    IG_REELS("Instagram Reels & Explore", "Backs out of the Reels tab, viewer and Explore tab", PartialAction.LEAVE),
    X_FOR_YOU("X \u201cFor you\u201d feed", "Hides the For you timeline; Following stays open", PartialAction.COVER),
}

/** Plain-data copy of an AccessibilityNodeInfo subtree, so matching is testable on the JVM (no android.graphics.Rect). */
data class NodeSnapshot(
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val selected: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val children: List<NodeSnapshot> = emptyList(),
)

data class PartialMatch(val rule: PartialRule, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Screen signatures for the third-party apps. These ids / labels are undocumented and change
 * between app releases: everything app-specific lives in this object so a release change is a
 * one-place fix. ponytail: English-only label fallbacks; add per-locale labels if it matters.
 *
 * Candidates, NOT yet verified on a device - confirm with a uiautomator dump / Accessibility
 * Inspector, then record the app versions here:
 *   YouTube: unverified   Facebook: unverified   Instagram: unverified   X: unverified
 * Instagram and X are the newest and least checked (written from how those apps are commonly
 * described, with no device to test on): expect to correct their ids and labels.
 */
object PartialSignatures {
    const val YOUTUBE = "com.google.android.youtube"
    val FACEBOOK = setOf("com.facebook.katana", "com.facebook.lite")
    const val INSTAGRAM = "com.instagram.android"
    const val X = "com.twitter.android"
    val PACKAGES = FACEBOOK + YOUTUBE + INSTAGRAM + X

    private const val YT = "$YOUTUBE:id/"
    val SHORTS_IDS = setOf("${YT}reel_recycler", "${YT}reel_player_page_container")
    const val HOME_FEED_ID = "${YT}results"
    const val RELATED_LIST_ID = "${YT}watch_list"

    const val IG_REELS_VIEWER_ID_PART = "clips_viewer"
    val IG_TAB_LABELS = listOf("Reels", "Search and explore")

    const val X_TIMELINE_ID = "$X:id/timeline"
    const val X_FOR_YOU_LABEL = "For you"
}

object PartialBlocking {

    /**
     * The first enabled rule whose surface is showing in [root] for [pkg], or null. Priority:
     * Shorts, then the related list (a video is playing over the Home tab), then Home.
     */
    fun detect(pkg: String, root: NodeSnapshot?, enabled: Set<PartialRule>): PartialMatch? {
        if (root == null || enabled.isEmpty()) return null
        return when (pkg) {
            PartialSignatures.YOUTUBE -> youtube(root, enabled)
            in PartialSignatures.FACEBOOK -> facebook(root, enabled)
            PartialSignatures.INSTAGRAM -> instagram(root, enabled)
            PartialSignatures.X -> x(root, enabled)
            else -> null
        }
    }

    private fun youtube(root: NodeSnapshot, enabled: Set<PartialRule>): PartialMatch? {
        if (PartialRule.YT_SHORTS in enabled) {
            val shorts = root.find { it.viewId in PartialSignatures.SHORTS_IDS }
                ?: root.find { it.selected && it.label().equals("Shorts", ignoreCase = true) }
            if (shorts != null) return PartialMatch(PartialRule.YT_SHORTS, shorts.left, shorts.top, shorts.right, shorts.bottom)
        }
        if (PartialRule.YT_RELATED in enabled) {
            root.find { it.viewId == PartialSignatures.RELATED_LIST_ID && it.hasArea() }
                ?.let { return PartialMatch(PartialRule.YT_RELATED, it.left, it.top, it.right, it.bottom) }
        }
        if (PartialRule.YT_HOME in enabled && root.find { it.selected && it.label().equals("Home", ignoreCase = true) } != null) {
            root.find { it.viewId == PartialSignatures.HOME_FEED_ID && it.hasArea() }
                ?.let { return PartialMatch(PartialRule.YT_HOME, it.left, it.top, it.right, it.bottom) }
        }
        return null
    }

    private fun facebook(root: NodeSnapshot, enabled: Set<PartialRule>): PartialMatch? {
        if (PartialRule.FB_REELS !in enabled) return null
        // Only the selected tab / the full-screen viewer: the inline carousel in the News Feed must not
        // match, since backing out of the feed would leave Facebook altogether.
        val reels = root.find { it.selected && it.label().startsWith("Reels", ignoreCase = true) }
            ?: root.find { it.viewId?.contains("reels_viewer") == true }
        return reels?.let { PartialMatch(PartialRule.FB_REELS, it.left, it.top, it.right, it.bottom) }
    }

    private fun instagram(root: NodeSnapshot, enabled: Set<PartialRule>): PartialMatch? {
        if (PartialRule.IG_REELS !in enabled) return null
        // The bottom-bar buttons always exist, so only a SELECTED Reels / Explore tab (or the full-screen
        // viewer) counts. Reels text inside the feed must not match: backing out of the feed leaves Instagram.
        val surface = root.find { n -> n.selected && PartialSignatures.IG_TAB_LABELS.any { n.label().startsWith(it, ignoreCase = true) } }
            ?: root.find { it.viewId?.contains(PartialSignatures.IG_REELS_VIEWER_ID_PART) == true }
        return surface?.let { PartialMatch(PartialRule.IG_REELS, it.left, it.top, it.right, it.bottom) }
    }

    private fun x(root: NodeSnapshot, enabled: Set<PartialRule>): PartialMatch? {
        if (PartialRule.X_FOR_YOU !in enabled) return null
        // COVER, never LEAVE: BACK from X's home timeline exits the app.
        if (root.find { it.selected && it.label().startsWith(PartialSignatures.X_FOR_YOU_LABEL, ignoreCase = true) } == null) return null
        return root.find { it.viewId == PartialSignatures.X_TIMELINE_ID && it.hasArea() }
            ?.let { PartialMatch(PartialRule.X_FOR_YOU, it.left, it.top, it.right, it.bottom) }
    }

    private fun NodeSnapshot.label() = desc ?: text ?: ""
    private fun NodeSnapshot.hasArea() = right > left && bottom > top

    private fun NodeSnapshot.find(pred: (NodeSnapshot) -> Boolean): NodeSnapshot? {
        if (pred(this)) return this
        for (c in children) c.find(pred)?.let { return it }
        return null
    }
}

enum class LeaveStep { WAIT, BACK, HOME }

/**
 * Paces "back out of this screen": BACK, then let the transition settle ([COOLDOWN_MS]) before
 * trying again, and after two BACKs inside [WINDOW_MS] go HOME instead so the user can never be
 * bounced around inside the app (or trapped) if BACK isn't leaving the surface.
 */
class LeaveGate {
    private val backs = ArrayDeque<Long>()
    private var last = Long.MIN_VALUE / 2

    fun next(now: Long): LeaveStep {
        if (now - last < COOLDOWN_MS) return LeaveStep.WAIT
        last = now
        backs.removeAll { now - it > WINDOW_MS }
        if (backs.size >= 2) { backs.clear(); return LeaveStep.HOME }
        backs.add(now)
        return LeaveStep.BACK
    }

    companion object {
        const val COOLDOWN_MS = 600L
        const val WINDOW_MS = 3_000L
    }
}

/**
 * One block event per stretch on a matched surface. A COVER rule keeps matching on every re-check
 * while the user sits on the feed, so logging each match would turn one urge into a row every few
 * seconds and inflate Home stats, "Tries today" and the urge map.
 */
class MatchLogGate {
    private var last: Pair<String, PartialRule>? = null

    fun shouldLog(pkg: String, rule: PartialRule): Boolean {
        val key = pkg to rule
        if (key == last) return false
        last = key
        return true
    }

    /** The surface is gone (or the app was left): the next match is a new attempt. */
    fun clear() { last = null }
}
