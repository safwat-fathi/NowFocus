package app.getnowfocus.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentShieldTest {

    private fun shield(createdAt: Long) = CommitmentShield(
        startAt = createdAt, endAt = createdAt + CommitmentShield.DURATION_MS,
        domains = setOf("x.com"), packages = emptySet(), createdAt = createdAt,
    )

    @Test
    fun `can cancel immediately after creation`() {
        assertTrue(shield(createdAt = 1000).canCancel(now = 1000))
    }

    @Test
    fun `can cancel right up to the grace deadline`() {
        assertTrue(shield(createdAt = 1000).canCancel(now = 1000 + CommitmentShield.GRACE_MS - 1))
    }

    @Test
    fun `cannot cancel once the grace period has fully elapsed - the only exit this ever gets`() {
        assertFalse(shield(createdAt = 1000).canCancel(now = 1000 + CommitmentShield.GRACE_MS))
        assertFalse(shield(createdAt = 1000).canCancel(now = 1000 + CommitmentShield.GRACE_MS + 999_999))
    }
}
