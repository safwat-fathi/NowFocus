package app.getnowfocus.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * canCancel/isOver are boot-relative (elapsedRealtime + boot count), not
 * wall-clock: a fresh code review found that a wall-clock-only design let
 * setting the phone's date back reopen the (meant to be irrevocable) grace
 * period indefinitely, and setting it forward faked expiry - letting a
 * throwaway shield be created over, and permanently replace, a real one.
 */
class CommitmentShieldTest {

    private fun shield(createdElapsed: Long, bootCount: Int = 1, createdAt: Long = 1_000_000) = CommitmentShield(
        startAt = createdAt, endAt = createdAt + CommitmentShield.DURATION_MS,
        domains = setOf("x.com"), packages = emptySet(), createdAt = createdAt,
        createdElapsedRealtime = createdElapsed, createdBootCount = bootCount,
    )

    @Test
    fun `can cancel immediately after creation`() {
        assertTrue(shield(createdElapsed = 5000).canCancel(nowElapsedRealtime = 5000, currentBootCount = 1))
    }

    @Test
    fun `can cancel right up to the grace deadline`() {
        assertTrue(shield(createdElapsed = 5000).canCancel(nowElapsedRealtime = 5000 + CommitmentShield.GRACE_MS - 1, currentBootCount = 1))
    }

    @Test
    fun `cannot cancel once the grace period has fully elapsed - the only exit this ever gets`() {
        val s = shield(createdElapsed = 5000)
        assertFalse(s.canCancel(nowElapsedRealtime = 5000 + CommitmentShield.GRACE_MS, currentBootCount = 1))
        assertFalse(s.canCancel(nowElapsedRealtime = 5000 + CommitmentShield.GRACE_MS + 999_999, currentBootCount = 1))
    }

    @Test
    fun `setting the wall clock back cannot reopen the grace period - it is boot-elapsed-time only`() {
        // Old bug: canCancel(now) used wall-clock `now < createdAt + GRACE_MS`,
        // which a clock set backwards satisfies forever. elapsedRealtime is
        // immune to wall-clock changes by definition.
        val s = shield(createdElapsed = 5000, createdAt = 1_000_000)
        // Wall clock set back to before createdAt is irrelevant - only elapsed/boot matter.
        assertFalse(s.canCancel(nowElapsedRealtime = 5000 + CommitmentShield.GRACE_MS + 1, currentBootCount = 1))
    }

    @Test
    fun `cannot cancel after a reboot - elapsed time no longer means anything, so refuse rather than guess`() {
        val s = shield(createdElapsed = 5000, bootCount = 1)
        // A reboot resets elapsedRealtime near zero, so the raw delta might look
        // "within grace" - the boot-count mismatch must override that.
        assertFalse(s.canCancel(nowElapsedRealtime = 100, currentBootCount = 2))
    }

    @Test
    fun `not over well before 14 days`() {
        val s = shield(createdElapsed = 0)
        assertFalse(s.isOver(wallNow = 1_000_000, nowElapsedRealtime = CommitmentShield.DURATION_MS - 1, currentBootCount = 1))
    }

    @Test
    fun `over once 14 elapsed days have genuinely passed, same boot`() {
        val s = shield(createdElapsed = 0)
        assertTrue(s.isOver(wallNow = 1_000_000, nowElapsedRealtime = CommitmentShield.DURATION_MS, currentBootCount = 1))
    }

    @Test
    fun `setting the wall clock forward cannot fake expiry within the same boot`() {
        // Old bug: a naive `wallNow >= endAt` check is satisfied by winding the
        // clock forward, letting a throwaway shield be created over the real
        // one and then cancelled (or the clock wound back), permanently
        // defeating it. Elapsed-realtime ignores wall-clock entirely.
        val s = shield(createdElapsed = 0, createdAt = 1_000_000)
        val fakedWallClock = s.endAt + 999_999
        assertFalse(s.isOver(wallNow = fakedWallClock, nowElapsedRealtime = 1000, currentBootCount = 1))
    }

    @Test
    fun `falls back to wall-clock across a reboot, since elapsed time resets`() {
        val s = shield(createdElapsed = 5000, bootCount = 1, createdAt = 1_000_000)
        assertTrue(s.isOver(wallNow = s.endAt + 1, nowElapsedRealtime = 100, currentBootCount = 2))
        assertFalse(s.isOver(wallNow = s.endAt - 1, nowElapsedRealtime = 100, currentBootCount = 2))
    }
}
