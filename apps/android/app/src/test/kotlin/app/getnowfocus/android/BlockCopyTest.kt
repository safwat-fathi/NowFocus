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
        assertEquals("NowFocus closed YouTube", BlockCopy.title("YouTube"))
        assertEquals("NowFocus closed this app", BlockCopy.title(null))
    }

    @Test
    fun `each cause is named with its time`() {
        assertEquals("You're in a focus session until 3:45 PM.", BlockCopy.reason(BlockReason.FOCUS_SESSION, "3:45 PM", "YouTube"))
        assertEquals("It's bedtime wind-down until 6:00 AM.", BlockCopy.reason(BlockReason.BEDTIME, "6:00 AM", "YouTube"))
        assertEquals("Locked by your Commitment Shield until Oct 9.", BlockCopy.reason(BlockReason.COMMITMENT_SHIELD, "Oct 9", "YouTube"))
        assertEquals(
            "You've used your 30 minutes of YouTube today. It's back at midnight.",
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
        BlockReason.values().forEach { r ->
            val text = BlockCopy.siteNotice(r)
            assertTrue(text, text.endsWith("It will load again when it ends.") || r == BlockReason.DAILY_LIMIT)
        }
        assertTrue(BlockCopy.siteNotice(BlockReason.BEDTIME).contains("Bedtime"))
        assertTrue(BlockCopy.siteNotice(BlockReason.COMMITMENT_SHIELD).contains("Commitment Shield"))
    }
}
