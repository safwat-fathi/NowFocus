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
}
