package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject

/** A [DURATION_MS] exception for one blocked app, inside one session. */
data class AppPass(val packageName: String, val until: Long)

/**
 * "I need WhatsApp for a client" without ending the session: a pass opens one blocked app for five
 * minutes, twice per session. Normal and Strict only. A Locked session has no exit by definition (and
 * Bedtime runs as Locked), and the Commitment Shield never gets one. A pass is not a cancel, so it
 * never goes through [SessionEngine.canCancel]; this is its own gate, in one place.
 */
object Passes {
    const val DURATION_MS = 5 * 60_000L
    const val MAX_PER_SESSION = 2

    fun passesLeft(session: FocusSession): Int =
        if (session.enforcementMode == EnforcementMode.LOCKED || session.sessionType == SessionType.BEDTIME_WINDDOWN) 0
        else (MAX_PER_SESSION - session.passes.size).coerceAtLeast(0)

    /** The session with a pass for [pkg] added, or null when none may be given. Ends no later than the session. */
    fun grant(session: FocusSession, pkg: String, now: Long): FocusSession? {
        // Only an app this session closes can be passed: a listed one in a blocklist, an unlisted one in an allowlist.
        val closed = (pkg in session.packages) != (session.policyMode == PolicyMode.ALLOWLIST)
        if (!SessionEngine.isActive(session, now) || passesLeft(session) == 0 || !closed) return null
        if (session.passes.any { it.packageName == pkg && it.until > now }) return null // already open
        return session.copy(passes = session.passes + AppPass(pkg, minOf(now + DURATION_MS, session.endAt)))
    }

    fun toJson(passes: List<AppPass>): String =
        JSONArray().apply { passes.forEach { put(JSONObject().put("pkg", it.packageName).put("until", it.until)) } }.toString()

    fun fromJson(json: String): List<AppPass> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> AppPass(o.getString("pkg"), o.getLong("until")) } }
    }
}

/** Today's passes on daily-limit blocks, keyed by the limit (an app's package, or "site:<domain>"). */
data class LimitPassState(val day: String = "", val passes: List<AppPass> = emptyList())

/**
 * The same five-minute pass for a used-up daily limit: [Passes.MAX_PER_SESSION] per limit per local day. It is
 * its own allowance, separate from a session's, and resets at midnight along with the limit itself.
 */
object LimitPasses {
    private fun today(state: LimitPassState, day: String) = if (state.day == day) state.passes else emptyList()

    fun passesLeft(state: LimitPassState, key: String, day: String): Int =
        (Passes.MAX_PER_SESSION - today(state, day).count { it.packageName == key }).coerceAtLeast(0)

    /** When the open pass for [key] ends, or 0 if there is none. */
    fun activeUntil(state: LimitPassState, key: String, now: Long, day: String): Long =
        today(state, day).filter { it.packageName == key && it.until > now }.maxOfOrNull { it.until } ?: 0L

    /** Minutes one pass lasts: five, plus a minute per day of streak, up to fifteen. The only place these numbers live. */
    fun passMinutes(streakDays: Int): Int = 5 + streakDays.coerceIn(0, 10)

    /** The state with a pass for [key] added, or null when none may be given (none left, or one is still open). */
    fun grant(state: LimitPassState, key: String, now: Long, day: String, minutes: Int = 5): LimitPassState? {
        if (passesLeft(state, key, day) == 0 || activeUntil(state, key, now, day) > now) return null
        return LimitPassState(day, today(state, day) + AppPass(key, now + minutes * 60_000L))
    }

    fun toJson(s: LimitPassState): String = JSONObject().put("day", s.day).put("passes", JSONArray(Passes.toJson(s.passes))).toString()

    fun fromJson(json: String): LimitPassState = JSONObject(json).let { LimitPassState(it.optString("day"), Passes.fromJson(it.optJSONArray("passes")?.toString() ?: "[]")) }
}
