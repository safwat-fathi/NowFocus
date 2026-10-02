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

    private fun attempt(pkg: String, day: LocalDate, hour: Int, minute: Int = 0) =
        BlockEventRow(packageName = pkg, timestampMillis = day.atTime(hour, minute).toInstant(zone).toEpochMilli())

    @Test
    fun `urgeByHour buckets attempts by local hour across all 24 hours`() {
        val day = LocalDate.of(2026, 9, 21)
        val events = listOf(attempt("a", day, 15), attempt("a", day.plusDays(1), 15, 59), attempt("b", day, 9))
        val hours = HistoryStats.urgeByHour(events, zone)
        assertEquals(24, hours.size)
        assertEquals(2, hours[15])
        assertEquals(1, hours[9])
        assertEquals(3, hours.sum())
    }

    @Test
    fun `urgeByHour uses the given zone, not UTC`() {
        val event = attempt("a", LocalDate.of(2026, 9, 21), hour = 23, minute = 30) // 23:30 UTC
        val hours = HistoryStats.urgeByHour(listOf(event), ZoneOffset.ofHours(2)) // = 01:30 next day
        assertEquals(1, hours[1])
        assertEquals(0, hours[23])
    }

    @Test
    fun `peakUrge picks the busiest hour and the app most tried in it`() {
        val day = LocalDate.of(2026, 9, 21)
        val events = listOf(
            attempt("insta", day, 15), attempt("insta", day, 15, 20), attempt("reddit", day, 15, 40),
            attempt("reddit", day, 9), attempt("reddit", day, 9, 5),
        )
        assertEquals(Urge(hour = 15, count = 3, topPackage = "insta"), HistoryStats.peakUrge(events, zone))
    }

    @Test
    fun `peakUrge breaks a tie toward the earlier hour`() {
        val day = LocalDate.of(2026, 9, 21)
        val events = listOf(attempt("a", day, 20), attempt("b", day, 8))
        assertEquals(8, HistoryStats.peakUrge(events, zone)?.hour)
    }

    @Test
    fun `peakUrge is null with no attempts`() {
        assertEquals(null, HistoryStats.peakUrge(emptyList(), zone))
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

    @Test
    fun `turnedAwayCount counts every attempt in range across all apps, not just the top few`() {
        val day = LocalDate.of(2026, 9, 21)
        val events = listOf("a", "b", "c", "d", "e").map { attempt(it, day, 10) } +
            attempt("a", day, 11) + attempt("outside", day.plusDays(1), 10)
        val from = millisAt(day, 0)
        val to = millisAt(day.plusDays(1), 0)
        assertEquals(6, HistoryStats.turnedAwayCount(events, from, to))
        assertEquals(0, HistoryStats.turnedAwayCount(emptyList(), from, to))
    }

    @Test
    fun `startOfDay is local midnight in the given zone`() {
        val day = LocalDate.of(2026, 9, 21)
        val cairo = java.time.ZoneId.of("Africa/Cairo")
        val noonUtc = millisAt(day, 12)
        assertEquals(millisAt(day, 0), HistoryStats.startOfDayMillis(noonUtc, ZoneOffset.UTC))
        assertEquals(day.atStartOfDay(cairo).toInstant().toEpochMilli(), HistoryStats.startOfDayMillis(noonUtc, cairo))
    }

    @Test
    fun `a session ended almost at once does not keep the streak alive`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(completed(today.minusDays(1), 9, 30), cancelled(today, 9, scheduledMinutes = 60, actualMinutes = 1))
        assertEquals(1, HistoryStats.currentStreakDays(rows, today, zone)) // yesterday counts, today's 1-minute one doesn't
        assertEquals(0, HistoryStats.currentStreakDays(listOf(cancelled(today, 9, 60, 2)), today, zone))
    }

    @Test
    fun `several short sessions in a day add up toward the streak`() {
        val today = LocalDate.of(2026, 9, 25)
        val rows = listOf(cancelled(today, 9, 30, 5), cancelled(today, 11, 30, 6))
        assertEquals(1, HistoryStats.currentStreakDays(rows, today, zone))
    }

    @Test
    fun `focus score is null for an empty week and full marks for a perfect one`() {
        val monday = LocalDate.of(2026, 9, 21)
        val from = millisAt(monday, 0)
        val to = millisAt(monday.plusDays(7), 0)
        assertEquals(null, HistoryStats.focusScore(emptyList(), from, to, zone))
        val perfect = (0..4).map { completed(monday.plusDays(it.toLong()), 9, 60) } // 5 days x 1h = 5h, all completed
        assertEquals(100, HistoryStats.focusScore(perfect, from, to, zone))
    }

    @Test
    fun `focus score weighs completion, time and days`() {
        val monday = LocalDate.of(2026, 9, 21)
        val from = millisAt(monday, 0)
        val to = millisAt(monday.plusDays(7), 0)
        // One 60-minute completed session: 0.5*1 + 0.3*(60/300) + 0.2*(1/5) = 0.5 + 0.06 + 0.04 = 0.60
        assertEquals(60, HistoryStats.focusScore(listOf(completed(monday, 9, 60)), from, to, zone))
        // One cancelled session: completion 0, 30 focused minutes: 0.3*0.1 + 0.2*0.2 = 0.07
        assertEquals(7, HistoryStats.focusScore(listOf(cancelled(monday, 9, 60, 30)), from, to, zone))
    }

    @Test
    fun `the weekly summary has counts only`() {
        val monday = LocalDate.of(2026, 9, 21)
        val from = millisAt(monday, 0)
        val to = millisAt(monday.plusDays(7), 0)
        val rows = listOf(completed(monday, 9, 90), cancelled(monday.plusDays(1), 9, 60, 30))
        val events = listOf(BlockEventRow(packageName = "com.secret.app", timestampMillis = millisAt(monday, 10)))
        val text = HistoryStats.weekSummaryText(rows, events, from, to, today = monday.plusDays(1), zone = zone)
        assertTrue(text, text.startsWith("My NowFocus week: 2h 0m focused across 2 sessions (1 completed)."))
        assertTrue(text, text.contains("Turned away 1 times."))
        assertFalse(text, text.contains("secret"))
    }
}
