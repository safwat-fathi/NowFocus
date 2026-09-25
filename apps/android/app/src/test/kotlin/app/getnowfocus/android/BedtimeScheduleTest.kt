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

    // Regression coverage for the exact bug a fresh code review caught: the
    // original nextBoundary only considered today's and tomorrow's windows,
    // so after midnight it missed today's wake time entirely (that boundary
    // belongs to LAST NIGHT's window) and jumped straight to tonight's
    // wind-down - leaving quiet-notifications stuck on all day.

    @Test
    fun `nextBoundary just after midnight is this morning's wake, not tonight's wind-down`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day, 7), BedtimeSchedule.nextBoundary(settings, now = at(day, 0, 30), zone))
    }

    @Test
    fun `nextBoundary in the early morning is still this morning's wake`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day, 7), BedtimeSchedule.nextBoundary(settings, now = at(day, 2), zone))
    }

    @Test
    fun `nextBoundary late at night, inside tonight's window, is tomorrow's wake`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day.plusDays(1), 7), BedtimeSchedule.nextBoundary(settings, now = at(day, 23, 5), zone))
    }

    @Test
    fun `nextBoundary at midday is tonight's wind-down`() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day, 22), BedtimeSchedule.nextBoundary(settings, now = at(day, 12), zone))
    }

    @Test
    fun `nextBoundary handles a sleep time that has itself rolled past midnight`() {
        // windDown 22:00 unchanged, sleep bumped to 00:00, wake 07:00: the sleep
        // alarm firing at 00:00 must still find today's 07:00 wake as next.
        val lateSleep = settings.copy(sleepMinute = 0)
        val day = LocalDate.of(2026, 9, 21)
        assertEquals(at(day, 7), BedtimeSchedule.nextBoundary(lateSleep, now = at(day, 0, 0), zone))
    }

    // decideQuietFilter: the pure decision behind reconciling the interruption
    // filter, callable from the alarm, boot, settings-save, and app-init - so
    // disabling Bedtime or turning off the toggle mid-window doesn't leave
    // notifications stuck quiet until the next scheduled alarm happens to fire.

    private val enabledSettings = settings.copy(enabled = true, quietNotifications = true)
    private val insideWindow = at(LocalDate.of(2026, 9, 21), 23)
    private val outsideWindow = at(LocalDate.of(2026, 9, 21), 12)

    @Test
    fun `decideQuietFilter sets priority when quiet time starts and filter isn't already priority`() {
        assertEquals(
            QuietDecision.SET_PRIORITY,
            BedtimeSchedule.decideQuietFilter(enabledSettings, insideWindow, zone, currentFilterIsPriority = false),
        )
    }

    @Test
    fun `decideQuietFilter is a no-op once priority is already set`() {
        assertEquals(
            QuietDecision.NONE,
            BedtimeSchedule.decideQuietFilter(enabledSettings, insideWindow, zone, currentFilterIsPriority = true),
        )
    }

    @Test
    fun `decideQuietFilter restores once outside the window`() {
        assertEquals(
            QuietDecision.RESTORE_ALL,
            BedtimeSchedule.decideQuietFilter(enabledSettings, outsideWindow, zone, currentFilterIsPriority = true),
        )
    }

    @Test
    fun `decideQuietFilter restores immediately when Bedtime is disabled mid-window`() {
        val disabled = enabledSettings.copy(enabled = false)
        assertEquals(
            QuietDecision.RESTORE_ALL,
            BedtimeSchedule.decideQuietFilter(disabled, insideWindow, zone, currentFilterIsPriority = true),
        )
    }

    @Test
    fun `decideQuietFilter restores immediately when the quiet-notifications toggle is turned off mid-window`() {
        val toggleOff = enabledSettings.copy(quietNotifications = false)
        assertEquals(
            QuietDecision.RESTORE_ALL,
            BedtimeSchedule.decideQuietFilter(toggleOff, insideWindow, zone, currentFilterIsPriority = true),
        )
    }

    @Test
    fun `decideQuietFilter never touches a filter it didn't set`() {
        // Outside the window, filter not priority - nothing to restore, nothing to set.
        assertEquals(
            QuietDecision.NONE,
            BedtimeSchedule.decideQuietFilter(enabledSettings, outsideWindow, zone, currentFilterIsPriority = false),
        )
    }
}
