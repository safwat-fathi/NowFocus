package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryStatsTest {

    private val zone = ZoneOffset.UTC
    private fun millisAt(date: LocalDate, hour: Int = 12) = date.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun completed(day: LocalDate, startHour: Int, durationMinutes: Long) = SessionHistoryRow(
        policyId = "p1",
        startAt = millisAt(day, startHour),
        endAt = millisAt(day, startHour) + durationMinutes * 60_000,
        status = FocusSessionStatus.COMPLETED,
    )

    private fun cancelled(day: LocalDate, startHour: Int, scheduledMinutes: Long, actualMinutes: Long) = SessionHistoryRow(
        policyId = "p1",
        startAt = millisAt(day, startHour),
        endAt = millisAt(day, startHour) + scheduledMinutes * 60_000,
        status = FocusSessionStatus.CANCELLED,
        cancelledAt = millisAt(day, startHour) + actualMinutes * 60_000,
    )

    @Test
    fun `completed session counts its full scheduled duration`() {
        val row = completed(LocalDate.of(2026, 9, 21), startHour = 9, durationMinutes = 45)
        assertEquals(45 * 60_000L, row.focusedMillis)
    }

    @Test
    fun `cancelled session counts only elapsed time, not the scheduled duration`() {
        val row = cancelled(LocalDate.of(2026, 9, 21), startHour = 9, scheduledMinutes = 60, actualMinutes = 20)
        assertEquals(20 * 60_000L, row.focusedMillis)
    }

    @Test
    fun `totalFocusedMillis sums only rows starting within the range`() {
        val monday = LocalDate.of(2026, 9, 21)
        val rows = listOf(
            completed(monday, 9, 30),
            completed(monday.plusDays(1), 9, 15),
            completed(monday.plusWeeks(1), 9, 100), // outside range
        )
        val from = millisAt(monday, 0)
        val to = millisAt(monday.plusDays(7), 0)
        assertEquals(45 * 60_000L, HistoryStats.totalFocusedMillis(rows, from, to))
    }

    @Test
    fun `weekBucketsMinutes places each session's minutes on its own weekday`() {
        val monday = LocalDate.of(2026, 9, 21) // known Monday
        val rows = listOf(completed(monday, 9, 30), completed(monday.plusDays(2), 9, 20)) // Mon, Wed
        val buckets = HistoryStats.weekBucketsMinutes(rows, anyDayInWeek = monday.plusDays(3), zone = zone)
        assertEquals(listOf(30L, 0L, 20L, 0L, 0L, 0L, 0L), buckets)
    }

    @Test
    fun `completionRate is completed over total in range`() {
        val day = LocalDate.of(2026, 9, 21)
        val rows = listOf(
            completed(day, 9, 30),
            completed(day, 11, 30),
            cancelled(day, 14, 60, 10),
        )
        val from = millisAt(day, 0)
        val to = millisAt(day.plusDays(1), 0)
        assertEquals(2.0 / 3.0, HistoryStats.completionRate(rows, from, to), 0.0001)
    }

    @Test
    fun `completionRate is zero with no sessions in range, not a division error`() {
        assertEquals(0.0, HistoryStats.completionRate(emptyList(), 0L, 1000L), 0.0001)
    }

    @Test
    fun `streak counts consecutive days ending today`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(completed(today, 9, 10), completed(today.minusDays(1), 9, 10), completed(today.minusDays(2), 9, 10))
        assertEquals(3, HistoryStats.currentStreakDays(rows, today, zone))
    }

    @Test
    fun `streak has a one-day grace so it doesn't zero out before today's session`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(completed(today.minusDays(1), 9, 10), completed(today.minusDays(2), 9, 10))
        assertEquals(2, HistoryStats.currentStreakDays(rows, today, zone))
    }

    @Test
    fun `streak breaks on a gap`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(
            completed(today, 9, 10),
            // gap on today.minusDays(1)
            completed(today.minusDays(2), 9, 10),
        )
        assertEquals(1, HistoryStats.currentStreakDays(rows, today, zone))
    }

    @Test
    fun `streak is zero with no history at all`() {
        assertEquals(0, HistoryStats.currentStreakDays(emptyList(), LocalDate.of(2026, 9, 25), zone))
    }

    @Test
    fun `streak is zero once the most recent session is more than a day old`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(completed(today.minusDays(3), 9, 10))
        assertEquals(0, HistoryStats.currentStreakDays(rows, today, zone))
    }

    @Test
    fun `topBlockedPackages ranks by count within range, most first`() {
        val events = listOf(
            BlockEventRow(packageName = "com.instagram.android", timestampMillis = 100),
            BlockEventRow(packageName = "com.instagram.android", timestampMillis = 200),
            BlockEventRow(packageName = "com.reddit.frontpage", timestampMillis = 300),
        )
        val result = HistoryStats.topBlockedPackages(events, from = 0, to = 1000, limit = 3)
        assertEquals(listOf("com.instagram.android" to 2, "com.reddit.frontpage" to 1), result)
    }

    @Test
    fun `topBlockedPackages excludes events outside the range`() {
        val events = listOf(BlockEventRow(packageName = "a", timestampMillis = 5000))
        assertTrue(HistoryStats.topBlockedPackages(events, from = 0, to = 1000).isEmpty())
    }

    @Test
    fun `first block event always logs`() {
        assertTrue(HistoryStats.shouldLogBlockEvent(now = 1000, lastLoggedAt = null))
    }

    @Test
    fun `a bounce within the dedup window does not log again`() {
        assertFalse(HistoryStats.shouldLogBlockEvent(now = 1000, lastLoggedAt = 500, windowMs = 3000))
    }

    @Test
    fun `a bounce after the dedup window logs as a new attempt`() {
        assertTrue(HistoryStats.shouldLogBlockEvent(now = 4000, lastLoggedAt = 500, windowMs = 3000))
    }
}
