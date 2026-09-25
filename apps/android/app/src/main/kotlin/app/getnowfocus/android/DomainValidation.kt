package app.getnowfocus.android

/**
 * Port of apps/macos/NowFocusCore/DomainValidation.swift, plus the suffix
 * matcher the VPN uses. Pure Kotlin so it runs in plain JVM unit tests.
 */
object DomainValidation {
    private const val ALLOWED = "abcdefghijklmnopqrstuvwxyz0123456789.-"

    /** Returns a normalized lowercase hostname, or null if the input isn't a plausible bare domain. */
    fun normalize(raw: String): String? {
        var domain = raw.trim().lowercase()
        domain = domain.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        // Strip anything after the host itself: path, port, query, fragment.
        val cut = domain.indexOfFirst { it in "/:?#" }
        if (cut >= 0) domain = domain.substring(0, cut)

        val ok = domain.isNotEmpty() &&
            '.' in domain &&
            !domain.startsWith('.') && !domain.endsWith('.') &&
            !domain.startsWith('-') && !domain.endsWith('-') &&
            domain.all { it in ALLOWED }
        return if (ok) domain else null
    }

    /** DNS suffix match: "youtube.com" blocks "m.youtube.com" but not "notyoutube.com". */
    fun matches(host: String, rules: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return rules.any { h == it || h.endsWith(".$it") }
    }
}
