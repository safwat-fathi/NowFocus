package app.getnowfocus.android

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * "30 minutes of youtube.com a day". A site limit is an [AppLimit] whose key is "site:<domain>", so the
 * pending-change rules, JSON and dialogs are shared with app limits. UsageStats can't see inside a browser,
 * so the time is counted by [FocusAccessibilityService] from the address bar and kept in [SiteUsage].
 */
// ponytail: "site:" prefix on packageName instead of a kind field; add a real kind if a third limit type appears.
object SiteLimits {
    private const val PREFIX = "site:"

    fun key(domain: String) = PREFIX + domain
    fun isSite(l: AppLimit) = l.packageName.startsWith(PREFIX)
    fun domain(l: AppLimit) = l.packageName.removePrefix(PREFIX)

    /** The limit key that covers [host] ("m.youtube.com" -> "site:youtube.com"), longest domain first; null if none. */
    fun keyFor(host: String, limits: List<AppLimit>): String? =
        limits.filter { isSite(it) && DomainValidation.matches(host, listOf(domain(it))) }
            .maxByOrNull { domain(it).length }?.packageName

    /** The host an address bar is showing, or null for a search box, placeholder or anything that isn't a bare site. */
    fun hostOf(barText: String?): String? = barText?.let { DomainValidation.normalize(it) }

    /** Local calendar day of [now]: the usage counters roll over when this changes. */
    fun dayOf(now: Long, zone: ZoneId): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
}

/** Where the address bar was on the previous tick. [key] null = no limited site in front. */
data class SiteTick(val key: String?, val at: Long)

object SiteTimer {
    /**
     * Milliseconds to credit to [prev]'s site now. Capped at [maxGap] so a stall (doze, a missed tick) can't
     * over-credit: the time between ticks is only ever as long as one tick interval.
     */
    fun credit(prev: SiteTick?, now: Long, maxGap: Long): Long =
        if (prev?.key == null) 0L else (now - prev.at).coerceIn(0L, maxGap)
}

/** Time spent per limited site on one local [day]. A different day reads as empty, so there is nothing to reset. */
data class SiteUsage(val day: String = "", val ms: Map<String, Long> = emptyMap()) {
    fun usedMs(key: String, today: String): Long = if (day == today) ms[key] ?: 0L else 0L

    fun plus(today: String, key: String, add: Long): SiteUsage {
        val base = if (day == today) ms else emptyMap()
        return SiteUsage(today, base + (key to (base[key] ?: 0L) + add))
    }

    fun toJson(): String = JSONObject().put("day", day).put("ms", JSONObject(ms)).toString()

    companion object {
        fun fromJson(json: String): SiteUsage = JSONObject(json).let { o ->
            val m = o.optJSONObject("ms")
            SiteUsage(o.optString("day"), m?.keys()?.asSequence()?.associateWith { m.getLong(it) } ?: emptyMap())
        }
    }
}
