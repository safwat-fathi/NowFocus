package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whitelist mode: the list is what stays open and everything else is closed. */
class AllowlistTest {
    private val now = 1_000L
    private val none: (String) -> Boolean = { false }
    private fun allow(vararg pkgs: String, end: Long = 9_000L, passes: Map<String, Long> = emptyMap(), pausedUntil: Long = 0L) =
        RuleWindow(endAt = end, domains = setOf("youtube.com"), packages = pkgs.toSet(), allowlist = true, passes = passes, pausedUntil = pausedUntil)
    private fun block(vararg pkgs: String) = RuleWindow(endAt = 9_000L, domains = emptySet(), packages = pkgs.toSet())
    private fun closing(rules: ActiveRules, pkg: String, exempt: (String) -> Boolean = none) = rules.windowsBlocking(pkg, now, exempt)

    @Test
    fun `an allowlist closes what it does not list and keeps what it does`() {
        val rules = ActiveRules(listOf(allow("com.code")))
        assertTrue(closing(rules, "com.game").isNotEmpty())
        assertTrue(closing(rules, "com.code").isEmpty())
    }

    @Test
    fun `what is exempt is never closed by an allowlist, and a blocklist ignores the exemption`() {
        val exempt = { pkg: String -> pkg == "com.launcher" }
        assertTrue(closing(ActiveRules(listOf(allow("com.code"))), "com.launcher", exempt).isEmpty())
        assertTrue(closing(ActiveRules(listOf(block("com.launcher"))), "com.launcher", exempt).isNotEmpty())
    }

    @Test
    fun `an allowlist with no app on this phone closes nothing`() {
        // Its apps may all be another platform's: closing everything would be the worst way to find out.
        assertTrue(closing(ActiveRules(listOf(allow())), "com.game").isEmpty())
    }

    @Test
    fun `an expired, paused or passed allowlist closes nothing`() {
        assertTrue(closing(ActiveRules(listOf(allow("com.code", end = now))), "com.game").isEmpty())
        assertTrue(closing(ActiveRules(listOf(allow("com.code", pausedUntil = 5_000L))), "com.game").isEmpty()) // cheat day
        assertTrue(closing(ActiveRules(listOf(allow("com.code", passes = mapOf("com.game" to 5_000L)))), "com.game").isEmpty())
        assertTrue(closing(ActiveRules(listOf(allow("com.code", passes = mapOf("com.game" to 5_000L)))), "com.other").isNotEmpty())
    }

    @Test
    fun `an allowlist never filters sites`() {
        val rules = ActiveRules(listOf(allow("com.code")))
        assertTrue(rules.liveDomains(now).isEmpty())
        assertNull(rules.reasonForDomain("youtube.com", now))
        assertTrue(rules.livePackages(now).isEmpty())
    }

    @Test
    fun `the Commitment Shield still closes an app the allowlist lists, and says so`() {
        val shield = RuleWindow(endAt = 9_000L, domains = emptySet(), packages = setOf("com.code"), source = BlockSource.COMMITMENT_SHIELD)
        val rules = ActiveRules(listOf(allow("com.code"), shield))
        val matching = closing(rules, "com.code")
        assertEquals(listOf(BlockSource.COMMITMENT_SHIELD), matching.map { it.source })
        assertEquals(BlockReason.COMMITMENT_SHIELD, matching.single().reason)
    }

    @Test
    fun `an allowlist window is worded as its own cause, bedtime first`() {
        assertEquals(BlockReason.ALLOWLIST_SESSION, allow("com.code").reason)
        assertEquals(BlockReason.BEDTIME, allow("com.code").copy(bedtime = true).reason)
        assertEquals(uiText(R.string.block_reason_allowlist, "3:45 PM"), BlockCopy.reason(BlockReason.ALLOWLIST_SESSION, "3:45 PM", "Game"))
        assertEquals(BlockCopy.timeLabel(BlockReason.FOCUS_SESSION), BlockCopy.timeLabel(BlockReason.ALLOWLIST_SESSION))
    }

    @Test
    fun `only a package with a launcher icon, or an essential one, is exempt`() {
        val essential = setOf("com.launcher", "com.dialer")
        val icons = setOf("com.game", "com.launcher", "com.dialer")
        assertFalse(Essentials.isExempt("com.game", essential) { it in icons })
        assertTrue(Essentials.isExempt("com.launcher", essential) { it in icons })
        assertTrue("a helper window has no icon", Essentials.isExempt("com.google.android.gms", essential) { it in icons })
        assertTrue("a package this phone can't see is not closed", Essentials.isExempt("com.hidden", essential) { false })
        assertTrue(Essentials.FIXED.all { Essentials.isExempt(it, Essentials.FIXED) { true } })
        assertTrue("com.android.settings" in Essentials.FIXED)
    }

    private fun session(mode: PolicyMode, vararg pkgs: String) = FocusSession(
        id = "s", policyId = "p", startAt = 0, endAt = 1_000_000, status = FocusSessionStatus.ACTIVE, createdAt = 0,
        packages = pkgs.toSet(), policyMode = mode,
    )

    @Test
    fun `a pass reopens an app the session closes, whichever way the list reads`() {
        assertNotNull(Passes.grant(session(PolicyMode.ALLOWLIST, "com.code"), "com.game", now))
        assertNull("an allowed app is not closed, so there is nothing to pass", Passes.grant(session(PolicyMode.ALLOWLIST, "com.code"), "com.code", now))
        assertNotNull(Passes.grant(session(PolicyMode.BLOCKLIST, "com.game"), "com.game", now))
        assertNull(Passes.grant(session(PolicyMode.BLOCKLIST, "com.game"), "com.other", now))
    }

    @Test
    fun `a policy holds its mode, and one saved before whitelist mode is a blocklist`() {
        val p = BlockPolicy(name = "Only these", apps = listOf(AppRule("com.code", "Code")), mode = PolicyMode.ALLOWLIST)
        assertEquals(PolicyMode.ALLOWLIST, BlockPolicy.listFromJson(BlockPolicy.listToJson(listOf(p))).single().mode)
        val old = """[{"id":"1","name":"Old","domains":[],"apps":[]}]"""
        assertEquals(PolicyMode.BLOCKLIST, BlockPolicy.listFromJson(old).single().mode)
    }

    @Test
    fun `a whitelist with no app on this phone cannot be enforced here`() {
        assertFalse(BlockPolicy(name = "x", mode = PolicyMode.ALLOWLIST).enforcesHere)
        assertTrue(BlockPolicy(name = "x", mode = PolicyMode.ALLOWLIST, apps = listOf(AppRule("a", "A"))).enforcesHere)
        assertTrue("an empty blocklist is allowed to start, as before", BlockPolicy(name = "x").enforcesHere)
    }

    @Test
    fun `a session takes its rules and their meaning from the policy together`() {
        val p = BlockPolicy(name = "x", domains = listOf("a.com"), apps = listOf(AppRule("com.code", "Code")), mode = PolicyMode.ALLOWLIST)
        val s = session(PolicyMode.BLOCKLIST).withPolicy(p)
        assertEquals(PolicyMode.ALLOWLIST, s.policyMode)
        assertEquals(setOf("com.code"), s.packages)
        assertEquals(p.id, s.policyId)
    }
}
