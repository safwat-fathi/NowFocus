package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageLimitsTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(h: Int, m: Int = 0, d: Int = 2) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun min(n: Long) = n * 60_000L
    private fun resume(pkg: String, t: Long) = UsageEvt(pkg, true, t)
    private fun pause(pkg: String, t: Long) = UsageEvt(pkg, false, t)
    private val from = at(0)

    @Test
    fun `two separate stretches add up`() {
        val events = listOf(resume("ig", at(9)), pause("ig", at(9, 10)), resume("ig", at(13)), pause("ig", at(13, 5)))
        assertEquals(min(15), UsageMath.foregroundMillis(events, "ig", from, now = at(14)))
    }

    @Test
    fun `an app still in front counts up to now`() {
        assertEquals(min(7), UsageMath.foregroundMillis(listOf(resume("ig", at(9))), "ig", from, now = at(9, 7)))
    }

    @Test
    fun `an app already in front at midnight counts from midnight`() {
        // Only the pause is in the window: it had been open since before.
        assertEquals(min(30), UsageMath.foregroundMillis(listOf(pause("ig", at(0, 30))), "ig", from, now = at(9)))
    }

    @Test
    fun `resuming the next screen before the old one pauses does not double count or cut short`() {
        val events = listOf(resume("ig", at(9)), resume("ig", at(9, 4)), pause("ig", at(9, 5)), pause("ig", at(9, 10)))
        assertEquals(min(10), UsageMath.foregroundMillis(events, "ig", from, now = at(11)))
    }

    @Test
    fun `other apps' events are ignored`() {
        val events = listOf(resume("yt", at(9)), pause("yt", at(10)), resume("ig", at(11)), pause("ig", at(11, 3)))
        assertEquals(min(3), UsageMath.foregroundMillis(events, "ig", from, now = at(12)))
        assertEquals(0L, UsageMath.foregroundMillis(events, "none", from, now = at(12)))
    }

    @Test
    fun `a stray extra pause does not go negative or count twice`() {
        val events = listOf(resume("ig", at(9)), pause("ig", at(9, 10)), pause("ig", at(9, 20)))
        // The second pause has no resume: read as "was open since the window opened", the safe over-count.
        assertTrue(UsageMath.foregroundMillis(events, "ig", from, now = at(10)) >= min(10))
    }

    @Test
    fun `a limit lifts at the next local midnight`() {
        assertEquals(at(0, d = 3), UsageMath.nextMidnight(at(23, 59), zone))
        assertEquals(at(0, d = 3), UsageMath.nextMidnight(at(0), zone))
    }

    private val ig = AppLimit("ig", "Instagram", minutesPerDay = 30)

    @Test
    fun `tightening a limit counts at once`() {
        val tighter = ig.withMinutes(15, at(10), zone)
        assertEquals(15, tighter.minutesAt(at(10)))
        assertNull(tighter.pendingMinutes)
    }

    @Test
    fun `loosening a limit waits for midnight`() {
        val looser = ig.withMinutes(60, at(10), zone)
        assertEquals(30, looser.minutesAt(at(10)))
        assertEquals(30, looser.minutesAt(at(23, 59)))
        assertEquals(60, looser.minutesAt(at(0, d = 3)))
        assertEquals(60, looser.settled(at(0, d = 3))!!.minutesPerDay)
    }

    @Test
    fun `removing a limit also waits for midnight, then it is gone`() {
        val removed = ig.withMinutes(0, at(10), zone)
        assertEquals(30, removed.minutesAt(at(10)))
        assertEquals(0, removed.minutesAt(at(0, d = 3)))
        assertNull(removed.settled(at(0, d = 3)))
        assertEquals(removed, removed.settled(at(10)))
    }

    @Test
    fun `a pending change survives json`() {
        val list = listOf(ig, ig.copy(packageName = "yt", label = "YouTube").withMinutes(60, at(10), zone))
        assertEquals(list, AppLimit.listFromJson(AppLimit.listToJson(list)))
    }

    @Test
    fun `friction lets an app open for ten minutes after you answer`() {
        assertFalse(FrictionGate.isAllowed("ig", now = 1_000))
        FrictionGate.allow("ig", now = 1_000)
        assertTrue(FrictionGate.isAllowed("ig", now = 1_000 + FrictionGate.OPEN_MS - 1))
        assertFalse(FrictionGate.isAllowed("ig", now = 1_000 + FrictionGate.OPEN_MS))
        assertFalse(FrictionGate.isAllowed("other", now = 1_000))
    }

    @Test
    fun `app urges give each app its own busiest hour, most-tried first, and skip thin ones`() {
        fun tries(pkg: String, vararg hours: Int) = hours.map { BlockEventRow(packageName = pkg, timestampMillis = at(it)) }
        val events = tries("ig", 23, 23, 23, 22) + tries("yt", 9, 9, 14) + tries("rare", 3, 3)
        val urges = HistoryStats.appUrges(events, zone)
        assertEquals(listOf("ig", "yt"), urges.map { it.packageName })
        assertEquals(AppUrge("ig", hour = 23, hourCount = 3, total = 4), urges[0])
        assertEquals(9, urges[1].hour)
    }
}
