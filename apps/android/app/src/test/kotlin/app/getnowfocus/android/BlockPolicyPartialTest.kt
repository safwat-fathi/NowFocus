package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockPolicyPartialTest {
    @Test
    fun `partial names round trip and unknown names from a newer build are dropped`() {
        assertEquals(
            setOf(PartialRule.YT_SHORTS, PartialRule.FB_REELS),
            BlockPolicy.partialFromNames(listOf("YT_SHORTS", "FB_REELS", "SOMETHING_NEW")),
        )
        assertEquals(emptySet<PartialRule>(), BlockPolicy.partialFromNames(emptyList()))
    }

    @Test
    fun `the six rule names and their order are the cross-platform contract`() {
        // macOS and Windows list the same six rows with the same identifiers; change all three together.
        val names = listOf("YT_SHORTS", "YT_HOME", "YT_RELATED", "FB_REELS", "IG_REELS", "X_FOR_YOU")
        assertEquals(names, PartialRule.entries.map { it.name })
        assertEquals(PartialRule.entries.toSet(), BlockPolicy.partialFromNames(names))
    }
}
