package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private fun session(status: FocusSessionStatus, startAt: Long, endAt: Long) =
        FocusSession(id = "s1", policyId = "p1", startAt = startAt, endAt = endAt, status = status, createdAt = startAt)

    @Test
    fun `scheduled becomes active once now reaches startAt`() {
        val s = session(FocusSessionStatus.SCHEDULED, startAt = 1000, endAt = 2000)
        val result = SessionEngine.evaluateState(s, now = 1500)
        assertEquals(FocusSessionStatus.ACTIVE, result.status)
    }

    @Test
    fun `scheduled expires if never observed before endAt`() {
        val s = session(FocusSessionStatus.SCHEDULED, startAt = 1000, endAt = 2000)
        val result = SessionEngine.evaluateState(s, now = 2500)
        assertEquals(FocusSessionStatus.EXPIRED, result.status)
    }

    @Test
    fun `active completes exactly at endAt`() {
        val s = session(FocusSessionStatus.ACTIVE, startAt = 1000, endAt = 2000)
        val result = SessionEngine.evaluateState(s, now = 2000)
        assertEquals(FocusSessionStatus.COMPLETED, result.status)
    }

    @Test
    fun `active stays active before endAt`() {
        val s = session(FocusSessionStatus.ACTIVE, startAt = 1000, endAt = 2000)
        val result = SessionEngine.evaluateState(s, now = 1999)
        assertEquals(FocusSessionStatus.ACTIVE, result.status)
    }

    @Test
    fun `terminal status is left untouched`() {
        val s = session(FocusSessionStatus.CANCELLED, startAt = 1000, endAt = 2000)
        val result = SessionEngine.evaluateState(s, now = 1500)
        assertEquals(FocusSessionStatus.CANCELLED, result.status)
    }

    @Test
    fun `isActive is false once endAt has passed even if status is stale`() {
        val s = session(FocusSessionStatus.ACTIVE, startAt = 1000, endAt = 2000)
        assertTrue(SessionEngine.isActive(s, now = 1500))
        assertFalse(SessionEngine.isActive(s, now = 2000))
    }

    @Test
    fun `normal mode can always cancel`() {
        assertTrue(SessionEngine.canCancel(EnforcementMode.NORMAL, unlockCompleted = false))
        assertTrue(SessionEngine.canCancel(EnforcementMode.NORMAL, unlockCompleted = true))
    }

    @Test
    fun `strict mode requires unlock to complete first`() {
        assertFalse(SessionEngine.canCancel(EnforcementMode.STRICT, unlockCompleted = false))
        assertTrue(SessionEngine.canCancel(EnforcementMode.STRICT, unlockCompleted = true))
    }

    @Test
    fun `locked mode never allows cancelling, even if unlock somehow reports complete`() {
        assertFalse(SessionEngine.canCancel(EnforcementMode.LOCKED, unlockCompleted = false))
        assertFalse(SessionEngine.canCancel(EnforcementMode.LOCKED, unlockCompleted = true))
    }
}
