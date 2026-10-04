package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A recurring focus session, e.g. "Work: Mon-Fri 9:00-12:00 on Deep Work". [days] are the days the window
 * STARTS on; one that ends at or before its start ends the next day, like Bedtime's.
 */
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val policyId: String,
    val mode: EnforcementMode = EnforcementMode.NORMAL,
    val enabled: Boolean = true,
) {
    companion object {
        fun listToJson(list: List<Schedule>): String = JSONArray().apply {
            list.forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("name", s.name)
                    .put("days", JSONArray(s.days.map { it.value }))
                    .put("start", s.startMinute).put("end", s.endMinute)
                    .put("policyId", s.policyId).put("mode", s.mode.name).put("enabled", s.enabled))
            }
        }.toString()

        fun listFromJson(json: String): List<Schedule> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val days = o.getJSONArray("days")
                Schedule(
                    id = o.getString("id"), name = o.getString("name"),
                    days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                    startMinute = o.getInt("start"), endMinute = o.getInt("end"),
                    policyId = o.getString("policyId"),
                    mode = EnforcementMode.valueOf(o.getString("mode")),
                    enabled = o.getBoolean("enabled"),
                )
            }
        }

        /** scheduleId to the start of the last window of it that was begun, so ending one early doesn't restart it. */
        fun runsToJson(runs: Map<String, Long>): String = JSONObject(runs).toString()
        fun runsFromJson(json: String): Map<String, Long> =
            JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
    }
}

/** One occurrence of a schedule: [start] until [end] (end exclusive). */
data class Occurrence(val schedule: Schedule, val start: Long, val end: Long)

object Schedules {
    private fun occurrenceOn(s: Schedule, date: LocalDate, zone: ZoneId): Occurrence? {
        if (date.dayOfWeek !in s.days) return null
        val start = date.atStartOfDay(zone).plusMinutes(s.startMinute.toLong())
        var end = date.atStartOfDay(zone).plusMinutes(s.endMinute.toLong())
        if (s.endMinute <= s.startMinute) end = end.plusDays(1)
        return Occurrence(s, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    private fun today(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()   // LocalDate.ofInstant needs API 34

    /** The enabled occurrence running at [now] (today's or one that crossed midnight), the longest-lasting if several. */
    fun current(schedules: List<Schedule>, now: Long, zone: ZoneId): Occurrence? {
        val t = today(now, zone)
        return schedules.filter { it.enabled }
            .flatMap { s -> listOf(t, t.minusDays(1)).mapNotNull { occurrenceOn(s, it, zone) } }
            .filter { now >= it.start && now < it.end }
            .maxByOrNull { it.end }
    }

    /** The soonest start after [now], within the next eight days. */
    fun nextStart(schedules: List<Schedule>, now: Long, zone: ZoneId): Long? {
        val t = today(now, zone)
        return schedules.filter { it.enabled }
            .flatMap { s -> (0L..8L).mapNotNull { occurrenceOn(s, t.plusDays(it), zone) } }
            .map { it.start }.filter { it > now }.minOrNull()
    }

    /** "Mon-Fri", "Every day", "Sat, Sun", "Mon, Wed, Fri". */
    fun daysLabel(days: Set<DayOfWeek>): UiText {
        val sorted = days.sorted()
        val names = sorted.map { it.shortName() }
        return when {
            sorted.size == 7 -> uiText(R.string.days_every_day)
            sorted.size > 2 && sorted.zipWithNext().all { (a, b) -> b.value - a.value == 1 } -> uiText(R.string.days_range, names.first(), names.last())
            else -> UiText.Joined(names, R.string.sep_comma)
        }
    }
}

/** "Mon", "Tue", ... in the app language. */
fun DayOfWeek.shortName(): UiText = uiText(
    when (this) {
        DayOfWeek.MONDAY -> R.string.day_mon
        DayOfWeek.TUESDAY -> R.string.day_tue
        DayOfWeek.WEDNESDAY -> R.string.day_wed
        DayOfWeek.THURSDAY -> R.string.day_thu
        DayOfWeek.FRIDAY -> R.string.day_fri
        DayOfWeek.SATURDAY -> R.string.day_sat
        DayOfWeek.SUNDAY -> R.string.day_sun
    },
)
