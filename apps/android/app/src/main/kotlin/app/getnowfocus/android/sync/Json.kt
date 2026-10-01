package app.getnowfocus.android.sync

import org.json.JSONArray
import org.json.JSONObject

// Null-safe accessors. Android's org.json and the JVM test library disagree on optString() for a JSON null
// ("null" vs ""), so everything here goes through isNull()/opt() only.
internal fun JSONObject.s(key: String): String? = if (isNull(key)) null else opt(key) as? String
internal fun JSONObject.b(key: String, default: Boolean): Boolean = if (isNull(key)) default else (opt(key) as? Boolean) ?: default
internal fun JSONObject.i(key: String): Int? = if (isNull(key)) null else (opt(key) as? Number)?.toInt()
internal fun JSONObject.l(key: String): Long? = if (isNull(key)) null else (opt(key) as? Number)?.toLong()
internal fun JSONObject.arr(key: String): JSONArray = if (isNull(key)) JSONArray() else (opt(key) as? JSONArray) ?: JSONArray()
internal fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else opt(key) as? JSONObject

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }
internal fun JSONArray.items(): List<Any?> = (0 until length()).map { opt(it) }

/** Deep copy, so a merge never mutates the last server record it started from. */
internal fun JSONObject.copy(): JSONObject = JSONObject(toString())
