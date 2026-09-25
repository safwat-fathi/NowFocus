package app.getnowfocus.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ships with 2 of the mockup's 4 toggles. Greyscale and "close the feeds"
 * are dropped: no public API gives a normal app system-wide greyscale
 * without a permission it can't hold, and feed-level blocking isn't visible
 * to DNS blocking or foreground-app detection either - same reasoning as
 * the Editor screen's dropped "Feeds only" section.
 */
data class BedtimeSettings(
    val windDownMinute: Int = 22 * 60,
    val sleepMinute: Int = 23 * 60,
    val wakeMinute: Int = 7 * 60,
    val enabled: Boolean = false,
    val quietNotifications: Boolean = true,
    // One-shot GLOBAL_ACTION_LOCK_SCREEN at sleep time - needs API 28, so this
    // is forced false below that (see FocusAccessibilityService/BedtimeScreen).
    val lockAtSleep: Boolean = true,
)

/**
 * Pure scheduling math - no AlarmManager/Context here, so it's plain-JVM
 * testable. Bedtime windows frequently cross midnight; every boundary here
 * is resolved to an absolute epoch-millis instant before comparison, per
 * native_tech_stack_spec.md's explicit note on why that matters (minutes-
 * since-midnight comparisons break across the date line).
 */
object BedtimeSchedule {

    /** Wind-down-to-wake window starting on [referenceDate], as an epoch-millis range (end exclusive). */
    fun windowFor(settings: BedtimeSettings, referenceDate: LocalDate, zone: ZoneId): LongRange {
        val start = referenceDate.atStartOfDay(zone).plusMinutes(settings.windDownMinute.toLong())
        var end = referenceDate.atStartOfDay(zone).plusMinutes(settings.wakeMinute.toLong())
        if (settings.wakeMinute <= settings.windDownMinute) end = end.plusDays(1)
        return start.toInstant().toEpochMilli() until end.toInstant().toEpochMilli()
    }

    /** True if [now] falls in tonight's window (not yet started) or last night's (still running past midnight). */
    fun isQuietTimeNow(settings: BedtimeSettings, now: Long, zone: ZoneId): Boolean {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return now in windowFor(settings, today, zone) || now in windowFor(settings, today.minusDays(1), zone)
    }

    /** Epoch millis of the next upcoming sleep-time trigger: today's if still ahead, otherwise tomorrow's. */
    fun nextSleepTrigger(settings: BedtimeSettings, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todaySleep = today.atStartOfDay(zone).plusMinutes(settings.sleepMinute.toLong()).toInstant().toEpochMilli()
        if (todaySleep > now) return todaySleep
        return today.plusDays(1).atStartOfDay(zone).plusMinutes(settings.sleepMinute.toLong()).toInstant().toEpochMilli()
    }
}
