package app.getnowfocus.android

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialsProblemTest {
    @Test
    fun `incomplete form says why instead of doing nothing`() {
        assertNotNull(credentialsProblem("", ""))
        assertNotNull(credentialsProblem("a.b", "longenough"))
        assertNotNull(credentialsProblem("a@b.co", "1234567"))
    }

    @Test
    fun `valid form passes`() {
        assertNull(credentialsProblem("a@b.co", "12345678"))
    }
}
