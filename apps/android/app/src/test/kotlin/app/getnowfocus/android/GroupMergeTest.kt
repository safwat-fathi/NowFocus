package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** A saved group is added into a profile without duplicating what is already there. */
class GroupMergeTest {
    private val group = BlockPolicy(
        name = "Social",
        domains = listOf("x.com", "reddit.com"),
        apps = listOf(AppRule("com.x", "X"), AppRule("com.reddit", "Reddit")),
    )

    @Test
    fun `a blocklist takes the group's sites and apps and skips what it has`() {
        val profile = BlockPolicy(name = "Work", domains = listOf("x.com"), apps = listOf(AppRule("com.reddit", "Reddit")))
        val merged = profile.withGroup(group)
        assertEquals(listOf("x.com", "reddit.com"), merged.domains)
        assertEquals(listOf("com.reddit", "com.x"), merged.apps.map { it.packageName })
    }

    @Test
    fun `an allowlist takes only the apps, since it has no site list`() {
        val merged = BlockPolicy(name = "Study", mode = PolicyMode.ALLOWLIST).withGroup(group)
        assertEquals(emptyList<String>(), merged.domains)
        assertEquals(2, merged.apps.size)
    }

    @Test
    fun `groups survive the policy json round trip`() {
        assertEquals(listOf(group), BlockPolicy.listFromJson(BlockPolicy.listToJson(listOf(group))))
    }
}
