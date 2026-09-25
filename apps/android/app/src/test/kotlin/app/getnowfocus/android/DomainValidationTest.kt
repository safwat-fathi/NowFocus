package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the repo-root test_domain_validation.swift. */
class DomainValidationTest {

    @Test
    fun `normalize matches macOS behaviour`() {
        assertEquals("youtube.com", DomainValidation.normalize("youtube.com"))
        assertEquals("youtube.com", DomainValidation.normalize("https://www.youtube.com"))
        assertEquals("youtube.com", DomainValidation.normalize("youtube.com/watch?v=abc"))
        assertEquals("reddit.com", DomainValidation.normalize("HTTP://Reddit.COM/r/all"))
        assertEquals("twitter.com", DomainValidation.normalize("  twitter.com  "))
        assertEquals(null, DomainValidation.normalize("evil.com\n1.2.3.4 bank.com"))
        assertEquals(null, DomainValidation.normalize("not a domain"))
        assertEquals(null, DomainValidation.normalize(""))
        assertEquals(null, DomainValidation.normalize("-bad.com"))
    }

    @Test
    fun `matches is a DNS suffix match, not substring`() {
        val rules = setOf("youtube.com")
        assertTrue(DomainValidation.matches("youtube.com", rules))
        assertTrue(DomainValidation.matches("m.youtube.com", rules))
        assertTrue(DomainValidation.matches("WWW.YouTube.com.", rules))
        assertFalse(DomainValidation.matches("notyoutube.com", rules))
        assertFalse(DomainValidation.matches("youtube.com.evil.net", rules))
    }
}
