package app.getnowfocus.android

/** What to do when a rule's surface is on screen: back out of it, or hide just the list. */
enum class PartialAction { LEAVE, COVER }

enum class PartialRule(val label: String, val detail: String, val action: PartialAction) {
    YT_SHORTS("YouTube Shorts", "Backs out of the Shorts player and tab", PartialAction.LEAVE),
    YT_HOME("YouTube Home feed", "Hides recommended videos on the Home tab", PartialAction.COVER),
    YT_RELATED("YouTube up next / related", "Hides the list under a playing video", PartialAction.COVER),
    FB_REELS("Facebook Reels", "Backs out of the Reels tab and viewer", PartialAction.LEAVE),
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
 *   YouTube: unverified   Facebook: unverified
 */
object PartialSignatures {
    const val YOUTUBE = "com.google.android.youtube"
    val FACEBOOK = setOf("com.facebook.katana", "com.facebook.lite")
    val PACKAGES = FACEBOOK + YOUTUBE

    private const val YT = "$YOUTUBE:id/"
    val SHORTS_IDS = setOf("${YT}reel_recycler", "${YT}reel_player_page_container")
    const val HOME_FEED_ID = "${YT}results"
    const val RELATED_LIST_ID = "${YT}watch_list"
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
