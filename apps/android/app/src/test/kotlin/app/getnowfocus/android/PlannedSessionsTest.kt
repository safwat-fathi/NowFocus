package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedSessionsTest {
    private fun plan(startAt: Long, endAt: Long = startAt + 60 * 60_000L) =
        PlannedSession(policyId = "p1", startAt = startAt, endAt = endAt, createdAt = 0L)

    @Test
    fun `must be at least LEAD_MS ahead`() {
        val now = 1_000_000L
        assertFalse(PlannedSessions.canSchedule(now, now + PlannedSessions.LEAD_MS - 1))
        assertTrue(PlannedSessions.canSchedule(now, now + PlannedSessions.LEAD_MS))
    }

    @Test
    fun `due picks the earliest one whose start has passed, not a later one`() {
        val now = 1_000_000L
        val earlier = plan(now - 1000)
        val later = plan(now - 500)
        val future = plan(now + 1000)
        assertEquals(earlier, PlannedSessions.due(listOf(later, earlier, future), now))
    }

    @Test
    fun `due is null when nothing has started yet`() {
        assertNull(PlannedSessions.due(listOf(plan(2000)), now = 1000))
    }

    @Test
    fun `nextStart is the soonest future one, ignoring ones already due`() {
        val now = 1_000_000L
        val due = plan(now - 1)
        val soon = plan(now + 500)
        val later = plan(now + 5000)
        assertEquals(500L + now, PlannedSessions.nextStart(listOf(later, due, soon), now))
    }

    @Test
    fun `survives a json round trip`() {
        val list = listOf(plan(1000), plan(2000, 9000).copy(mode = EnforcementMode.STRICT))
        assertEquals(list, PlannedSession.listFromJson(PlannedSession.listToJson(list)))
    }
}
