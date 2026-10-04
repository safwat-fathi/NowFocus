package app.getnowfocus.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun `under a minute is shown as less than one minute`() {
        assertEquals(uiText(R.string.duration_lt_min), DurationFormat.remaining(30_000))
        assertEquals(uiText(R.string.duration_lt_min), DurationFormat.remaining(0))
        assertEquals(uiText(R.string.duration_lt_min), DurationFormat.remaining(-5_000))
    }

    @Test
    fun `minutes then hours and minutes`() {
        assertEquals(uiText(R.string.duration_m, 45), DurationFormat.remaining(45 * min + 20_000))
        assertEquals(uiText(R.string.duration_hm, 1, 12), DurationFormat.remaining(hour + 12 * min))
        assertEquals(uiText(R.string.duration_h, 1), DurationFormat.remaining(hour))
    }

    @Test
    fun `days and hours for a long commitment`() {
        assertEquals(uiText(R.string.duration_dh, 3, 4), DurationFormat.remaining(3 * day + 4 * hour + 30 * min))
        assertEquals(uiText(R.string.duration_d, 1), DurationFormat.remaining(day))
    }
}
