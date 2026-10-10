package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    private val adguard = DnsChoice(DnsProvider.ADGUARD_FAMILY)
    private val always = adguard.copy(alwaysOn = true)
    private val none = UpstreamHealth()

    private fun status(
        choice: DnsChoice,
        sessionLive: Boolean = false,
        vpnPermitted: Boolean = true,
        strict: String? = null,
        tunnelUp: Boolean = true,
        upstream: UpstreamHealth = none,
        now: Long = 100_000L,
    ) = DnsPolicy.status(choice, sessionLive, vpnPermitted, strict, tunnelUp, upstream, now)

    @Test
    fun `the tunnel is kept for a live window, or for always-on with a real pick, and otherwise closes`() {
        assertFalse(DnsPolicy.shouldKeepUp(adguard, hasLiveWindow = false))
        assertTrue(DnsPolicy.shouldKeepUp(adguard, hasLiveWindow = true))
        assertTrue(DnsPolicy.shouldKeepUp(always, hasLiveWindow = false))
        assertFalse("always-on with the system DNS has nothing to keep", DnsPolicy.shouldKeepUp(DnsChoice(alwaysOn = true), hasLiveWindow = false))
        assertFalse("a custom pick without a host is the system DNS", DnsPolicy.shouldKeepUp(DnsChoice(DnsProvider.CUSTOM, customHost = "", alwaysOn = true), hasLiveWindow = false))
        assertTrue("a session still needs the tunnel to block sites", DnsPolicy.shouldKeepUp(DnsChoice(), hasLiveWindow = true))
    }

    @Test
    fun `off, waiting and active`() {
        assertEquals(DnsStatus.Off, status(DnsChoice(), sessionLive = true))
        assertEquals(DnsStatus.WaitingForSession, status(adguard))
        assertEquals(DnsStatus.Active, status(adguard, sessionLive = true))
        assertEquals(DnsStatus.Active, status(always))
    }

    @Test
    fun `permission and Private DNS conflicts outrank a tunnel that is not up`() {
        assertEquals(DnsStatus.NeedsPermission, status(always, vpnPermitted = false, tunnelUp = false))
        assertEquals(DnsStatus.StrictConflict("dns.example.com"), status(always, strict = "dns.example.com", tunnelUp = false))
        assertEquals(DnsStatus.NeedsPermission, status(always, vpnPermitted = false, strict = "dns.example.com"))
        assertEquals(DnsStatus.Starting, status(always, tunnelUp = false))
    }

    @Test
    fun `a server that stopped answering is flagged for a minute, until it answers again`() {
        val failed = UpstreamHealth(okAt = 10_000, failAt = 95_000)
        assertEquals(DnsStatus.Unreachable, status(always, upstream = failed))
        assertEquals(DnsStatus.Active, status(always, upstream = failed, now = 95_000 + DnsPolicy.UNREACHABLE_WINDOW_MS))
        assertEquals(DnsStatus.Active, status(always, upstream = UpstreamHealth(okAt = 96_000, failAt = 95_000)))
    }
}
