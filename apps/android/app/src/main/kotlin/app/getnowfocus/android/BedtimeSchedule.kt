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

    /**
     * Epoch millis of the next upcoming wind-down-or-wake boundary. Considers
     * yesterday's window too, not just today's and tomorrow's: just after
     * midnight, "today's wake" is the END of YESTERDAY's window
     * ([windowFor] for yesterday), not something [windowFor] for today or
     * tomorrow ever produces. Missing that case was an actual bug this
     * function's tests exist to catch again - the boundary alarm would skip
     * the morning wake entirely and jump straight to the next wind-down,
     * leaving quiet-notifications stuck on all day.
     */
    fun nextBoundary(settings: BedtimeSettings, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today, today.plusDays(1))
            .flatMap { day -> windowFor(settings, day, zone).let { listOf(it.first, it.last + 1) } }
            .filter { it > now }
            .min()
    }

    /** Epoch millis of the next upcoming sleep-time trigger: today's if still ahead, otherwise tomorrow's. */
    fun nextSleepTrigger(settings: BedtimeSettings, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todaySleep = today.atStartOfDay(zone).plusMinutes(settings.sleepMinute.toLong()).toInstant().toEpochMilli()
        if (todaySleep > now) return todaySleep
        return today.plusDays(1).atStartOfDay(zone).plusMinutes(settings.sleepMinute.toLong()).toInstant().toEpochMilli()
    }

    /**
     * The interruption-filter action to take right now, given the live
     * filter state. Callable from more than just the twice-daily boundary
     * alarm - also from settings save, app init, and boot - so disabling
     * Bedtime or its quiet-notifications toggle mid-window restores
     * immediately instead of staying stuck quiet until the next alarm.
     * Never touches a filter this decision wouldn't itself have set: it
     * only ever restores away from PRIORITY, never away from anything else,
     * so a user's own independent DND choice is left alone.
     */
    fun decideQuietFilter(settings: BedtimeSettings, now: Long, zone: ZoneId, currentFilterIsPriority: Boolean): QuietDecision {
        val shouldBeQuiet = settings.enabled && settings.quietNotifications && isQuietTimeNow(settings, now, zone)
        return when {
            shouldBeQuiet && !currentFilterIsPriority -> QuietDecision.SET_PRIORITY
            !shouldBeQuiet && currentFilterIsPriority -> QuietDecision.RESTORE_ALL
            else -> QuietDecision.NONE
        }
    }
}

enum class QuietDecision { SET_PRIORITY, RESTORE_ALL, NONE }
