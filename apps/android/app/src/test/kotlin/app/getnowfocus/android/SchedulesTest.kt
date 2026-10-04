package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulesTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val work = Schedule(id = "w", name = "Work", days = weekdays, startMinute = 9 * 60, endMinute = 12 * 60, policyId = "p")
    // 2026-10-05 is a Monday, 2026-10-03 a Saturday.

    @Test
    fun `inside a weekday window it is current, with that window's bounds`() {
        val occ = Schedules.current(listOf(work), at(2026, 10, 5, 10), zone)!!
        assertEquals(at(2026, 10, 5, 9), occ.start)
        assertEquals(at(2026, 10, 5, 12), occ.end)
    }

    @Test
    fun `outside the hours or on a weekend nothing is current`() {
        assertNull(Schedules.current(listOf(work), at(2026, 10, 5, 8, 59), zone))
        assertNull(Schedules.current(listOf(work), at(2026, 10, 5, 12), zone))
        assertNull(Schedules.current(listOf(work), at(2026, 10, 3, 10), zone))
    }

    @Test
    fun `a disabled schedule never runs`() {
        assertNull(Schedules.current(listOf(work.copy(enabled = false)), at(2026, 10, 5, 10), zone))
        assertNull(Schedules.nextStart(listOf(work.copy(enabled = false)), at(2026, 10, 5, 10), zone))
    }

    @Test
    fun `a window that crosses midnight is still current after midnight, on the day it started`() {
        val night = work.copy(days = setOf(DayOfWeek.FRIDAY), startMinute = 22 * 60, endMinute = 2 * 60) // Fri 22:00 to Sat 02:00
        assertNotNull(Schedules.current(listOf(night), at(2026, 10, 10, 1), zone)) // Saturday 01:00
        assertNull(Schedules.current(listOf(night), at(2026, 10, 11, 1), zone))    // Sunday 01:00
    }

    @Test
    fun `next start skips to the next allowed day`() {
        assertEquals(at(2026, 10, 5, 9), Schedules.nextStart(listOf(work), at(2026, 10, 3, 10), zone)) // Saturday -> Monday
        assertEquals(at(2026, 10, 6, 9), Schedules.nextStart(listOf(work), at(2026, 10, 5, 9), zone))   // at the start: the next one
    }

    @Test
    fun `the longest-lasting of two overlapping windows wins`() {
        val long = work.copy(id = "l", endMinute = 15 * 60)
        assertEquals("l", Schedules.current(listOf(work, long), at(2026, 10, 5, 10), zone)!!.schedule.id)
    }

    @Test
    fun `day labels`() {
        assertEquals(uiText(R.string.days_range, DayOfWeek.MONDAY.shortName(), DayOfWeek.FRIDAY.shortName()), Schedules.daysLabel(weekdays))
        assertEquals(uiText(R.string.days_every_day), Schedules.daysLabel(DayOfWeek.entries.toSet()))
        assertEquals(
            UiText.Joined(listOf(DayOfWeek.SATURDAY.shortName(), DayOfWeek.SUNDAY.shortName()), R.string.sep_comma),
            Schedules.daysLabel(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
        )
        assertEquals(
            UiText.Joined(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).map { it.shortName() }, R.string.sep_comma),
            Schedules.daysLabel(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
        )
    }

    @Test
    fun `schedules and runs survive json`() {
        val list = listOf(work, work.copy(id = "x", mode = EnforcementMode.LOCKED, enabled = false))
        assertEquals(list, Schedule.listFromJson(Schedule.listToJson(list)))
        assertEquals(mapOf("w" to 5L), Schedule.runsFromJson(Schedule.runsToJson(mapOf("w" to 5L))))
    }
}
