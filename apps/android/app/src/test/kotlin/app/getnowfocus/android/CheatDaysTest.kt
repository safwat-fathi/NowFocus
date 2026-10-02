package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CheatDaysTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `tomorrow is too soon unless it is a full day away`() {
        val now = at(2026, 10, 2, 10)
        assertFalse(CheatDays.canSchedule(now, at(2026, 10, 3), null, zone))   // starts in 14h
        assertTrue(CheatDays.canSchedule(now, at(2026, 10, 4), null, zone))    // starts in 38h
    }

    @Test
    fun `exactly 24 hours ahead is allowed`() {
        val now = at(2026, 10, 2, 0)
        assertTrue(CheatDays.canSchedule(now, at(2026, 10, 3), null, zone))
    }

    @Test
    fun `one a week, so a day within seven days of the last is refused in both directions`() {
        val now = at(2026, 10, 1, 10)
        val existing = CheatDays.forDay(at(2026, 10, 10), now, zone)
        assertFalse(CheatDays.canSchedule(now, at(2026, 10, 16), existing, zone)) // 6 days after
        assertTrue(CheatDays.canSchedule(now, at(2026, 10, 17), existing, zone))  // 7 days after
        assertFalse(CheatDays.canSchedule(now, at(2026, 10, 5), existing, zone))  // 5 days before
        assertTrue(CheatDays.canSchedule(now, at(2026, 10, 3), existing, zone))   // 7 days before
    }

    @Test
    fun `options are soonest first and all legal`() {
        val now = at(2026, 10, 2, 10)
        val existing = CheatDays.forDay(at(2026, 9, 30), now, zone)
        val options = CheatDays.options(now, zone, existing)
        assertEquals(at(2026, 10, 7), options.first())
        assertTrue(options.all { CheatDays.canSchedule(now, it, existing, zone) })
        assertEquals(options.sorted(), options)
    }

    @Test
    fun `a cheat day runs from midnight to the next midnight`() {
        val cheat = CheatDays.forDay(at(2026, 10, 10), now = 0, zone = zone)
        assertEquals(at(2026, 10, 11), cheat.endAt)
        assertTrue(cheat.isActive(at(2026, 10, 10, 12)))
        assertFalse(cheat.isActive(at(2026, 10, 9, 23)))
        assertFalse(cheat.isActive(at(2026, 10, 11)))
    }

    @Test
    fun `cancelling before it starts frees the week, ending a live one keeps it counted`() {
        val cheat = CheatDays.forDay(at(2026, 10, 10), now = 0, zone = zone)
        assertNull(CheatDays.cancel(cheat, at(2026, 10, 9)))
        val ended = CheatDays.cancel(cheat, at(2026, 10, 10, 12))!!
        assertEquals(at(2026, 10, 10, 12), ended.endAt)
        assertEquals(cheat.startAt, ended.startAt)
        assertEquals(cheat, CheatDays.cancel(cheat, at(2026, 10, 12)))
    }
}
