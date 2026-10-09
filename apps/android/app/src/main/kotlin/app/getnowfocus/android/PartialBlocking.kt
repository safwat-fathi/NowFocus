package app.getnowfocus.android

import androidx.annotation.StringRes

/** What to do when a rule's surface is on screen: back out of it, or hide just the list. */
enum class PartialAction { LEAVE, COVER }

enum class PartialRule(@StringRes val label: Int, @StringRes val detail: Int, val action: PartialAction) {
    YT_SHORTS(R.string.partial_yt_shorts, R.string.partial_yt_shorts_sub, PartialAction.LEAVE),
    YT_HOME(R.string.partial_yt_home, R.string.partial_yt_home_sub, PartialAction.COVER),
    YT_RELATED(R.string.partial_yt_related, R.string.partial_yt_related_sub, PartialAction.COVER),
    FB_REELS(R.string.partial_fb_reels, R.string.partial_fb_reels_sub, PartialAction.LEAVE),
    IG_REELS(R.string.partial_ig_reels, R.string.partial_ig_reels_sub, PartialAction.LEAVE),
    X_FOR_YOU(R.string.partial_x_for_you, R.string.partial_x_for_you_sub, PartialAction.COVER),
    TT_FOR_YOU(R.string.partial_tt_feed, R.string.partial_tt_feed_sub, PartialAction.COVER),
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

/** [passThrough]: a cover that lets touches reach the app, for a screen whose own controls are scrolled away. */
data class PartialMatch(
    val rule: PartialRule, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val passThrough: Boolean = false,
    /** Normally the rule's own action; TikTok's rule covers its feed tabs but leaves a full-screen player. */
    val action: PartialAction = rule.action,
)

/**
 * What X's home screen showed last. X hides the For you / Following row when the feed is scrolled, and
 * from then on nothing on screen says which tab it is, so the last tab seen stands in. One per service.
 */
class FeedMemory {
    var xForYou = true
}

/**
 * Screen signatures for the third-party apps. Checked against real screens on 2026-10-09 (Galaxy M52, Android 13):
 * YouTube 21.40.161, Facebook 581.0.0.45.58, Instagram 450.0.0.50.77, X 12.32.0, TikTok Go. These ids / labels
 * are undocumented and change between app releases: everything app-specific lives in this object so a release
 * change is a one-place fix. ponytail: English-only label fallbacks; add per-locale labels if it matters.
 */
object PartialSignatures {
    const val YOUTUBE = "com.google.android.youtube"
    val FACEBOOK = setOf("com.facebook.katana", "com.facebook.lite")
    const val INSTAGRAM = "com.instagram.android"
    const val X = "com.twitter.android"
    val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")
    val PACKAGES = FACEBOOK + YOUTUBE + INSTAGRAM + X + TIKTOK

    private const val YT = "$YOUTUBE:id/"
    val SHORTS_IDS = setOf("${YT}reel_recycler", "${YT}reel_player_page_container")
    const val HOME_FEED_ID = "${YT}results"
    const val RELATED_LIST_ID = "${YT}watch_list"

    const val IG_REELS_VIEWER_ID_PART = "clips_viewer"
    val IG_TAB_LABELS = listOf("Reels", "Search and explore")

    /** X is Compose: ids are bare test tags, so compare the part after any "/". */
    const val X_HOME_ID = "scaffold_home_tabbed"
    const val X_FOR_YOU_LABEL = "For you"
    /** Content starts below the 90 px status bar on the test device. */
    const val X_CONTENT_TOP = 90

    const val TT_FOR_YOU_LABEL = "For You"
    const val TT_FOLLOWING_LABEL = "Following"
    /** TikTok's For You / Following tabs sit in the top bar, above this y. */
    const val TT_TAB_ROW_BOTTOM = 300
    /** The bottom bar's labels (Home, Explore, Inbox, Me); the bar's top edge is [TT_NAV_ABOVE_LABEL] px above them. */
    val TT_NAV_LABELS = setOf("Home", "Explore", "Inbox", "Me")
    const val TT_NAV_ABOVE_LABEL = 83
    /** TikTok's video player (and the feed behind it) is built on this view; its Explore grid exposes nothing at all. */
    const val TT_PLAYER_ID = "gesture_deal_view"
    /** Below the 90 px status bar; TikTok's Explore search bar starts here. */
    const val TT_CONTENT_TOP = 86
    const val TT_NAV_CENTER_LABEL = "Creative home plus"
}

object PartialBlocking {

    /**
     * The first enabled rule whose surface is showing in [root] for [pkg], or null. Priority:
     * Shorts, then the related list (a video is playing over the Home tab), then Home.
     */
    fun detect(pkg: String, root: NodeSnapshot?, enabled: Set<PartialRule>, mem: FeedMemory = FeedMemory()): PartialMatch? {
        if (root == null || enabled.isEmpty()) return null
        return when (pkg) {
            PartialSignatures.YOUTUBE -> youtube(root, enabled)
            in PartialSignatures.FACEBOOK -> facebook(root, enabled)
            PartialSignatures.INSTAGRAM -> instagram(root, enabled)
            PartialSignatures.X -> x(root, enabled, mem)
            in PartialSignatures.TIKTOK -> tiktok(root, enabled)
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

    /**
     * X's home is a Compose screen: the feed list has no id or label, so the cover is sized from the tab row
     * above it and the bottom bar below. With the tab row scrolled away the cover runs from the status bar to
     * the bottom and lets touches through, so scrolling back up still works. COVER, never LEAVE: BACK from
     * X's home timeline exits the app.
     */
    private fun x(root: NodeSnapshot, enabled: Set<PartialRule>, mem: FeedMemory): PartialMatch? {
        if (PartialRule.X_FOR_YOU !in enabled) return null
        val home = root.find { it.viewId?.substringAfterLast('/') == PartialSignatures.X_HOME_ID } ?: return null
        val tabRowShown = root.find { it.label().startsWith(PartialSignatures.X_FOR_YOU_LABEL, ignoreCase = true) } != null
        val tab = root.selectedWith(PartialSignatures.X_FOR_YOU_LABEL)
        if (tabRowShown) mem.xForYou = tab != null
        if (!mem.xForYou) return null
        val top = tab?.bottom ?: PartialSignatures.X_CONTENT_TOP
        val navTop = root.find { it.selected && it.left == 0 && it.top > top && it.bottom - it.top < 400 }?.top
        // ponytail: 95% of the scaffold height stands in for the bottom bar's top when it's scrolled away.
        val bottom = navTop ?: (home.bottom * 95 / 100)
        return PartialMatch(PartialRule.X_FOR_YOU, 0, top, home.right, bottom, passThrough = tab == null)
    }

    /**
     * TikTok, three screens:
     *  - the main feed (For You and Following both in the top bar): cover it and the top tabs (Following, Friends, For You, LIVE, search), bottom bar left open;
     *  - Explore (bottom bar showing and nothing else labelled: its grid isn't exposed to Accessibility): cover it;
     *  - a video player with no bottom bar (opened from Explore, a profile, a link): back out of it.
     * Inbox and Me label their own content, so they never match.
     * ponytail: the bar's top is a fixed 83 px above its labels (Galaxy M52, 1080 px wide); scale it by density if another phone shows a seam.
     */
    private fun tiktok(root: NodeSnapshot, enabled: Set<PartialRule>): PartialMatch? {
        if (PartialRule.TT_FOR_YOU !in enabled) return null
        val inTabRow = { label: String -> root.find { it.label().equals(label, true) && it.top < PartialSignatures.TT_TAB_ROW_BOTTOM } }
        val navLabelTop = root.find { n -> n.top > PartialSignatures.TT_TAB_ROW_BOTTOM && PartialSignatures.TT_NAV_LABELS.any { n.label().equals(it, true) } }?.top
        if (navLabelTop == null) {
            val player = root.find { it.viewId?.substringAfterLast('/') == PartialSignatures.TT_PLAYER_ID && it.hasArea() } ?: return null
            return PartialMatch(PartialRule.TT_FOR_YOU, player.left, player.top, player.right, player.bottom, action = PartialAction.LEAVE)
        }
        val navTop = navLabelTop - PartialSignatures.TT_NAV_ABOVE_LABEL
        if (inTabRow(PartialSignatures.TT_FOR_YOU_LABEL) != null && inTabRow(PartialSignatures.TT_FOLLOWING_LABEL) != null)
            return PartialMatch(PartialRule.TT_FOR_YOU, root.left, PartialSignatures.TT_CONTENT_TOP, root.right, navTop)
        val allowed = PartialSignatures.TT_NAV_LABELS + PartialSignatures.TT_NAV_CENTER_LABEL
        if (root.labels().all { l -> allowed.any { it.equals(l, true) } })
            return PartialMatch(PartialRule.TT_FOR_YOU, root.left, PartialSignatures.TT_CONTENT_TOP, root.right, navTop)
        return null
    }

    private fun NodeSnapshot.labels(): List<String> =
        listOfNotNull(label().takeIf { it.isNotBlank() }) + children.flatMap { it.labels() }

    private fun NodeSnapshot.label() = desc ?: text ?: ""
    private fun NodeSnapshot.hasArea() = right > left && bottom > top

    /** The nearest selected node at or above a node whose label starts with [prefix]: a tab whose label is on a child. */
    private fun NodeSnapshot.selectedWith(prefix: String, under: NodeSnapshot? = null): NodeSnapshot? {
        val sel = if (selected) this else under
        if (sel != null && label().startsWith(prefix, ignoreCase = true)) return sel
        for (c in children) c.selectedWith(prefix, sel)?.let { return it }
        return null
    }

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
