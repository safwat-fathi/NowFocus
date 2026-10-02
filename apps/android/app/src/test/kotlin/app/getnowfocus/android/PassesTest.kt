package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassesTest {
    private val start = 1_000_000L
    private fun session(
        mode: EnforcementMode = EnforcementMode.NORMAL,
        type: SessionType = SessionType.FOCUS,
        passes: List<AppPass> = emptyList(),
        end: Long = start + 60 * 60_000L,
    ) = FocusSession(
        id = "s", policyId = "p", startAt = start, endAt = end, status = FocusSessionStatus.ACTIVE, createdAt = start,
        packages = setOf("com.chat", "com.video"), enforcementMode = mode, sessionType = type, passes = passes,
    )

    @Test
    fun `normal and strict get two passes, locked and bedtime get none`() {
        assertEquals(2, Passes.passesLeft(session(EnforcementMode.NORMAL)))
        assertEquals(2, Passes.passesLeft(session(EnforcementMode.STRICT)))
        assertEquals(0, Passes.passesLeft(session(EnforcementMode.LOCKED)))
        assertEquals(0, Passes.passesLeft(session(EnforcementMode.NORMAL, SessionType.BEDTIME_WINDDOWN)))
    }

    @Test
    fun `a granted pass lasts five minutes and uses one of two`() {
        val granted = Passes.grant(session(), "com.chat", now = start + 1000)!!
        assertEquals(listOf(AppPass("com.chat", start + 1000 + Passes.DURATION_MS)), granted.passes)
        assertEquals(1, Passes.passesLeft(granted))
    }

    @Test
    fun `the third pass is refused`() {
        val two = session(passes = listOf(AppPass("com.chat", start + 10), AppPass("com.video", start + 20)))
        assertNull(Passes.grant(two, "com.chat", now = start + Passes.DURATION_MS * 2))
    }

    @Test
    fun `a locked session refuses a pass even for a blocked app`() {
        assertNull(Passes.grant(session(EnforcementMode.LOCKED), "com.chat", now = start + 1000))
    }

    @Test
    fun `only an app the session blocks can be passed`() {
        assertNull(Passes.grant(session(), "com.other", now = start + 1000))
    }

    @Test
    fun `an app that already has a live pass is not charged twice`() {
        val live = session(passes = listOf(AppPass("com.chat", start + 100_000)))
        assertNull(Passes.grant(live, "com.chat", now = start + 1000))
    }

    @Test
    fun `a pass never outlasts the session`() {
        val end = start + 60_000L
        val granted = Passes.grant(session(end = end), "com.chat", now = start + 1000)!!
        assertEquals(end, granted.passes.single().until)
    }

    @Test
    fun `no pass outside the running window`() {
        assertNull(Passes.grant(session(), "com.chat", now = start - 1))
        assertNull(Passes.grant(session(end = start + 1000), "com.chat", now = start + 2000))
    }

    @Test
    fun `passes survive a json round trip`() {
        val passes = listOf(AppPass("com.chat", 5L), AppPass("com.video", 9L))
        assertEquals(passes, Passes.fromJson(Passes.toJson(passes)))
        assertNotNull(Passes.fromJson("[]"))
        assertTrue(Passes.fromJson("[]").isEmpty())
    }
}
