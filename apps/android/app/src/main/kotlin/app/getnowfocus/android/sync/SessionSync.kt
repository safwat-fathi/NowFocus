package app.getnowfocus.android.sync

import android.content.Context
import app.getnowfocus.android.DomainValidation
import app.getnowfocus.android.Enforcement
import app.getnowfocus.android.FocusSession
import app.getnowfocus.android.FocusSessionStatus
import app.getnowfocus.android.HistoryDatabase
import app.getnowfocus.android.PolicyMode
import app.getnowfocus.android.SessionEngine
import app.getnowfocus.android.SessionNotifier
import app.getnowfocus.android.SessionOrigin
import app.getnowfocus.android.SessionRepository
import app.getnowfocus.android.toHistoryRow
import app.getnowfocus.android.withPolicy
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** The session half of a sync pass, next to the policy and bedtime half that [SyncLogic] owns. */
interface SessionSyncPort {
    /** Every `session` record from the pull (possibly none: also the moment to join one that was waiting). */
    suspend fun onPulled(records: List<ServerRecord>)
    suspend fun plan(now: Long): List<Outgoing>
    suspend fun onPushed(sent: List<Outgoing>, results: List<PushOutcome>)
}

/**
 * What this phone remembers about session sync: which state of a session the server already has
 * ([acked], by fingerprint), the server's JSON for sessions we joined or pushed ([raws]), and sessions worth
 * joining that had to wait because one was already running ([pending]).
 */
data class SessionSyncState(
    val acked: Map<String, String> = emptyMap(),
    val raws: Map<String, String> = emptyMap(),
    val pending: Map<String, String> = emptyMap(),
) {
    fun encode(): String = JSONObject()
        .put("acked", JSONObject(acked)).put("raws", JSONObject(raws)).put("pending", JSONObject(pending)).toString()

    /** Only what could still matter: the running session and anything waiting. */
    fun pruned(keep: Set<String>) = SessionSyncState(
        acked.filterKeys { it in keep }, raws.filterKeys { it in keep }, pending.filterKeys { it in keep },
    )

    companion object {
        fun decode(text: String?): SessionSyncState = runCatching {
            val o = JSONObject(text ?: return SessionSyncState())
            fun map(key: String) = o.obj(key)?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
            SessionSyncState(map("acked"), map("raws"), map("pending"))
        }.getOrDefault(SessionSyncState())
    }
}

/**
 * Cross-device sessions on Android. Local-first like everything in sync: this only ever adds a session to
 * enforce (bounded by [SessionWire.decide]), or ends one the user ended elsewhere. It never weakens a session
 * running here, and a failed pass changes nothing.
 */
class RepositorySessionSync(
    private val context: Context,
    private val repository: SessionRepository,
    private val deviceName: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SessionSyncPort {

    private suspend fun <R> edit(block: (SessionSyncState) -> Pair<SessionSyncState, R>): R =
        repository.editSessionSync { text -> block(SessionSyncState.decode(text)).let { (next, r) -> next.encode() to r } }

    private suspend fun localSession(now: Long) = repository.sessionFlow.first()?.let { SessionEngine.evaluateState(it, now) }

    override suspend fun onPulled(records: List<ServerRecord>) {
        val now = clock()
        val joinEnabled = repository.joinRemoteFlow.first()
        for (remote in records.mapNotNull { SessionWire.parse(it) }) {
            val local = localSession(now)
            when (val d = SessionWire.decide(remote, local, now, joinEnabled)) {
                SessionDecision.EndLocal -> if (local != null) endHere(local, now)
                is SessionDecision.Extend -> if (local != null) {
                    repository.save(local.copy(endAt = d.endAt))   // the services re-derive from the saved session
                    SessionNotifier.sync(context)
                }
                SessionDecision.Join, SessionDecision.Defer -> edit { st -> st.copy(pending = st.pending + (remote.id to remote.raw.toString())) to Unit }
                SessionDecision.Ignore -> {}
            }
        }
        joinWaiting(now, joinEnabled)
    }

    /** The session running here was cancelled on another device. Same bookkeeping as cancelling it here. */
    private suspend fun endHere(local: FocusSession, now: Long) {
        val ended = local.copy(status = FocusSessionStatus.CANCELLED, cancelledAt = now)
        repository.save(ended)
        HistoryDatabase.get(context).dao().insertSession(ended.toHistoryRow())
        edit { st -> st.copy(acked = st.acked + (ended.id to SessionWire.fingerprint(ended))) to Unit } // the server already knows
        SessionNotifier.sync(context)
        app.getnowfocus.android.reconcileBedtimeSession(context, repository.bedtimeSettingsFlow.first())
    }

    private suspend fun joinWaiting(now: Long, joinEnabled: Boolean) {
        val local = localSession(now)
        val st = edit { it to it }
        val waiting = st.pending.values.mapNotNull { json ->
            runCatching { JSONObject(json) }.getOrNull()?.let { o -> SessionWire.parse(ServerRecord(SessionWire.TYPE, o.s("id") ?: "", json, false, 0, 0)) }
        }
        val policies = repository.policiesFlow.first()
        val best = waiting.filter { joinEnabled && SessionWire.enforceable(it, policies) && SessionWire.decide(it, local, now, true) == SessionDecision.Join }.maxByOrNull { it.endAt }
        if (best == null) {
            // Drop what is over or no longer worth waiting for, so the list can't grow forever.
            val live = waiting.filter { it.endAt > now }.map { it.id }.toSet()
            edit { s -> s.copy(pending = s.pending.filterKeys { it in live }) to Unit }
            return
        }
        join(best, now)
    }

    private suspend fun join(remote: RemoteSession, now: Long) {
        val policy = repository.policiesFlow.first().find { it.id == remote.policyId }
        val base = FocusSession(
            id = remote.id, policyId = remote.policyId, startAt = remote.startAt, endAt = remote.endAt,
            status = FocusSessionStatus.SCHEDULED, createdAt = now,
            enforcementMode = remote.mode, origin = SessionOrigin.REMOTE, startedOn = remote.startedOn,
        )
        val session = (if (policy != null && policy.mode == PolicyMode.ALLOWLIST) base.withPolicy(policy)
        // The snapshot only knows the originating device's own apps, so add what this account's synced profile says for Android.
        else base.copy(
            domains = remote.domains + policy?.domains.orEmpty().mapNotNull(DomainValidation::normalize),
            packages = remote.packages + policy?.apps.orEmpty().map { it.packageName },
            partial = remote.partial + policy?.partial.orEmpty(),
        )).let { SessionEngine.evaluateState(it, now) }
        repository.save(session)
        edit { st ->
            st.copy(
                acked = st.acked + (session.id to SessionWire.fingerprint(session)),   // joining changes nothing the server must hear
                raws = st.raws + (session.id to remote.raw.toString()),
                pending = st.pending - session.id,
            ) to Unit
        }
        Enforcement.start(context)
    }

    override suspend fun plan(now: Long): List<Outgoing> {
        val s = localSession(now)?.takeIf { SessionWire.syncs(it) } ?: return emptyList()
        val fp = SessionWire.fingerprint(s)
        val st = edit { it to it }
        if (st.acked[s.id] == fp) return emptyList()
        val data = SessionWire.toWire(s, deviceName, st.raws[s.id]?.let { JSONObject(it) })
        return listOf(Outgoing(SessionWire.TYPE, s.id, now, data.toString(), false, fp))
    }

    override suspend fun onPushed(sent: List<Outgoing>, results: List<PushOutcome>) {
        val keep = setOfNotNull(repository.sessionFlow.first()?.id)
        for (res in results) {
            val out = sent.find { it.id.lowercase() == res.id.lowercase() } ?: continue
            // Applied, already known or refused: all mean "don't send this exact state again". A new state has a new fingerprint.
            edit { st ->
                val raw = res.record?.takeIf { res.status != "rejected" }?.dataJson
                st.copy(acked = st.acked + (out.id.lowercase() to out.fingerprint), raws = if (raw != null) st.raws + (out.id.lowercase() to raw) else st.raws)
                    .pruned(keep + st.pending.keys) to Unit
            }
            if (res.status == "stale") res.record?.let { onPulled(listOf(it)) }
        }
    }
}
