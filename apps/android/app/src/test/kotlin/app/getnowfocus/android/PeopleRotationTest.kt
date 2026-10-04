package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeopleRotationTest {

    private val day = 24 * 60 * 60 * 1000L
    private fun person(id: String, addedAt: Long = 0, lastTalkedAt: Long? = null) =
        Person(id = id, name = id, phone = "+100", addedAt = addedAt, lastTalkedAt = lastTalkedAt)

    @Test
    fun `next is null with nobody added`() {
        assertNull(PeopleRotation.next(emptyList()))
    }

    @Test
    fun `next picks whoever you talked to longest ago`() {
        val people = listOf(person("recent", lastTalkedAt = 900), person("oldest", lastTalkedAt = 100), person("middle", lastTalkedAt = 500))
        assertEquals("oldest", PeopleRotation.next(people)?.id)
    }

    @Test
    fun `can't remember counts as oldest of all`() {
        val people = listOf(person("long-ago", lastTalkedAt = 1), person("unknown", lastTalkedAt = null))
        assertEquals("unknown", PeopleRotation.next(people)?.id)
    }

    @Test
    fun `ties go to whoever was added first`() {
        val people = listOf(person("later", addedAt = 20), person("earlier", addedAt = 10))
        assertEquals("earlier", PeopleRotation.next(people)?.id)
    }

    @Test
    fun `sinceLabel is null when unknown`() {
        assertNull(person("a", lastTalkedAt = null).sinceLabel(now = 100 * day))
    }

    @Test
    fun `sinceLabel makes no claim for a very recent chat`() {
        assertNull(person("a", lastTalkedAt = 100 * day - 2 * day).sinceLabel(now = 100 * day))
    }

    @Test
    fun `sinceLabel counts days up to two weeks`() {
        assertEquals(UiText.Plural(R.plurals.since_days, 3), person("a", lastTalkedAt = 100 * day - 3 * day).sinceLabel(now = 100 * day))
        assertEquals(UiText.Plural(R.plurals.since_days, 13), person("a", lastTalkedAt = 100 * day - 13 * day).sinceLabel(now = 100 * day))
    }

    @Test
    fun `sinceLabel switches to weeks at two weeks`() {
        assertEquals(UiText.Plural(R.plurals.since_weeks, 2), person("a", lastTalkedAt = 100 * day - 14 * day).sinceLabel(now = 100 * day))
        assertEquals(UiText.Plural(R.plurals.since_weeks, 8), person("a", lastTalkedAt = 100 * day - 59 * day).sinceLabel(now = 100 * day))
    }

    @Test
    fun `sinceLabel switches to months at sixty days`() {
        assertEquals(UiText.Plural(R.plurals.since_months, 2), person("a", lastTalkedAt = 100 * day - 65 * day).sinceLabel(now = 100 * day))
    }
}
