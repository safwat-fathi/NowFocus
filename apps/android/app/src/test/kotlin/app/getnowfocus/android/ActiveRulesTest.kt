package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual session and the Commitment Shield can both be active at once, each
 * with its own endAt - this is what makes that safe: nothing here trusts a
 * single scalar endAt, everything re-filters by [now] on every call.
 */
class ActiveRulesTest {

    @Test
    fun `no windows means nothing is blocked and nothing is scheduled`() {
        val rules = ActiveRules(emptyList())
        assertTrue(rules.liveDomains(now = 1000).isEmpty())
        assertTrue(rules.livePackages(now = 1000).isEmpty())
        assertNull(rules.nextExpiryAfter(now = 1000))
        assertFalse(rules.hasLiveWindow(now = 1000))
    }

    @Test
    fun `a single live window contributes its own domains and packages`() {
        val rules = ActiveRules(listOf(RuleWindow(endAt = 2000, domains = setOf("x.com"), packages = setOf("com.x"))))
        assertEquals(setOf("x.com"), rules.liveDomains(now = 1000))
        assertEquals(setOf("com.x"), rules.livePackages(now = 1000))
        assertEquals(2000L, rules.nextExpiryAfter(now = 1000))
        assertTrue(rules.hasLiveWindow(now = 1000))
    }

    @Test
    fun `a window whose endAt has passed contributes nothing`() {
        val rules = ActiveRules(listOf(RuleWindow(endAt = 1000, domains = setOf("x.com"), packages = setOf("com.x"))))
        assertTrue(rules.liveDomains(now = 1000).isEmpty())
        assertNull(rules.nextExpiryAfter(now = 1000))
        assertFalse(rules.hasLiveWindow(now = 1000))
    }

    @Test
    fun `two live windows union their domains and packages`() {
        val rules = ActiveRules(
            listOf(
                RuleWindow(endAt = 5000, domains = setOf("x.com"), packages = setOf("com.x")),
                RuleWindow(endAt = 9000, domains = setOf("y.com"), packages = setOf("com.y")),
            ),
        )
        assertEquals(setOf("x.com", "y.com"), rules.liveDomains(now = 1000))
        assertEquals(setOf("com.x", "com.y"), rules.livePackages(now = 1000))
    }

    @Test
    fun `nextExpiryAfter is the soonest live window, so the VPN wakes at the right time`() {
        val rules = ActiveRules(
            listOf(
                RuleWindow(endAt = 9000, domains = setOf("y.com"), packages = emptySet()),
                RuleWindow(endAt = 5000, domains = setOf("x.com"), packages = emptySet()),
            ),
        )
        assertEquals(5000L, rules.nextExpiryAfter(now = 1000))
    }

    @Test
    fun `one window expiring does not affect a still-live window - the exact commitment-shield bug`() {
        // A short manual session (endAt 2000) plus a 14-day Commitment Shield (endAt far out).
        val rules = ActiveRules(
            listOf(
                RuleWindow(endAt = 2000, domains = setOf("session-only.com"), packages = emptySet()),
                RuleWindow(endAt = 999_999_999, domains = setOf("shield.com"), packages = emptySet()),
            ),
        )
        // Session has expired; the shield must still be live.
        assertEquals(setOf("shield.com"), rules.liveDomains(now = 3000))
        assertTrue(rules.hasLiveWindow(now = 3000))
        assertEquals(999_999_999L, rules.nextExpiryAfter(now = 3000))
    }

    @Test
    fun `livePartial unions live windows and drops expired ones`() {
        val rules = ActiveRules(
            listOf(
                RuleWindow(endAt = 2000, domains = emptySet(), packages = emptySet(), partial = setOf(PartialRule.YT_SHORTS)),
                RuleWindow(endAt = 9000, domains = emptySet(), packages = emptySet(), partial = setOf(PartialRule.FB_REELS)),
            ),
        )
        assertEquals(setOf(PartialRule.YT_SHORTS, PartialRule.FB_REELS), rules.livePartial(now = 1000))
        assertEquals(setOf(PartialRule.FB_REELS), rules.livePartial(now = 3000))
        assertTrue(rules.livePartial(now = 9000).isEmpty())
    }

    @Test
    fun `the shield keeps its partial rules after the session window expires`() {
        val rules = ActiveRules(
            listOf(
                RuleWindow(endAt = 2000, domains = emptySet(), packages = emptySet(), partial = setOf(PartialRule.YT_HOME)),
                RuleWindow(endAt = 999_999_999, domains = emptySet(), packages = emptySet(), source = BlockSource.COMMITMENT_SHIELD, partial = setOf(PartialRule.FB_REELS)),
            ),
        )
        assertEquals(setOf(PartialRule.FB_REELS), rules.livePartial(now = 3000))
    }
}
