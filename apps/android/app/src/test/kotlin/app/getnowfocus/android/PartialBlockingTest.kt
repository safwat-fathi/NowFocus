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

    // X 12.32 on a real phone: a Compose screen. The feed has no id; the selected tab is a node with no label
    // whose child says "For you"; the bottom bar's selected entry is a node at the left edge.
    private fun xHome(forYouSelected: Boolean = true, tabRow: Boolean = true) = root(
        node(id = "MainLanding", bounds = listOf(0, 0, 1080, 2400), children = listOf(
            node(id = "scaffold_home_tabbed", bounds = listOf(0, 0, 1080, 2400), children = buildList {
                if (tabRow) {
                    add(node(selected = forYouSelected, bounds = listOf(22, 249, 400, 375), children = listOf(node(desc = "For you"))))
                    add(node(selected = !forYouSelected, bounds = listOf(400, 249, 760, 375), children = listOf(node(desc = "Following"))))
                    add(node(selected = true, bounds = listOf(0, 2127, 200, 2274), children = listOf(node(desc = "Home"))))
                }
            }),
        )),
    )

    @Test
    fun `x For you feed is covered between the tab row and the bottom bar`() {
        val m = PartialBlocking.detect(x, xHome(), all)!!
        assertEquals(PartialRule.X_FOR_YOU, m.rule)
        assertEquals(listOf(0, 375, 1080, 2127), listOf(m.left, m.top, m.right, m.bottom))
        assertFalse(m.passThrough)
    }

    @Test
    fun `x Following tab is not a match`() {
        assertNull(PartialBlocking.detect(x, xHome(forYouSelected = false), all))
    }

    @Test
    fun `x feed stays covered after the tab row scrolls away, and touches pass through`() {
        val mem = FeedMemory()
        PartialBlocking.detect(x, xHome(), all, mem)
        val m = PartialBlocking.detect(x, xHome(tabRow = false), all, mem)!!
        assertTrue(m.passThrough)
        assertEquals(90, m.top)
    }

    @Test
    fun `x feed scrolled away on Following stays open`() {
        val mem = FeedMemory()
        PartialBlocking.detect(x, xHome(forYouSelected = false), all, mem)
        assertNull(PartialBlocking.detect(x, xHome(tabRow = false), all, mem))
    }

    @Test
    fun `x screens other than home are not covered`() {
        assertNull(PartialBlocking.detect(x, root(node(selected = true, children = listOf(node(desc = "For you")))), all))
    }

    @Test
    fun `x rule only applies inside x and only when enabled`() {
        assertNull(PartialBlocking.detect(ig, xHome(), all))
        assertNull(PartialBlocking.detect(x, xHome(), setOf(PartialRule.YT_HOME)))
    }

    private val tt = "com.zhiliaoapp.musically.go"

    @Test
    fun `tiktok main feed is covered from the status bar, top tabs included, down to the bottom bar`() {
        val feed = root(
            node(desc = "Following", bounds = listOf(338, 78, 569, 225)),
            node(desc = "For You", bounds = listOf(569, 78, 763, 225)),
            node(desc = "Home", bounds = listOf(0, 2229, 216, 2263)),
        )
        val m = PartialBlocking.detect(tt, feed, all)!!
        assertEquals(PartialRule.TT_FOR_YOU, m.rule)
        assertEquals(listOf(86, 2146), listOf(m.top, m.bottom))
        assertEquals(PartialRule.TT_FOR_YOU, PartialBlocking.detect("com.zhiliaoapp.musically", feed, all)!!.rule)
    }

    private val ttNav = listOf(
        node(desc = "Home", bounds = listOf(0, 2229, 216, 2263)),
        node(desc = "Explore", bounds = listOf(216, 2229, 432, 2263)),
        node(desc = "Creative home plus", bounds = listOf(432, 2146, 648, 2274)),
        node(desc = "Inbox", bounds = listOf(648, 2229, 864, 2263)),
        node(desc = "Me", bounds = listOf(864, 2229, 1080, 2263)),
    )

    @Test
    fun `tiktok Explore, which exposes nothing but the bottom bar, is covered`() {
        val m = PartialBlocking.detect(tt, root(*ttNav.toTypedArray()), all)!!
        assertEquals(PartialRule.TT_FOR_YOU, m.rule)
        assertEquals(PartialAction.COVER, m.action)
        assertEquals(listOf(86, 2146), listOf(m.top, m.bottom))
    }

    @Test
    fun `tiktok Inbox and Me label their own content and are left alone`() {
        val inbox = root(node(desc = "Inbox", bounds = listOf(0, 86, 1080, 225)), node(desc = "Share videos with friends"), *ttNav.toTypedArray())
        assertNull(PartialBlocking.detect(tt, inbox, all))
    }

    @Test
    fun `tiktok video player without the bottom bar is left`() {
        val player = root(
            node(id = "$tt:id/gesture_deal_view", bounds = listOf(0, 86, 1080, 2145)),
            node(text = "Add comment...", bounds = listOf(64, 2179, 701, 2253)),
        )
        val m = PartialBlocking.detect(tt, player, all)!!
        assertEquals(PartialAction.LEAVE, m.action)
        assertNull(PartialBlocking.detect(tt, root(node(id = "$tt:id/gesture_deal_view", bounds = listOf(0, 2274, 1080, 2274))), all))
    }

    @Test
    fun `tiktok interest picker with only a For You tab is not covered`() {
        assertNull(PartialBlocking.detect(tt, root(node(desc = "For You", bounds = listOf(443, 86, 637, 233)), node(desc = "Beauty & style")), all))
        assertNull(PartialBlocking.detect(tt, root(*ttNav.toTypedArray()), setOf(PartialRule.X_FOR_YOU)))
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
