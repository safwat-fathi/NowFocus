package app.getnowfocus.android

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * "30 minutes of Instagram a day": past it, the app is blocked until local midnight. A limit can be
 * tightened at once, but loosening it (more minutes, or removing it, [pendingMinutes] = 0) only takes
 * effect at the next midnight: the moment you want a limit gone is the moment it has just stopped you.
 */
data class AppLimit(
    val packageName: String,
    val label: String,
    val minutesPerDay: Int,
    val pendingMinutes: Int? = null,
    val pendingFrom: Long = 0L,
) {
    /** The minutes in force at [now]; 0 means no limit. */
    fun minutesAt(now: Long): Int = if (pendingMinutes != null && now >= pendingFrom) pendingMinutes else minutesPerDay
    fun limitMillisAt(now: Long): Long = minutesAt(now) * 60_000L

    /** Folds a pending change that has come due into the limit itself. Null once it is a removed limit. */
    fun settled(now: Long): AppLimit? =
        if (pendingMinutes != null && now >= pendingFrom) (if (pendingMinutes == 0) null else copy(minutesPerDay = pendingMinutes, pendingMinutes = null, pendingFrom = 0L)) else this

    /** [minutes] 0 removes it. Tighter applies now; looser waits for midnight. */
    fun withMinutes(minutes: Int, now: Long, zone: ZoneId): AppLimit {
        val current = minutesAt(now)
        val looser = minutes == 0 || (current != 0 && minutes > current)
        return if (looser) copy(pendingMinutes = minutes, pendingFrom = UsageMath.nextMidnight(now, zone))
        else copy(minutesPerDay = minutes, pendingMinutes = null, pendingFrom = 0L)
    }

    companion object {
        val MINUTE_CHOICES = listOf(15, 30, 45, 60, 90, 120)

        fun listToJson(list: List<AppLimit>): String = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("pkg", it.packageName).put("label", it.label).put("min", it.minutesPerDay)
                    .apply { if (it.pendingMinutes != null) put("pending", it.pendingMinutes).put("from", it.pendingFrom) })
            }
        }.toString()

        fun listFromJson(json: String): List<AppLimit> {
            val a = JSONArray(json)
            return (0 until a.length()).map {
                a.getJSONObject(it).let { o ->
                    AppLimit(o.getString("pkg"), o.getString("label"), o.getInt("min"), if (o.has("pending")) o.getInt("pending") else null, o.optLong("from", 0L))
                }
            }
        }
    }
}

/** One foreground-state change of an app, as reported by [UsageEvents]. */
data class UsageEvt(val packageName: String, val resumed: Boolean, val timestamp: Long)

/** Pure arithmetic over usage events, so it is plain-JVM testable. */
object UsageMath {
    /**
     * Milliseconds [pkg] was in front between [from] and [now], from its resume/pause events in time order.
     * Counts activities rather than flags: an app can resume its next screen before the old one reports
     * its pause, and that overlap must not be double-counted or end the interval early. An app still in
     * front at [now] counts up to [now]; one already in front at [from] (no resume event seen) counts from [from].
     */
    fun foregroundMillis(events: List<UsageEvt>, pkg: String, from: Long, now: Long): Long {
        var depth = 0
        var since = 0L
        var total = 0L
        for (e in events.filter { it.packageName == pkg }.sortedBy { it.timestamp }) {
            if (e.resumed) {
                if (depth == 0) since = maxOf(e.timestamp, from)
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0) total += (e.timestamp - since).coerceAtLeast(0)
            } else {
                // A pause with no resume seen: it was in front when the window opened.
                total += (e.timestamp - from).coerceAtLeast(0)
            }
        }
        if (depth > 0) total += (now - since).coerceAtLeast(0)
        return total
    }

    /** Local midnight that ends the day containing [now]: when a limit that was reached lifts. */
    fun nextMidnight(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

object UsageAccess {
    /** Usage access is a special-access setting, not a runtime permission: the user switches it on in Settings. */
    fun isGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}

object UsageReader {
    /** Foreground time today (since local midnight) per package in [packages]. Empty without usage access. */
    fun foregroundToday(context: Context, packages: Set<String>, now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (packages.isEmpty() || !UsageAccess.isGranted(context)) return emptyMap()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val from = HistoryStats.startOfDayMillis(now, ZoneId.systemDefault())
        val raw = manager.queryEvents(from, now)
        val events = ArrayList<UsageEvt>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg !in packages) continue
            // ACTIVITY_RESUMED/PAUSED (API 29+) share their values with the older MOVE_TO_FOREGROUND/BACKGROUND.
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> events.add(UsageEvt(pkg, true, e.timeStamp))
                UsageEvents.Event.ACTIVITY_PAUSED -> events.add(UsageEvt(pkg, false, e.timeStamp))
            }
        }
        return packages.associateWith { UsageMath.foregroundMillis(events, it, from, now) }
    }
}
