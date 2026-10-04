package app.getnowfocus.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppRule(val packageName: String, val label: String)

/**
 * What a policy's list means. A blocklist closes what it names; an allowlist closes everything else (apps
 * only: its domains are never enforced, a DNS filter can't say "everything except"). Chosen when the policy is
 * created and never changed, since flipping it would turn "block these" into "allow only these".
 */
enum class PolicyMode { BLOCKLIST, ALLOWLIST }

/** Mirrors NowFocusCore/BlockPolicy.swift. Domains always include subdomains. */
data class BlockPolicy(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domains: List<String> = emptyList(),
    val apps: List<AppRule> = emptyList(),
    val partial: Set<PartialRule> = emptySet(),
    val mode: PolicyMode = PolicyMode.BLOCKLIST,
) {
    /**
     * False for an allowlist with no app on this phone (its apps may all be another platform's: the list syncs).
     * Such a session would close everything, so it never starts or joins, and enforces nothing if one slips through.
     */
    val enforcesHere: Boolean get() = mode == PolicyMode.BLOCKLIST || apps.isNotEmpty()

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
                    put("partial", JSONArray(p.partial.map { it.name }))
                    put("mode", p.mode.name)
                })
            }
        }.toString()

        /** Unknown names (a rule from a newer build) are dropped rather than failing the whole policy list. */
        fun partialFromNames(names: Iterable<String>): Set<PartialRule> =
            names.mapNotNull { n -> PartialRule.entries.find { it.name == n } }.toSet()

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
                    // Absent in policies saved before partial blocking existed.
                    partial = o.optJSONArray("partial")?.let { a -> partialFromNames((0 until a.length()).map { a.getString(it) }) } ?: emptySet(),
                    // Absent in policies saved before whitelist mode: those are blocklists.
                    mode = PolicyMode.entries.find { it.name == o.optString("mode") } ?: PolicyMode.BLOCKLIST,
                )
            }
        }
    }
}
