package app.getnowfocus.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A whole local day on which sessions, Bedtime, schedules and limits don't block. The Commitment Shield still does. */
data class CheatDay(val startAt: Long, val endAt: Long, val createdAt: Long) {
    fun isActive(now: Long) = now in startAt until endAt
}

/**
 * Pre-committed, not impulsive: a cheat day must be set at least [LEAD_MS] ahead and at most one can fall
 * in any [GAP_DAYS]-day span, so it can't be reached for in the moment that blocking is hardest to bear.
 * The Shield promises "no early bypass", so a cheat day never touches it (see Enforcement).
 *
 * ponytail: uses the wall clock, unlike the Shield's boot-relative check. Winding the clock forward could
 * start one early, which is the user defeating their own tool; upgrade to the Shield's elapsedRealtime +
 * boot-count check if that ever matters.
 */
object CheatDays {
    const val LEAD_MS = 24 * 3_600_000L
    const val GAP_DAYS = 7L

    private fun dayStart(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun dateOf(millis: Long, zone: ZoneId) = LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone)

    /** [existing] is the latest cheat day, used or scheduled; a new one must be a week clear of it. */
    fun canSchedule(now: Long, dayStart: Long, existing: CheatDay?, zone: ZoneId): Boolean {
        if (dayStart - now < LEAD_MS) return false
        if (existing == null) return true
        return abs(ChronoUnit.DAYS.between(dateOf(existing.startAt, zone), dateOf(dayStart, zone))) >= GAP_DAYS
    }

    /** Start-of-day instants you can pick, soonest first. */
    fun options(now: Long, zone: ZoneId, existing: CheatDay?, count: Int = 14): List<Long> {
        val today = dateOf(now, zone)
        return (1L..count + 2L).map { dayStart(today.plusDays(it), zone) }
            .filter { canSchedule(now, it, existing, zone) }
            .take(count)
    }

    fun forDay(dayStart: Long, now: Long, zone: ZoneId) =
        CheatDay(dayStart, dayStart(dateOf(dayStart, zone).plusDays(1), zone), now)

    /** Cancelling a day that hasn't begun frees the week; ending a live one early keeps it counted. */
    fun cancel(cheat: CheatDay, now: Long): CheatDay? = when {
        now < cheat.startAt -> null
        cheat.isActive(now) -> cheat.copy(endAt = now)
        else -> cheat
    }
}
