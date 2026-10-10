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

    @Test
    fun `a paused window blocks nothing inside the pause but is still live`() {
        val rules = ActiveRules(listOf(RuleWindow(endAt = 9000, domains = setOf("x.com"), packages = setOf("com.x"), pausedFrom = 2000, pausedUntil = 4000)))
        assertEquals(setOf("x.com"), rules.liveDomains(now = 1000))
        assertTrue(rules.liveDomains(now = 3000).isEmpty())
        assertTrue(rules.livePackages(now = 3000).isEmpty())
        assertTrue(rules.windowsBlocking("com.x", now = 3000).isEmpty())
        assertTrue(rules.hasLiveWindow(now = 3000))
        assertEquals(setOf("x.com"), rules.liveDomains(now = 4000))
    }

    @Test
    fun `the services wake at the start and end of a pause, not just at the window end`() {
        val rules = ActiveRules(listOf(RuleWindow(endAt = 9000, domains = setOf("x.com"), packages = emptySet(), pausedFrom = 2000, pausedUntil = 4000)))
        assertEquals(2000L, rules.nextExpiryAfter(now = 1000))
        assertEquals(4000L, rules.nextExpiryAfter(now = 3000))
        assertEquals(9000L, rules.nextExpiryAfter(now = 4000))
    }

    @Test
    fun `a passed app is not blocked until its pass ends, the others still are`() {
        val rules = ActiveRules(listOf(RuleWindow(endAt = 9000, domains = emptySet(), packages = setOf("com.chat", "com.video"), passes = mapOf("com.chat" to 5000L))))
        assertEquals(setOf("com.video"), rules.livePackages(now = 1000))
        assertTrue(rules.windowsBlocking("com.chat", now = 1000).isEmpty())
        assertEquals(1, rules.windowsBlocking("com.video", now = 1000).size)
        assertEquals(5000L, rules.nextExpiryAfter(now = 1000))
        assertEquals(setOf("com.chat", "com.video"), rules.livePackages(now = 5000))
    }

    @Test
    fun `a pause on the session window leaves the Shield window blocking`() {
        val rules = ActiveRules(listOf(
            RuleWindow(endAt = 9000, domains = setOf("x.com"), packages = setOf("com.x"), pausedFrom = 0, pausedUntil = 9000),
            RuleWindow(endAt = 9000, domains = setOf("adult.com"), packages = setOf("com.adult"), source = BlockSource.COMMITMENT_SHIELD),
        ))
        assertEquals(setOf("adult.com"), rules.liveDomains(now = 1000))
        assertEquals(setOf("com.adult"), rules.livePackages(now = 1000))
    }

    @Test
    fun `windowBlockingDomain prefers the Shield and matches subdomains only`() {
        val session = RuleWindow(endAt = 2000, domains = setOf("x.com"), packages = emptySet())
        val shield = RuleWindow(endAt = 3000, domains = setOf("x.com"), packages = emptySet(), source = BlockSource.COMMITMENT_SHIELD)
        val rules = ActiveRules(listOf(session, shield))
        assertEquals(BlockSource.COMMITMENT_SHIELD, rules.windowBlockingDomain("m.x.com", now = 1000)?.source)
        assertNull(rules.windowBlockingDomain("notx.com", now = 1000))
        assertNull(rules.windowBlockingDomain("x.com", now = 3000))
    }
}
