package app.getnowfocus.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProTest {
    private val allowlist = BlockPolicy(name = "Only these", mode = PolicyMode.ALLOWLIST, apps = listOf(AppRule("com.example.a", "A")))
    private val blocklist = BlockPolicy(name = "Block these")

    @Test fun `everything is open while the gate is off`() {
        for (stored in listOf(null, false, true)) assertTrue(Pro.active(stored, enforced = false))
    }

    @Test fun `enforced, only an account sync last found on Pro counts`() {
        assertTrue(Pro.active(true, enforced = true))
        assertFalse(Pro.active(false, enforced = true))
        assertFalse("signed out or not asked yet is Free", Pro.active(null, enforced = true))
    }

    @Test fun `a free account starts blocklists but not allowlists`() {
        assertTrue(blocklist.canStart(pro = false))
        assertFalse(allowlist.canStart(pro = false))
        assertTrue(allowlist.canStart(pro = true))
    }

    @Test fun `an allowlist with no app on this phone never starts, Pro or not`() {
        assertFalse(allowlist.copy(apps = emptyList()).canStart(pro = true))
    }
}
