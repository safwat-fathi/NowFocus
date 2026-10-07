package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import org.json.JSONObject
import java.security.MessageDigest

/** Everything the sync layer reads and writes in one atomic step (one DataStore edit, see SessionRepository.syncTransaction). */
data class Local(val policies: List<BlockPolicy>, val bedtime: BedtimeSettings, val state: SyncState)

/** A server record as it arrives from pull, or as the "current" copy inside a stale push result. */
data class ServerRecord(val type: String, val id: String, val dataJson: String, val deleted: Boolean, val revision: Int, val updatedAt: Long)

/** One change to push. [dataJson] is null for a tombstone. [updatedAt] is when the user made the change. */
data class Outgoing(val type: String, val id: String, val updatedAt: Long, val dataJson: String?, val deleted: Boolean, val fingerprint: String)

data class PushOutcome(val type: String, val id: String, val status: String, val record: ServerRecord?, val code: String?)

data class Applied(val local: Local, val bedtimeChanged: Boolean = false, val rejected: Int = 0)

/**
 * The sync rules, as pure functions over [Local]. The engine does the network; this decides what the data means.
 *
 * Invariants worth knowing:
 *  - A record is "dirty" when it differs from the server copy we last saw (or never reached the server). Dirtiness
 *    is always recomputed from the data; [Meta.dirtyAt] only supplies the TIME of the user's last change.
 *  - Deletions are recorded where they happen ([stampPolicies]), never inferred from a profile being absent.
 *  - Conflicts use the server's own rule, last-write-wins on `updatedAt`.
 *  - Nothing here ever touches a running session or the Commitment Shield.
 */
object SyncLogic {
    const val POLICY = "policy"
    const val BEDTIME = "bedtime_settings"
    const val BEDTIME_ID = "default"
    private const val DELETED = "deleted"

    // ---------------------------------------------------------------- write-time bookkeeping

    /** Called inside the same edit that saved [new] over [old]. A no-op until the device has been linked to an account. */
    fun stampPolicies(state: SyncState, old: List<BlockPolicy>, new: List<BlockPolicy>, now: Long): SyncState {
        if (state.userId == null) return state
        val oldById = old.associateBy { it.id }
        val newIds = new.map { it.id }.toSet()
        var metas = state.policies
        for (p in new) {
            val prev = oldById[p.id]
            val m = metas[p.id]
            if (m != null && !m.imported) continue
            if (prev != null && PolicyWire.same(prev, p)) continue            // untouched by this write
            val base = m ?: Meta()
            val dirty = base.rawJson == null || !PolicyWire.same(p, PolicyWire.toLocal(base.raw!!))
            // Every change while dirty moves the timestamp: an offline edit at 10:05 must beat another device's edit at 10:02.
            metas = metas + (p.id to base.copy(deleted = false, dirtyAt = if (dirty) now else null, rejected = null))
        }
        for (o in old) if (o.id !in newIds) {
            val m = metas[o.id]
            metas = when {
                m == null || m.rawJson == null -> metas - o.id                 // never reached the server: nothing to tell it
                !m.imported -> metas
                else -> metas + (o.id to m.copy(deleted = true, dirtyAt = now, rejected = null))
            }
        }
        return state.copy(policies = metas)
    }

    fun stampBedtime(state: SyncState, old: BedtimeSettings, new: BedtimeSettings, now: Long): SyncState {
        if (state.userId == null || BedtimeWire.same(old, new)) return state
        val m = state.bedtime ?: Meta()
        return state.copy(bedtime = m.copy(dirtyAt = if (bedtimeDirty(new, m)) now else null, rejected = null))
    }

    // ---------------------------------------------------------------- linking

    /** Same account: resume. A different account (or none before): start clean so nothing of another account's cursor or raw data leaks in. */
    fun link(state: SyncState, userId: String): SyncState = if (state.userId == userId) state else SyncState(userId = userId)

    /** After sign-in, deleting the account: the server data is gone, local data stays, and the next link is a fresh merge. */
    fun unlink(): SyncState = SyncState()

    // ---------------------------------------------------------------- pull

    fun applyPulled(local: Local, records: List<ServerRecord>, cursor: Long): Applied {
        var cur = local
        var bedtimeChanged = false
        for (r in records) {
            when (r.type) {
                POLICY -> cur = applyPolicyRecord(cur, r)
                BEDTIME -> applyBedtimeRecord(cur, r).let { (next, changed) -> cur = next; bedtimeChanged = bedtimeChanged || changed }
                // session, shield_item, user_settings: not synced by this build yet; the cursor still moves past them.
            }
        }
        return Applied(cur.copy(state = cur.state.copy(cursor = maxOf(cursor, cur.state.cursor))), bedtimeChanged)
    }

    /**
     * Run once, after the first full pull following a link. [referenced] are policy ids something still points at
     * (bedtime's profile, the current session): those are never dropped.
     */
    fun finishInitialPull(local: Local, referenced: Set<String>): Local {
        var policies = local.policies
        val serverHasProfiles = local.state.policies.values.any { it.rawJson != null && !it.deleted }
        if (serverHasProfiles) {
            // This phone's untouched starter profile would only duplicate the account's own.
            policies = policies.filterNot { p -> local.state.policies[p.id] == null && isUntouchedSeed(p) && p.id !in referenced }
        }
        return local.copy(policies = policies, state = local.state.copy(initialPullDone = true))
    }

    fun isUntouchedSeed(p: BlockPolicy): Boolean = PolicyWire.same(p.copy(id = BlockPolicy.DEFAULT.id), BlockPolicy.DEFAULT)

    private fun applyPolicyRecord(local: Local, r: ServerRecord): Local {
        val id = r.id.lowercase()
        val m = local.state.policies[id]
        val mine = local.policies.find { it.id == id }
        val set = { metas: Map<String, Meta>, policies: List<BlockPolicy> -> local.copy(policies = policies, state = local.state.copy(policies = metas)) }

        if (r.deleted) {
            return when {
                m != null && m.deleted -> set(local.state.policies - id, local.policies)   // both sides agree it's gone
                mine == null -> set(local.state.policies - id, local.policies)
                // No bookkeeping for a profile we hold: we can't tell whether it was edited here, so keep it (it goes up as new).
                m == null -> local
                policyDirty(mine, m) && m?.rawJson != null && (m.dirtyAt ?: 0) > r.updatedAt ->
                    // Edited here after it was deleted there: the edit wins and is pushed as a new record.
                    set(local.state.policies + (id to m.copy(rawJson = null, revision = r.revision, deleted = false)), local.policies)
                else -> set(local.state.policies - id, local.policies.filterNot { it.id == id })
            }
        }

        val raw = JSONObject(r.dataJson)
        if (!PolicyWire.supported(raw)) {
            // Keep the record (so it's never mistaken for a deletion) but don't import or enforce it.
            return set(local.state.policies + (id to Meta(rawJson = r.dataJson, revision = r.revision, imported = false)), local.policies)
        }
        val incoming = PolicyWire.toLocal(raw)
        val fresh = Meta(rawJson = r.dataJson, revision = r.revision)

        if (m != null && m.deleted) {
            // Deleted here, edited elsewhere: whichever happened later wins.
            return if ((m.dirtyAt ?: 0) > r.updatedAt) set(local.state.policies + (id to m.copy(rawJson = r.dataJson, revision = r.revision)), local.policies)
            else set(local.state.policies + (id to fresh), local.policies + incoming)
        }
        if (mine == null) return set(local.state.policies + (id to fresh), local.policies + incoming)

        // Present on both sides. No bookkeeping yet (first sync) means the server wins; otherwise a newer local edit wins.
        val localWins = m != null && policyDirty(mine, m) && (m.dirtyAt ?: 0) > r.updatedAt
        return if (localWins) set(local.state.policies + (id to m!!.copy(rawJson = r.dataJson, revision = r.revision)), local.policies)
        else set(local.state.policies + (id to fresh), local.policies.map { if (it.id == id) incoming else it })
    }

    private fun applyBedtimeRecord(local: Local, r: ServerRecord): Pair<Local, Boolean> {
        if (r.deleted) return local to false
        val m = local.state.bedtime
        val incoming = BedtimeWire.toLocal(JSONObject(r.dataJson))
        val localWins = m != null && bedtimeDirty(local.bedtime, m) && (m.dirtyAt ?: 0) > r.updatedAt
        return if (localWins) {
            local.copy(state = local.state.copy(bedtime = m!!.copy(rawJson = r.dataJson, revision = r.revision))) to false
        } else {
            val changed = !BedtimeWire.same(local.bedtime, incoming)
            local.copy(bedtime = incoming, state = local.state.copy(bedtime = Meta(rawJson = r.dataJson, revision = r.revision))) to changed
        }
    }

    // ---------------------------------------------------------------- push

    fun planPush(local: Local, now: Long): List<Outgoing> {
        if (local.state.userId == null || !local.state.initialPullDone) return emptyList()
        val out = ArrayList<Outgoing>()
        for (p in local.policies) {
            val m = local.state.policies[p.id]
            if (m != null && (m.deleted || !m.imported)) continue
            if (!policyDirty(p, m)) continue
            val json = PolicyWire.merge(p, m?.raw).toString()
            val fp = fingerprint(PolicyWire.canonical(p), m?.rawJson)
            if (m?.rejected == fp) continue
            out += Outgoing(POLICY, p.id, m?.dirtyAt ?: now, json, false, fp)
        }
        for ((id, m) in local.state.policies) {
            if (m.deleted && m.imported && m.rawJson != null && m.rejected != DELETED) out += Outgoing(POLICY, id, m.dirtyAt ?: now, null, true, DELETED)
        }
        val bm = local.state.bedtime
        if (bedtimeDirty(local.bedtime, bm)) {
            val json = BedtimeWire.merge(local.bedtime, bm?.raw).toString()
            val fp = fingerprint(BedtimeWire.canonical(local.bedtime), bm?.rawJson)
            if (bm?.rejected != fp) out += Outgoing(BEDTIME, BEDTIME_ID, bm?.dirtyAt ?: now, json, false, fp)
        }
        return out
    }

    /** [sent] and [results] are matched by (type, id). A change the server didn't answer stays dirty and goes out again. */
    fun applyPushResults(local: Local, sent: List<Outgoing>, results: List<PushOutcome>): Applied {
        var cur = local
        var bedtimeChanged = false
        var rejected = 0
        for (res in results) {
            val out = sent.find { it.type == res.type && it.id.lowercase() == res.id.lowercase() } ?: continue
            // policy_in_use: a session is running on this profile and the edit would loosen it. The server's copy wins
            // outright, so the local edit is reverted (clear its dirty time, or "newer local edit wins" would resend it).
            val inUse = res.status == "rejected" && res.code == "policy_in_use" && res.record != null && out.type == POLICY
            if (inUse) cur.state.policies[out.id.lowercase()]?.let { m -> cur = cur.copy(state = cur.state.copy(policies = cur.state.policies + (out.id.lowercase() to m.copy(dirtyAt = null)))) }
            when (if (inUse) "stale" else res.status) {
                "applied" -> {
                    val rec = res.record ?: continue
                    when (out.type) {
                        POLICY -> cur = if (rec.deleted) cur.copy(state = cur.state.copy(policies = cur.state.policies - out.id.lowercase())) else adoptAfterPush(cur, rec)
                        BEDTIME -> cur = cur.copy(state = cur.state.copy(bedtime = settle(cur.state.bedtime, rec, BedtimeWire.same(cur.bedtime, BedtimeWire.toLocal(JSONObject(rec.dataJson))))))
                    }
                }
                // The server already has this or newer: take its copy, unless a newer local edit slipped in meanwhile.
                "stale" -> res.record?.let { rec ->
                    when (out.type) {
                        POLICY -> cur = applyPolicyRecord(cur, rec)
                        BEDTIME -> applyBedtimeRecord(cur, rec).let { (next, changed) -> cur = next; bedtimeChanged = bedtimeChanged || changed }
                    }
                }
                else -> { // rejected: remember the payload so it isn't resent until the user changes it
                    rejected++
                    cur = when (out.type) {
                        POLICY -> cur.state.policies[out.id.lowercase()]?.let { m -> cur.copy(state = cur.state.copy(policies = cur.state.policies + (out.id.lowercase() to m.copy(rejected = out.fingerprint)))) } ?: cur
                        else -> cur.copy(state = cur.state.copy(bedtime = (cur.state.bedtime ?: Meta()).copy(rejected = out.fingerprint)))
                    }
                }
            }
        }
        return Applied(cur, bedtimeChanged, rejected)
    }

    private fun adoptAfterPush(local: Local, rec: ServerRecord): Local {
        val id = rec.id.lowercase()
        val mine = local.policies.find { it.id == id }
        val same = mine != null && PolicyWire.same(mine, PolicyWire.toLocal(JSONObject(rec.dataJson)))
        val m = settle(local.state.policies[id], rec, same)
        return local.copy(state = local.state.copy(policies = local.state.policies + (id to m)))
    }

    /** After the server accepted our data: it becomes the new base. If the user edited again meanwhile, it stays dirty. */
    private fun settle(old: Meta?, rec: ServerRecord, localMatchesServer: Boolean) =
        Meta(rawJson = rec.dataJson, revision = rec.revision, dirtyAt = if (localMatchesServer) null else old?.dirtyAt)

    /** Changes the server refused that are still waiting for the user to edit them. */
    fun rejectedCount(state: SyncState): Int = state.policies.values.count { it.rejected != null } + (if (state.bedtime?.rejected != null) 1 else 0)

    // ---------------------------------------------------------------- dirtiness (always derived from the data)

    private fun policyDirty(p: BlockPolicy, m: Meta?): Boolean = m?.rawJson == null || !PolicyWire.same(p, PolicyWire.toLocal(m.raw!!))

    /** Never uploaded bedtime counts as dirty only when it isn't just the defaults. */
    private fun bedtimeDirty(b: BedtimeSettings, m: Meta?): Boolean =
        if (m?.rawJson == null) !BedtimeWire.same(b, BedtimeWire.DEFAULT) else !BedtimeWire.same(b, BedtimeWire.toLocal(m.raw!!))

    /**
     * Identifies "this content on top of this server base". Built from the content, not from the merged JSON: a new
     * rule gets a random id every time it is merged, which would make every attempt look different.
     */
    private fun fingerprint(content: String, base: String?): String =
        MessageDigest.getInstance("SHA-256").digest("$content\u0000${base ?: ""}".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
