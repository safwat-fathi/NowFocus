package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LimitPassesTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(h: Int, m: Int = 0, d: Int = 2) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun day(d: Int = 2) = SiteLimits.dayOf(at(12, d = d), zone)
    private val none = LimitPassState()

    @Test
    fun `a pass lasts five minutes and is one of two`() {
        val s = LimitPasses.grant(none, "ig", at(10), day())!!
        assertEquals(at(10, 5), LimitPasses.activeUntil(s, "ig", at(10, 1), day()))
        assertEquals(0L, LimitPasses.activeUntil(s, "ig", at(10, 5), day()))
        assertEquals(1, LimitPasses.passesLeft(s, "ig", day()))
    }

    @Test
    fun `none while one is open, a second after it ends, never a third`() {
        val first = LimitPasses.grant(none, "ig", at(10), day())!!
        assertNull(LimitPasses.grant(first, "ig", at(10, 2), day()))
        val second = LimitPasses.grant(first, "ig", at(10, 6), day())!!
        assertEquals(0, LimitPasses.passesLeft(second, "ig", day()))
        assertNull(LimitPasses.grant(second, "ig", at(11), day()))
    }

    @Test
    fun `each limit has its own allowance and midnight starts it again`() {
        val s = LimitPasses.grant(none, "ig", at(10), day())!!
        assertEquals(2, LimitPasses.passesLeft(s, "site:youtube.com", day()))
        assertEquals(2, LimitPasses.passesLeft(s, "ig", day(3)))
        assertEquals(0L, LimitPasses.activeUntil(s, "ig", at(0, 1, d = 3), day(3)))
        assertEquals(1, LimitPasses.passesLeft(LimitPasses.grant(s, "ig", at(0, 1, d = 3), day(3))!!, "ig", day(3)) + 0)
    }

    @Test
    fun `a pass is five minutes plus one per streak day, up to fifteen`() {
        assertEquals(listOf(5, 10, 15, 15, 5), listOf(0, 5, 10, 20, -1).map(LimitPasses::passMinutes))
    }

    @Test
    fun `a longer pass lasts as long as it was given`() {
        val s = LimitPasses.grant(none, "ig", at(10), day(), minutes = 12)!!
        assertEquals(at(10, 12), LimitPasses.activeUntil(s, "ig", at(10, 11), day()))
        assertEquals(0L, LimitPasses.activeUntil(s, "ig", at(10, 12), day()))
    }

    @Test
    fun `passes survive a JSON round trip`() {
        val s = LimitPasses.grant(none, "site:youtube.com", at(10), day())!!
        assertEquals(s, LimitPasses.fromJson(LimitPasses.toJson(s)))
        assertEquals(none, LimitPasses.fromJson(LimitPasses.toJson(none)))
    }
}
