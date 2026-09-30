package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Same three levels and short labels as macOS's GoalPriority. */
enum class GoalPriority(val label: String) { HIGH("High"), MEDIUM("Med"), LOW("Low") }

/** Something you told NowFocus you're focusing for: shown when you're tempted to quit, or blocked. */
data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val priority: GoalPriority = GoalPriority.HIGH,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        // Same org.json approach as Person; not JVM-unit-testable (org.json is stubbed there).
        fun listToJson(goals: List<Goal>): String = JSONArray().apply {
            goals.forEach { g ->
                put(JSONObject().put("id", g.id).put("text", g.text).put("priority", g.priority.name).put("createdAt", g.createdAt))
            }
        }.toString()

        fun listFromJson(json: String): List<Goal> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Goal(
                    id = o.getString("id"),
                    text = o.getString("text"),
                    // A priority from a newer build falls back to the default rather than losing the goal.
                    priority = GoalPriority.entries.find { it.name == o.optString("priority") } ?: GoalPriority.HIGH,
                    createdAt = o.getLong("createdAt"),
                )
            }
        }
    }
}

object Goals {
    /** A random high-priority goal, falling back to any goal (macOS DatabaseManager.fetchRandomGoal). */
    fun pick(goals: List<Goal>, random: Random = Random.Default): Goal? =
        goals.filter { it.priority == GoalPriority.HIGH }.ifEmpty { goals }.randomOrNull(random)
}
