package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockCopyTest {
    private val now = 1_000L
    private fun window(source: BlockSource = BlockSource.SESSION, bedtime: Boolean = false, vararg domains: String) =
        RuleWindow(endAt = 9_000L, domains = domains.toSet(), packages = setOf("com.x"), source = source, bedtime = bedtime)

    @Test
    fun `the title always says NowFocus closed the app`() {
        assertEquals(uiText(R.string.block_title, "YouTube"), BlockCopy.title("YouTube"))
        assertEquals(uiText(R.string.block_title, uiText(R.string.block_this_app)), BlockCopy.title(null))
    }

    @Test
    fun `each cause is named with its time`() {
        assertEquals(uiText(R.string.block_reason_session, "3:45 PM"), BlockCopy.reason(BlockReason.FOCUS_SESSION, "3:45 PM", "YouTube"))
        assertEquals(uiText(R.string.block_reason_bedtime, "6:00 AM"), BlockCopy.reason(BlockReason.BEDTIME, "6:00 AM", "YouTube"))
        assertEquals(uiText(R.string.block_reason_shield, "Oct 9"), BlockCopy.reason(BlockReason.COMMITMENT_SHIELD, "Oct 9", "YouTube"))
        // The limit sentence is a plural on the minutes, with the app name second.
        assertEquals(
            UiText.Plural(R.plurals.block_reason_limit, 30, listOf(30, "YouTube")),
            BlockCopy.reason(BlockReason.DAILY_LIMIT, "ignored", "YouTube", limitMinutes = 30),
        )
    }

    @Test
    fun `a window's cause is bedtime, shield or a plain session`() {
        assertEquals(BlockReason.FOCUS_SESSION, window().reason)
        assertEquals(BlockReason.BEDTIME, window(bedtime = true).reason)
        assertEquals(BlockReason.COMMITMENT_SHIELD, window(source = BlockSource.COMMITMENT_SHIELD).reason)
    }

    @Test
    fun `a blocked site is explained by the window that blocks it, shield first`() {
        val rules = ActiveRules(listOf(
            window(domains = arrayOf("youtube.com", "reddit.com")),
            window(source = BlockSource.COMMITMENT_SHIELD, domains = arrayOf("youtube.com")),
        ))
        assertEquals(BlockReason.COMMITMENT_SHIELD, rules.reasonForDomain("m.youtube.com", now))
        assertEquals(BlockReason.FOCUS_SESSION, rules.reasonForDomain("reddit.com", now))
        assertNull(rules.reasonForDomain("example.com", now))
    }

    @Test
    fun `the site notice names the cause but never the site`() {
        // One fixed sentence per cause and no args, so there is nothing to put a site name into.
        assertEquals(uiText(R.string.block_site_session), BlockCopy.siteNotice(BlockReason.FOCUS_SESSION))
        assertEquals(uiText(R.string.block_site_bedtime), BlockCopy.siteNotice(BlockReason.BEDTIME))
        assertEquals(uiText(R.string.block_site_shield), BlockCopy.siteNotice(BlockReason.COMMITMENT_SHIELD))
        assertEquals(uiText(R.string.block_site_limit), BlockCopy.siteNotice(BlockReason.DAILY_LIMIT))
        BlockReason.values().forEach { r -> assertTrue((BlockCopy.siteNotice(r) as UiText.Res).args.isEmpty()) }
    }
}
