package app.getnowfocus.android.sync

import app.getnowfocus.android.AppRule
import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import app.getnowfocus.android.DomainValidation
import app.getnowfocus.android.PartialRule
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Android's lossy [BlockPolicy] <-> the server's policy `data` (services/api/WIRE_FORMAT.md, section 4).
 *
 * The one rule that matters: policies are last-write-wins as a whole, so an edit must be MERGED into the last
 * server JSON, never rebuilt from the Android model. Android owns the name, the domains, the apps with
 * `platform: "android"` and the `partial` names it knows; every other field and rule is carried over untouched.
 */
object PolicyWire {
    const val PLATFORM = "android"
    private val KNOWN_PARTIAL = PartialRule.entries.map { it.name }.toSet()

    /** Android has no allowlist mode. Applying an allowlist's domains as blocks would do the opposite of what the user set. */
    fun supported(raw: JSONObject): Boolean = raw.s("mode") != "allowlist"

    fun toLocal(raw: JSONObject): BlockPolicy {
        val domains = raw.arr("domainRules").objects().filter { it.b("enabled", true) }.mapNotNull { it.s("domain") }
        val apps = raw.arr("applicationRules").objects()
            .filter { it.s("platform") == PLATFORM && it.b("enabled", true) }
            .mapNotNull { r -> r.s("nativeIdentifier")?.let { AppRule(it, r.s("displayName") ?: it) } }
        val partial = raw.arr("partial").items().filterIsInstance<String>().filter { it in KNOWN_PARTIAL }
        return BlockPolicy(
            id = raw.s("id")!!.lowercase(),
            name = raw.s("name") ?: "",
            domains = domains,
            apps = apps,
            partial = BlockPolicy.partialFromNames(partial),
        )
    }

    /** `raw` is the last server record (null for a brand-new profile). Never mutates it. */
    fun merge(local: BlockPolicy, raw: JSONObject?): JSONObject {
        val out = raw?.copy() ?: JSONObject().put("mode", "blocklist")
        out.put("id", local.id.lowercase())
        out.put("name", local.name)
        out.put("domainRules", mergeDomains(local.domains, out.arr("domainRules").objects()))
        out.put("applicationRules", mergeApps(local.apps, out.arr("applicationRules").objects()))
        mergePartial(local.partial, out)
        return out
    }

    private fun mergeDomains(domains: List<String>, existing: List<JSONObject>): JSONArray {
        val wanted = LinkedHashSet<String>().also { set -> domains.forEach { d -> DomainValidation.normalize(d)?.let(set::add) } }
        val seen = HashSet<String>()
        val rules = JSONArray()
        for (r in existing) {
            val key = r.s("domain")?.let(::domainKey)
            if (key == null) { rules.put(r); continue }              // not a rule we can read: keep it verbatim
            val enabled = r.b("enabled", true)
            when {
                key in wanted -> { if (!enabled) r.put("enabled", true); rules.put(r); seen += key }
                !enabled -> rules.put(r)                              // disabled elsewhere: not ours to drop
                // else: enabled on the server but gone locally -> the user removed it
            }
        }
        for (d in wanted) if (d !in seen) rules.put(JSONObject().put("id", UUID.randomUUID().toString()).put("domain", d).put("includeSubdomains", true).put("enabled", true))
        return rules
    }

    private fun mergeApps(apps: List<AppRule>, existing: List<JSONObject>): JSONArray {
        val wanted = LinkedHashMap<String, String>().also { m -> apps.forEach { m.putIfAbsent(it.packageName, it.label) } }
        val seen = HashSet<String>()
        val rules = JSONArray()
        for (r in existing) {
            if (r.s("platform") != PLATFORM) { rules.put(r); continue }   // another platform's app rule: never ours to touch
            val key = r.s("nativeIdentifier")
            if (key == null) { rules.put(r); continue }
            val enabled = r.b("enabled", true)
            when {
                key in wanted -> {
                    if (!enabled) r.put("enabled", true)
                    if (r.s("displayName") != wanted[key]) r.put("displayName", wanted[key])
                    rules.put(r); seen += key
                }
                !enabled -> rules.put(r)
            }
        }
        for ((pkg, label) in wanted) if (pkg !in seen) {
            rules.put(JSONObject().put("id", UUID.randomUUID().toString()).put("platform", PLATFORM).put("nativeIdentifier", pkg).put("displayName", label).put("enabled", true))
        }
        return rules
    }

    private fun mergePartial(partial: Set<PartialRule>, out: JSONObject) {
        // Entries this build doesn't know (a newer name, or a non-string) belong to someone else: keep them.
        val others = out.arr("partial").items().filter { !(it is String && it in KNOWN_PARTIAL) }
        val ours = PartialRule.entries.filter { it in partial }.map { it.name }
        if (others.isEmpty() && ours.isEmpty() && out.isNull("partial")) return
        out.put("partial", JSONArray((others + ours)))
    }

    /** Same meaning, ignoring order and the spelling of domains. */
    fun same(a: BlockPolicy, b: BlockPolicy): Boolean =
        a.id.lowercase() == b.id.lowercase() && a.name == b.name &&
            a.domains.map(::domainKey).toSet() == b.domains.map(::domainKey).toSet() &&
            a.apps.associate { it.packageName to it.label } == b.apps.associate { it.packageName to it.label } &&
            a.partial == b.partial

    /** A stable string for the content of [p] (order and domain spelling don't matter). */
    internal fun canonical(p: BlockPolicy): String = listOf(
        p.id.lowercase(), p.name, p.domains.map(::domainKey).toSortedSet().joinToString(","),
        p.apps.map { it.packageName + "=" + it.label }.sorted().joinToString(","), p.partial.map { it.name }.sorted().joinToString(","),
    ).joinToString("\u0001")

    /** The key two spellings of one domain share; unparseable input falls back to trimmed lowercase. */
    internal fun domainKey(d: String): String = DomainValidation.normalize(d) ?: d.trim().lowercase()
}

/** Bedtime is a singleton (`bedtime_settings`, id `default`). `quietNotifications` is Android-only and travels as an extra field. */
object BedtimeWire {
    val DEFAULT = BedtimeSettings()

    fun toLocal(raw: JSONObject): BedtimeSettings = BedtimeSettings(
        windDownMinute = raw.i("windDownMinute") ?: DEFAULT.windDownMinute,
        sleepMinute = raw.i("sleepMinute") ?: DEFAULT.sleepMinute,
        wakeMinute = raw.i("wakeMinute") ?: DEFAULT.wakeMinute,
        enabled = raw.b("enabled", DEFAULT.enabled),
        quietNotifications = raw.b("quietNotifications", DEFAULT.quietNotifications),
        lockAtSleep = raw.b("lockAtSleep", DEFAULT.lockAtSleep),
        policyId = raw.s("policyId")?.lowercase(),
    )

    fun merge(local: BedtimeSettings, raw: JSONObject?): JSONObject {
        val out = raw?.copy() ?: JSONObject()
        out.put("enabled", local.enabled)
        out.put("windDownMinute", local.windDownMinute)
        out.put("sleepMinute", local.sleepMinute)
        out.put("wakeMinute", local.wakeMinute)
        out.put("lockAtSleep", local.lockAtSleep)
        out.put("quietNotifications", local.quietNotifications)
        out.put("policyId", local.policyId?.lowercase() ?: JSONObject.NULL)
        return out
    }

    internal fun canonical(b: BedtimeSettings): String = b.copy(policyId = b.policyId?.lowercase()).toString()

    fun same(a: BedtimeSettings, b: BedtimeSettings): Boolean = a.copy(policyId = a.policyId?.lowercase()) == b.copy(policyId = b.policyId?.lowercase())
}
