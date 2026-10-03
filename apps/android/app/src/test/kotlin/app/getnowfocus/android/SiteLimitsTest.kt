package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SiteLimitsTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(h: Int, m: Int = 0, d: Int = 2) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val yt = AppLimit(SiteLimits.key("youtube.com"), "youtube.com", 30)
    private val music = AppLimit(SiteLimits.key("music.youtube.com"), "music.youtube.com", 60)
    private val ig = AppLimit("com.instagram.android", "Instagram", 30)

    @Test
    fun `address bar text becomes a host, search boxes and placeholders do not`() {
        assertEquals("m.youtube.com", SiteLimits.hostOf("https://m.youtube.com/watch?v=1"))
        assertEquals("youtube.com", SiteLimits.hostOf("www.youtube.com"))
        assertNull(SiteLimits.hostOf("Search or type URL"))
        assertNull(SiteLimits.hostOf("how to cook rice"))
        assertNull(SiteLimits.hostOf(null))
    }

    @Test
    fun `a subdomain counts toward the parent's limit, the longest match wins, apps are never matched`() {
        val limits = listOf(ig, yt, music)
        assertEquals(yt.packageName, SiteLimits.keyFor("m.youtube.com", limits))
        assertEquals(music.packageName, SiteLimits.keyFor("music.youtube.com", limits))
        assertNull(SiteLimits.keyFor("notyoutube.com", limits))
        assertNull(SiteLimits.keyFor("instagram.com", limits))
    }

    @Test
    fun `a stalled tick credits at most one interval`() {
        assertEquals(3_000L, SiteTimer.credit(SiteTick(yt.packageName, 1_000), 4_000, maxGap = 10_000))
        assertEquals(10_000L, SiteTimer.credit(SiteTick(yt.packageName, 1_000), 600_000, maxGap = 10_000))
        assertEquals(0L, SiteTimer.credit(SiteTick(null, 1_000), 4_000, maxGap = 10_000))
        assertEquals(0L, SiteTimer.credit(null, 4_000, maxGap = 10_000))
        assertEquals(0L, SiteTimer.credit(SiteTick(yt.packageName, 5_000), 4_000, maxGap = 10_000))
    }

    @Test
    fun `usage adds up within a day and starts again on the next`() {
        val d1 = SiteLimits.dayOf(at(9), zone)
        val d2 = SiteLimits.dayOf(at(0, d = 3), zone)
        val u = SiteUsage().plus(d1, yt.packageName, 60_000).plus(d1, yt.packageName, 30_000)
        assertEquals(90_000L, u.usedMs(yt.packageName, d1))
        assertEquals(0L, u.usedMs(yt.packageName, d2))
        assertEquals(5_000L, u.plus(d2, yt.packageName, 5_000).usedMs(yt.packageName, d2))
    }

    @Test
    fun `the day flips at local midnight`() {
        assertEquals("2026-10-02", SiteLimits.dayOf(at(23, 59), zone))
        assertEquals("2026-10-03", SiteLimits.dayOf(at(0, d = 3), zone))
    }

    @Test
    fun `usage survives a JSON round trip`() {
        val u = SiteUsage().plus("2026-10-02", yt.packageName, 1234)
        assertEquals(u, SiteUsage.fromJson(u.toJson()))
        assertEquals(SiteUsage(), SiteUsage.fromJson(SiteUsage().toJson()))
    }

    @Test
    fun `a site limit survives the shared limit JSON`() {
        assertEquals(listOf(yt), AppLimit.listFromJson(AppLimit.listToJson(listOf(yt))))
    }
}
