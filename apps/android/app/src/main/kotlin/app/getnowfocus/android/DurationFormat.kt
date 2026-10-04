package app.getnowfocus.android

object DurationFormat {
    /** "<1m", "45m", "1h 12m", "1h", "3d 4h": the time left, coarse enough to stay put between ticks. */
    fun remaining(ms: Long): UiText {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes < 1 -> uiText(R.string.duration_lt_min)
            minutes < 60 -> uiText(R.string.duration_m, minutes)
            minutes < 24 * 60 ->
                if (minutes % 60 == 0) uiText(R.string.duration_h, minutes / 60) else uiText(R.string.duration_hm, minutes / 60, minutes % 60)
            else -> {
                val hours = (minutes % (24 * 60)) / 60
                if (hours == 0) uiText(R.string.duration_d, minutes / (24 * 60)) else uiText(R.string.duration_dh, minutes / (24 * 60), hours)
            }
        }
    }
}
