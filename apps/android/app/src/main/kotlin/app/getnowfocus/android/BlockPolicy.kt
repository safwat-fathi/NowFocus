package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppRule(val packageName: String, val label: String)

/** Mirrors NowFocusCore/BlockPolicy.swift. Domains always include subdomains. */
data class BlockPolicy(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domains: List<String> = emptyList(),
    val apps: List<AppRule> = emptyList(),
) {
    companion object {
        val DEFAULT = BlockPolicy(
            name = "Deep Work",
            domains = listOf(
                "youtube.com", "twitter.com", "x.com", "reddit.com",
                "instagram.com", "tiktok.com", "facebook.com",
            ),
        )

        fun listToJson(policies: List<BlockPolicy>): String = JSONArray().apply {
            policies.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("domains", JSONArray(p.domains))
                    put("apps", JSONArray().apply {
                        p.apps.forEach { put(JSONObject().put("packageName", it.packageName).put("label", it.label)) }
                    })
                })
            }
        }.toString()

        fun listFromJson(json: String): List<BlockPolicy> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val domains = o.getJSONArray("domains")
                val apps = o.getJSONArray("apps")
                BlockPolicy(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    domains = (0 until domains.length()).map { domains.getString(it) },
                    apps = (0 until apps.length()).map {
                        val a = apps.getJSONObject(it)
                        AppRule(a.getString("packageName"), a.getString("label"))
                    },
                )
            }
        }
    }
}
