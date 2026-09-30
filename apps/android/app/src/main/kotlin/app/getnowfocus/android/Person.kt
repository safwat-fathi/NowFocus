package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Someone you'd rather hear from than scroll. [lastTalkedAt] is what you told
 * onboarding plus taps on the block screen's Call/Text - not call or SMS
 * history, and a tap that never becomes a call still counts. Null means
 * "can't remember".
 */
data class Person(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastTalkedAt: Long? = null,
) {
    /** "3 days" / "3 weeks" / "2 months", or null when unknown or too recent to claim anything. */
    fun sinceLabel(now: Long): String? {
        val days = ((now - (lastTalkedAt ?: return null)) / DAY_MS).toInt()
        return when {
            days < 3 -> null
            days < 14 -> "$days days"
            days < 60 -> "${days / 7} weeks"
            else -> "${days / 30} months"
        }
    }

    companion object {
        const val MAX = 5

        // Same org.json approach as BlockPolicy; not JVM-unit-testable (org.json is stubbed there).
        fun listToJson(people: List<Person>): String = JSONArray().apply {
            people.forEach { p ->
                put(
                    JSONObject().put("id", p.id).put("name", p.name).put("phone", p.phone).put("addedAt", p.addedAt)
                        .put("lastTalkedAt", p.lastTalkedAt ?: JSONObject.NULL),
                )
            }
        }.toString()

        fun listFromJson(json: String): List<Person> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Person(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    phone = o.getString("phone"),
                    addedAt = o.getLong("addedAt"),
                    lastTalkedAt = if (o.isNull("lastTalkedAt")) null else o.getLong("lastTalkedAt"),
                )
            }
        }
    }
}

object PeopleRotation {
    /** Whoever you talked to longest ago; "can't remember" counts as oldest, ties go to who was added first. */
    fun next(people: List<Person>): Person? =
        people.minWithOrNull(compareBy({ it.lastTalkedAt ?: Long.MIN_VALUE }, { it.addedAt }))
}
