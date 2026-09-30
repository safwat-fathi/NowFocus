package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFilterTest {

    private val apps = listOf(
        AppRule("com.google.android.youtube", "YouTube"),
        AppRule("com.instagram.android", "Instagram"),
        AppRule("com.reddit.frontpage", "Reddit"),
    )

    @Test
    fun `blank query keeps every app`() {
        assertEquals(apps, filterApps(apps, ""))
        assertEquals(apps, filterApps(apps, "   "))
    }

    @Test
    fun `matches label case-insensitively`() {
        assertEquals(listOf(apps[0]), filterApps(apps, "tube"))
        assertEquals(listOf(apps[1]), filterApps(apps, "INSTA"))
    }

    @Test
    fun `matches package name too`() {
        assertEquals(listOf(apps[2]), filterApps(apps, "frontpage"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(listOf(apps[2]), filterApps(apps, "  reddit "))
    }

    @Test
    fun `no match gives an empty list`() {
        assertEquals(emptyList<AppRule>(), filterApps(apps, "zzz"))
    }
}
