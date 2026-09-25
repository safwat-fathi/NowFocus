package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bedtime windows frequently cross midnight (wind-down 22:00 -> wake 07:00
 * next day). native_tech_stack_spec.md is explicit that this needs absolute
 * timestamps, not minutes-since-midnight comparisons - these tests exist to
 * prove that boundary, not just the happy path.
 */
class BedtimeScheduleTest {

    private val zone = ZoneOffset.UTC
    private val settings = BedtimeSettings(windDownMinute = 22 * 60, sleepMinute = 23 * 60, wakeMinute = 7 * 60)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).toInstant(zone).toEpochMilli()

    @Test
    fun `windowFor spans from wind-down tonight to wake the next morning`() {
        val day = LocalDate.of(2026, 9, 21)
        val window = BedtimeSchedule.windowFor(settings, day, zone)
        assertEquals(at(day, 22), window.first)
        assertEquals(at(day.plusDays(1), 7), window.last + 1) // LongRange.last is inclusive of the final millisecond
    }

    @Test
    fun `isQuietTimeNow is true late at night, within tonight's window`() {
        val day = LocalDate.of(2026, 9, 21)
        assertTrue(BedtimeSchedule.isQuietTimeNow(settings, now = at(day, 23), zone))
    }

    @Test
    fun `isQuietTimeNow is true in the early morning, within last night's window`() {
        val day = LocalDate.of(2026, 9, 21)
        assertTrue(BedtimeSchedule.isQuietTimeNow(settings, now = at(day, 5), zone))
    }

    @Test
    fun `isQuietTimeNow is false at midday`() {
        val day = LocalDate.of(2026, 9, 21)
        assertFalse(BedtimeSchedule.isQuietTimeNow(settings, now = at(day, 12), zone))
    }

    @Test
    fun `isQuietTimeNow is false right at wake time`() {
        val day = LocalDate.of(2026, 9, 21)
        assertFalse(BedtimeSchedule.isQuietTimeNow(settings, now = at(day, 7), zone))
    }

    @Test
    fun `nextSleepTrigger is today's sleep time when it hasn't happened yet`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day, 23), BedtimeSchedule.nextSleepTrigger(settings, now = at(day, 20), zone))
    }

    @Test
    fun `nextSleepTrigger rolls to tomorrow once today's sleep time has passed`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day.plusDays(1), 23), BedtimeSchedule.nextSleepTrigger(settings, now = at(day, 23, 30), zone))
    }
}
