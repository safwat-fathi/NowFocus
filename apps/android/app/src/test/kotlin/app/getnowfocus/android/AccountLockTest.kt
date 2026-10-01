package app.getnowfocus.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLockTest {
    @Test
    fun `locks only when on, signed in, usable and not yet unlocked`() {
        assertTrue(accountLocked(enabled = true, available = true, signedIn = true, unlocked = false))
    }

    @Test
    fun `every other combination opens the screen`() {
        assertFalse("lock off", accountLocked(enabled = false, available = true, signedIn = true, unlocked = false))
        assertFalse("signed out has nothing to protect", accountLocked(enabled = true, available = true, signedIn = false, unlocked = false))
        assertFalse("already unlocked this visit", accountLocked(enabled = true, available = true, signedIn = true, unlocked = true))
        // Fingerprint removed or unsupported: never strand the user outside Sign out / Delete.
        assertFalse("fail open", accountLocked(enabled = true, available = false, signedIn = true, unlocked = false))
    }
}
