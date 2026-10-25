package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A one-off session the user schedules for a specific future moment - "Strict, Friday 6pm, 2 hours" -
 * distinct from a recurring [Schedule] (a weekly day/time pattern) and from [FocusSessionStatus.SCHEDULED]
 * (which already means something else: a session a recurring Schedule just auto-started). Lives in its
 * own list, like Schedule and CheatDay, because SessionRepository's current-session slot holds only one
 * session and that one must already be running, not merely planned.
 */
data class PlannedSession(
    val id: String = UUID.randomUUID().toString(),
    val policyId: String,
    val startAt: Long,
    val endAt: Long,
    val mode: EnforcementMode = EnforcementMode.NORMAL,
    val createdAt: Long,
) {
    companion object {
        fun listToJson(list: List<PlannedSession>): String = JSONArray().apply {
            list.forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("policyId", s.policyId)
                    .put("startAt", s.startAt).put("endAt", s.endAt)
                    .put("mode", s.mode.name).put("createdAt", s.createdAt))
            }
        }.toString()

        fun listFromJson(json: String): List<PlannedSession> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PlannedSession(
                    id = o.getString("id"), policyId = o.getString("policyId"),
                    startAt = o.getLong("startAt"), endAt = o.getLong("endAt"),
                    mode = EnforcementMode.valueOf(o.getString("mode")), createdAt = o.getLong("createdAt"),
                )
            }
        }
    }
}

object PlannedSessions {
    /** At least this far ahead, same lead time as a Cheat Day: a plan, not a way to dodge the current block. */
    const val LEAD_MS = 10 * 60_000L

    fun canSchedule(now: Long, startAt: Long): Boolean = startAt - now >= LEAD_MS

    /** The earliest one due to start (startAt <= now), if any; arms and starts exactly this one. */
    fun due(list: List<PlannedSession>, now: Long): PlannedSession? = list.filter { it.startAt <= now }.minByOrNull { it.startAt }

    /** The soonest future start, for arming the alarm. */
    fun nextStart(list: List<PlannedSession>, now: Long): Long? = list.map { it.startAt }.filter { it > now }.minOrNull()
}
