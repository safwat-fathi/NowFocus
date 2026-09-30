package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun `under a minute is shown as less than one minute`() {
        assertEquals("<1m", DurationFormat.remaining(30_000))
        assertEquals("<1m", DurationFormat.remaining(0))
        assertEquals("<1m", DurationFormat.remaining(-5_000))
    }

    @Test
    fun `minutes then hours and minutes`() {
        assertEquals("45m", DurationFormat.remaining(45 * min + 20_000))
        assertEquals("1h 12m", DurationFormat.remaining(hour + 12 * min))
        assertEquals("1h", DurationFormat.remaining(hour))
    }

    @Test
    fun `days and hours for a long commitment`() {
        assertEquals("3d 4h", DurationFormat.remaining(3 * day + 4 * hour + 30 * min))
        assertEquals("1d", DurationFormat.remaining(day))
    }
}
