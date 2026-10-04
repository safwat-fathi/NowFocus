package app.getnowfocus.android.sync

import app.getnowfocus.android.BlockPolicy
import app.getnowfocus.android.DomainValidation
import app.getnowfocus.android.EnforcementMode
import app.getnowfocus.android.FocusSession
import app.getnowfocus.android.FocusSessionStatus
import app.getnowfocus.android.PartialRule
import app.getnowfocus.android.PolicyMode
import app.getnowfocus.android.SessionType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** A session some device pushed, reduced to what this phone needs to enforce it. */
data class RemoteSession(
    val id: String,
    val policyId: String,
    val status: String,
    val mode: EnforcementMode,
    val startAt: Long,
    val endAt: Long,
    val domains: Set<String>,
    val packages: Set<String>,
    val partial: Set<PartialRule>,
    /** What the originating device says the list means. A whitelist's [domains] and [packages] are empty on the wire. */
    val policyMode: PolicyMode,
    val startedOn: String?,
    /** The server's JSON for it. A status change is pushed by merging into this, so another device's extras survive. */
    val raw: JSONObject,
)

/** What to do about a pulled session. */
sealed interface SessionDecision {
    /** Start enforcing it here. */
    data object Join : SessionDecision
    /** Worth joining, but a session is already running here: keep it and join when this one ends. */
    data object Defer : SessionDecision
    /** The session running here was cancelled elsewhere. */
    data object EndLocal : SessionDecision
    /** The session running here was extended elsewhere. */
    data class Extend(val endAt: Long) : SessionDecision
    data object Ignore : SessionDecision
}

/**
 * Cross-device sessions (services/api/WIRE_FORMAT.md section 6), as pure functions. Only user-started focus
 * sessions travel: Bedtime and schedules run on each device from its own settings.
 *
 * Joining enforces on this phone something another device decided, so it is bounded: a day at most (the
 * server enforces the same cap), never already over, never from a device the user switched off.
 */
object SessionWire {
    const val TYPE = "session"
    const val MAX_MS = 24 * 3_600_000L

    private fun time(iso: String?): Long? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    fun parse(r: ServerRecord): RemoteSession? {
        if (r.type != TYPE || r.deleted) return null
        val o = runCatching { JSONObject(r.dataJson) }.getOrNull() ?: return null
        if (o.s("sessionType") != "focus" || (o.s("source") ?: "user") != "user") return null
        val mode = when (o.s("enforcementMode")) {
            "normal" -> EnforcementMode.NORMAL; "strict" -> EnforcementMode.STRICT; "locked" -> EnforcementMode.LOCKED
            else -> return null
        }
        val start = time(o.s("startAt")) ?: return null
        val end = time(o.s("endAt")) ?: return null
        val snap = o.obj("policySnapshot")
        return RemoteSession(
            id = r.id.lowercase(),
            policyId = (o.s("policyId") ?: return null).lowercase(),
            status = o.s("status") ?: return null,
            mode = mode, startAt = start, endAt = end,
            // Validated like user input: these reach a DNS filter.
            domains = snap?.arr("domainRules")?.objects().orEmpty().filter { it.b("enabled", true) }
                .mapNotNull { it.s("domain")?.let(DomainValidation::normalize) }.toSet(),
            packages = snap?.arr("applicationRules")?.objects().orEmpty().filter { it.s("platform") == PolicyWire.PLATFORM && it.b("enabled", true) }
                .mapNotNull { it.s("nativeIdentifier") }.toSet(),
            partial = BlockPolicy.partialFromNames(snap?.arr("partial")?.items().orEmpty().filterIsInstance<String>()),
            policyMode = if (snap?.s("mode") == "allowlist") PolicyMode.ALLOWLIST else PolicyMode.BLOCKLIST,
            startedOn = o.s("startedOn"),
            raw = o,
        )
    }

    fun decide(remote: RemoteSession, local: FocusSession?, now: Long, joinEnabled: Boolean): SessionDecision {
        if (local != null && local.id == remote.id) {
            return when {
                remote.status == "cancelled" && local.status == FocusSessionStatus.ACTIVE -> SessionDecision.EndLocal
                remote.endAt > local.endAt && remote.endAt - local.startAt <= MAX_MS -> SessionDecision.Extend(remote.endAt)
                else -> SessionDecision.Ignore
            }
        }
        if (!joinEnabled) return SessionDecision.Ignore
        val running = remote.status == "active" || (remote.status == "scheduled" && remote.startAt <= now)
        if (!running || remote.endAt <= now || remote.endAt - remote.startAt > MAX_MS) return SessionDecision.Ignore
        val busy = local != null && local.status == FocusSessionStatus.ACTIVE && now < local.endAt
        return if (busy) SessionDecision.Defer else SessionDecision.Join
    }

    /**
     * Whether this phone can enforce [remote]. A whitelist is this phone's own allowed apps from the synced
     * policy (never the snapshot, which is empty for one), so it needs that policy here with an app on it:
     * otherwise it waits, the policy may arrive in a later pull, and never closes everything in the meantime.
     */
    fun enforceable(remote: RemoteSession, policies: List<BlockPolicy>): Boolean {
        val policy = policies.find { it.id == remote.policyId }
        val allowlist = remote.policyMode == PolicyMode.ALLOWLIST || policy?.mode == PolicyMode.ALLOWLIST
        return !allowlist || (policy?.mode == PolicyMode.ALLOWLIST && policy.enforcesHere)
    }

    fun wireStatus(s: FocusSessionStatus) = s.name.lowercase()

    /** The session as the server stores it. With [base] (a session joined from elsewhere) only what changes is touched. */
    fun toWire(s: FocusSession, deviceName: String, base: JSONObject?): JSONObject {
        val o = base?.copy() ?: JSONObject()
        o.put("id", s.id).put("policyId", s.policyId).put("sessionType", "focus")
        if (!o.has("source")) o.put("source", "user")
        o.put("status", wireStatus(s.status)).put("enforcementMode", s.enforcementMode.name.lowercase())
        o.put("startAt", iso(s.startAt)).put("endAt", iso(s.endAt))
        s.cancelledAt?.let { o.put("cancelledAt", iso(it)) }
        if (base == null) {
            o.put("startedOn", deviceName)
            // A whitelist's rule arrays are empty on purpose: a client that joins from the snapshot alone (Android 0.6
            // and earlier) would read the allowed apps as apps to block. Receivers that know the mode take their own
            // platform's apps from the synced policy instead (WIRE_FORMAT.md section 6).
            val allow = s.policyMode == PolicyMode.ALLOWLIST
            o.put("policySnapshot", JSONObject()
                .put("mode", s.policyMode.name.lowercase())
                .put("domainRules", JSONArray().also { a -> if (!allow) s.domains.sorted().forEach { a.put(JSONObject().put("domain", it).put("includeSubdomains", true).put("enabled", true)) } })
                .put("applicationRules", JSONArray().also { a -> if (!allow) s.packages.sorted().forEach { a.put(JSONObject().put("platform", PolicyWire.PLATFORM).put("nativeIdentifier", it).put("enabled", true)) } })
                .put("partial", JSONArray(s.partial.map { it.name })))
        }
        return o
    }

    /** What "already told the server" means: status, end and cancel time. */
    fun fingerprint(s: FocusSession) = "${wireStatus(s.status)}|${s.endAt}|${s.cancelledAt ?: 0}"

    /** Only these ever leave the device. */
    fun syncs(s: FocusSession) = s.sessionType == SessionType.FOCUS && s.origin != app.getnowfocus.android.SessionOrigin.SCHEDULE
}
