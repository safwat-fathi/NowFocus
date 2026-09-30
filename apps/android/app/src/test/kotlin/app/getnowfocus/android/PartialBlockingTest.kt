package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialBlockingTest {

    private val yt = "com.google.android.youtube"
    private val fb = "com.facebook.katana"
    private val all = PartialRule.entries.toSet()

    private fun node(
        id: String? = null, desc: String? = null, text: String? = null, selected: Boolean = false,
        bounds: List<Int> = listOf(0, 0, 100, 100), children: List<NodeSnapshot> = emptyList(),
    ) = NodeSnapshot(id, text, desc, selected, bounds[0], bounds[1], bounds[2], bounds[3], children)

    private fun root(vararg children: NodeSnapshot) = node(children = children.toList())

    @Test
    fun `shorts player is detected`() {
        val tree = root(node(id = "$yt:id/reel_recycler"))
        assertEquals(PartialRule.YT_SHORTS, PartialBlocking.detect(yt, tree, all)?.rule)
    }

    @Test
    fun `selected Shorts tab is detected even without the player id`() {
        val tree = root(node(desc = "Shorts", selected = true))
        assertEquals(PartialRule.YT_SHORTS, PartialBlocking.detect(yt, tree, all)?.rule)
    }

    @Test
    fun `an unselected Shorts tab is not a match`() {
        val tree = root(node(desc = "Shorts", selected = false))
        assertNull(PartialBlocking.detect(yt, tree, all))
    }

    @Test
    fun `home feed match returns the feed bounds to cover`() {
        val tree = root(
            node(desc = "Home", selected = true),
            node(id = "$yt:id/results", bounds = listOf(0, 200, 1080, 1800)),
        )
        val m = PartialBlocking.detect(yt, tree, all)!!
        assertEquals(PartialRule.YT_HOME, m.rule)
        assertEquals(listOf(0, 200, 1080, 1800), listOf(m.left, m.top, m.right, m.bottom))
    }

    @Test
    fun `search results are not the home feed`() {
        // Same results list id, but the Home tab isn't the selected one.
        val tree = root(node(desc = "Subscriptions", selected = true), node(id = "$yt:id/results"))
        assertNull(PartialBlocking.detect(yt, tree, all))
    }

    @Test
    fun `related list under a playing video is detected and wins over home`() {
        val tree = root(
            node(desc = "Home", selected = true),
            node(id = "$yt:id/results"),
            node(id = "$yt:id/watch_list", bounds = listOf(0, 700, 1080, 2000)),
        )
        val m = PartialBlocking.detect(yt, tree, all)!!
        assertEquals(PartialRule.YT_RELATED, m.rule)
        assertEquals(700, m.top)
    }

    @Test
    fun `a disabled rule is not enforced`() {
        val tree = root(node(id = "$yt:id/reel_recycler"))
        assertNull(PartialBlocking.detect(yt, tree, setOf(PartialRule.FB_REELS)))
    }

    @Test
    fun `a rule only applies inside its own app`() {
        val tree = root(node(id = "$yt:id/reel_recycler"))
        assertNull(PartialBlocking.detect(fb, tree, all))
        assertNull(PartialBlocking.detect("com.other", tree, all))
    }

    @Test
    fun `selected Reels tab in facebook is detected`() {
        val tree = root(node(desc = "Reels, tab 3 of 6", selected = true))
        assertEquals(PartialRule.FB_REELS, PartialBlocking.detect(fb, tree, all)?.rule)
        assertEquals(PartialRule.FB_REELS, PartialBlocking.detect("com.facebook.lite", tree, all)?.rule)
    }

    @Test
    fun `reels carousel text in the news feed is not a match`() {
        // Backing out of the feed would exit Facebook, so only the selected tab / viewer counts.
        val tree = root(node(text = "Reels and short videos"), node(desc = "Reels", selected = false))
        assertNull(PartialBlocking.detect(fb, tree, all))
    }

    private val ig = "com.instagram.android"
    private val x = "com.twitter.android"

    @Test
    fun `selected Reels tab in instagram is detected`() {
        val tree = root(node(desc = "Reels, tab 4 of 5", selected = true))
        assertEquals(PartialRule.IG_REELS, PartialBlocking.detect(ig, tree, all)?.rule)
    }

    @Test
    fun `selected Explore tab in instagram is detected`() {
        val tree = root(node(desc = "Search and Explore", selected = true))
        assertEquals(PartialRule.IG_REELS, PartialBlocking.detect(ig, tree, all)?.rule)
    }

    @Test
    fun `the full-screen reels viewer in instagram is detected`() {
        val tree = root(node(id = "$ig:id/clips_viewer_view_pager"))
        assertEquals(PartialRule.IG_REELS, PartialBlocking.detect(ig, tree, all)?.rule)
    }

    @Test
    fun `unselected instagram tabs and feed reels text are not a match`() {
        // The bottom-bar buttons always exist; only the selected one means the user is on that surface,
        // and backing out of the feed would leave Instagram altogether.
        val tree = root(
            node(id = "$ig:id/clips_tab", desc = "Reels", selected = false),
            node(id = "$ig:id/search_tab", desc = "Search and Explore", selected = false),
            node(text = "Suggested Reels"),
        )
        assertNull(PartialBlocking.detect(ig, tree, all))
    }

    @Test
    fun `instagram rule only applies inside instagram and only when enabled`() {
        val tree = root(node(desc = "Reels", selected = true))
        assertNull(PartialBlocking.detect(x, tree, all))
        assertNull(PartialBlocking.detect(yt, tree, all))
        assertNull(PartialBlocking.detect(ig, tree, setOf(PartialRule.FB_REELS)))
    }

    @Test
    fun `x For you timeline is covered with the timeline bounds`() {
        val tree = root(
            node(desc = "For you", selected = true),
            node(id = "$x:id/timeline", bounds = listOf(0, 300, 1080, 2100)),
        )
        val m = PartialBlocking.detect(x, tree, all)!!
        assertEquals(PartialRule.X_FOR_YOU, m.rule)
        assertEquals(listOf(0, 300, 1080, 2100), listOf(m.left, m.top, m.right, m.bottom))
    }

    @Test
    fun `x Following tab and an unselected For you tab are not a match`() {
        val following = root(node(desc = "Following", selected = true), node(desc = "For you", selected = false), node(id = "$x:id/timeline"))
        assertNull(PartialBlocking.detect(x, following, all))
    }

    @Test
    fun `x is not covered without a visible timeline`() {
        val missing = root(node(desc = "For you", selected = true))
        assertNull(PartialBlocking.detect(x, missing, all))
        val invisible = root(node(desc = "For you", selected = true), node(id = "$x:id/timeline", bounds = listOf(0, 0, 0, 0)))
        assertNull(PartialBlocking.detect(x, invisible, all))
    }

    @Test
    fun `x rule only applies inside x and only when enabled`() {
        val tree = root(node(desc = "For you", selected = true), node(id = "$x:id/timeline"))
        assertNull(PartialBlocking.detect(ig, tree, all))
        assertNull(PartialBlocking.detect(x, tree, setOf(PartialRule.YT_HOME)))
    }

    @Test
    fun `empty or null tree matches nothing`() {
        assertNull(PartialBlocking.detect(yt, null, all))
        assertNull(PartialBlocking.detect(yt, root(), all))
    }

    @Test
    fun `an invisible zero-size list is not covered`() {
        val tree = root(node(id = "$yt:id/watch_list", bounds = listOf(0, 0, 0, 0)))
        assertNull(PartialBlocking.detect(yt, tree, all))
    }
}

class LeaveGateTest {
    @Test
    fun `first attempt backs out, a too-soon retry waits`() {
        val g = LeaveGate()
        assertEquals(LeaveStep.BACK, g.next(1_000))
        assertEquals(LeaveStep.WAIT, g.next(1_200))
    }

    @Test
    fun `third attempt inside the window goes home instead of another back`() {
        val g = LeaveGate()
        assertEquals(LeaveStep.BACK, g.next(1_000))
        assertEquals(LeaveStep.BACK, g.next(1_700))
        assertEquals(LeaveStep.HOME, g.next(2_400))
    }

    @Test
    fun `attempts spread past the window start over with back`() {
        val g = LeaveGate()
        assertEquals(LeaveStep.BACK, g.next(1_000))
        assertEquals(LeaveStep.BACK, g.next(1_700))
        assertEquals(LeaveStep.BACK, g.next(10_000))
    }
}

class MatchLogGateTest {
    @Test
    fun `a surface that keeps matching is logged once, not on every re-check`() {
        val g = MatchLogGate()
        assertTrue(g.shouldLog("com.google.android.youtube", PartialRule.YT_HOME))
        assertFalse(g.shouldLog("com.google.android.youtube", PartialRule.YT_HOME))
        assertFalse(g.shouldLog("com.google.android.youtube", PartialRule.YT_HOME))
    }

    @Test
    fun `moving to a different rule or app is a new attempt`() {
        val g = MatchLogGate()
        assertTrue(g.shouldLog("com.google.android.youtube", PartialRule.YT_HOME))
        assertTrue(g.shouldLog("com.google.android.youtube", PartialRule.YT_RELATED))
        assertTrue(g.shouldLog("com.facebook.katana", PartialRule.FB_REELS))
    }

    @Test
    fun `after the surface goes away the same match counts again`() {
        val g = MatchLogGate()
        assertTrue(g.shouldLog("com.google.android.youtube", PartialRule.YT_SHORTS))
        g.clear()
        assertTrue(g.shouldLog("com.google.android.youtube", PartialRule.YT_SHORTS))
    }
}
