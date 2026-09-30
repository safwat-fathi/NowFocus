package app.getnowfocus.android

object DurationFormat {
    /** "<1m", "45m", "1h 12m", "1h", "3d 4h": the time left, coarse enough to stay put between ticks. */
    fun remaining(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h".let { h -> if (minutes % 60 == 0L) h else "$h ${minutes % 60}m" }
            else -> "${minutes / (24 * 60)}d".let { d -> val hours = (minutes % (24 * 60)) / 60; if (hours == 0L) d else "$d ${hours}h" }
        }
    }
}
