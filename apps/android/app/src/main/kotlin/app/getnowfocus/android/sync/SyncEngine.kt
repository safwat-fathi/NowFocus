package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The local side of sync. [transact] must be one atomic read-modify-write of policies, bedtime and [SyncState] together. */
interface SyncStore {
    suspend fun <R> transact(block: (Local) -> Pair<Local, R>): R
    /** Profiles something still points at (bedtime's profile, the current session). */
    suspend fun referencedPolicyIds(): Set<String>
    /** Bedtime was replaced by server data: reschedule alarms, quiet notifications and the nightly session. */
    suspend fun onBedtimeApplied(settings: BedtimeSettings)
}

/** [rejected] = changes the server refused that are still outstanding (not just in this pass). */
data class SyncReport(val pulled: Int, val pushed: Int, val rejected: Int)

/**
 * One sync pass: pull everything new, apply it, then push what changed here. Passes never overlap. Network
 * errors propagate untouched (the caller decides when to retry); local data is only ever changed through
 * [SyncLogic], inside [SyncStore.transact].
 */
class SyncEngine(
    private val store: SyncStore,
    private val api: SyncApiPort,
    /** Cross-device sessions. Null leaves them out entirely (the tests that only cover profiles and bedtime). */
    private val sessions: SessionSyncPort? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()

    suspend fun syncOnce(): SyncReport = lock.withLock {
        var pulled = 0
        var pushed = 0

        // 1. Pull until caught up. Each page and its cursor are applied in one atomic step.
        var guard = 0
        val sessionRecords = ArrayList<ServerRecord>()
        while (guard++ < MAX_PAGES) {
            val cursor = store.transact { l -> l to l.state.cursor.takeIf { l.state.userId != null } } ?: return@withLock SyncReport(0, 0, 0)
            val page = try { api.pull(cursor) } catch (e: ApiException) {
                if (e.status != 410) throw e
                // The cursor is older than the server's history: start over and reconcile from the full set.
                store.transact { l -> l.copy(state = l.state.copy(cursor = 0)) to Unit }
                continue
            }
            val applied = store.transact { l -> if (l.state.userId == null) l to null else SyncLogic.applyPulled(l, page.changes, page.cursor).let { it.local to it } }
            pulled += page.changes.size
            sessionRecords += page.changes.filter { it.type == SessionWire.TYPE }
            if (applied?.bedtimeChanged == true) store.onBedtimeApplied(applied.local.bedtime)
            if (!page.hasMore || page.cursor <= cursor) break
        }
        // Even with nothing new: a session that had to wait for the running one to end is joined now.
        if (sessions != null && store.transact { l -> l to (l.state.userId != null) }) sessions.onPulled(sessionRecords)

        // 2. The first pull after linking is complete: decide what the account already has versus what only this phone has.
        val referenced = store.referencedPolicyIds()
        store.transact { l -> if (l.state.userId != null && !l.state.initialPullDone) SyncLogic.finishInitialPull(l, referenced) to Unit else l to Unit }

        // 3. Push. A stale answer can leave something new to send (a rebased edit), so look again, a few times at most.
        repeat(MAX_PUSH_ROUNDS) {
            val now = clock()
            // Profiles go before the session that points at them.
            val plan = store.transact { l -> l to SyncLogic.planPush(l, now) } +
                (if (store.transact { l -> l to (l.state.userId != null && l.state.initialPullDone) }) sessions?.plan(now).orEmpty() else emptyList())
            if (plan.isEmpty()) return@repeat
            for (chunk in plan.chunked(MAX_CHANGES_PER_PUSH)) {
                val results = api.push(chunk)
                // Sessions have their own bookkeeping: SyncLogic must never see their outcomes.
                val (sessionSent, otherSent) = chunk.partition { it.type == SessionWire.TYPE }
                val (sessionResults, otherResults) = results.partition { it.type == SessionWire.TYPE }
                val applied = store.transact { l -> if (l.state.userId == null) l to null else SyncLogic.applyPushResults(l, otherSent, otherResults).let { it.local to it } }
                if (sessionSent.isNotEmpty()) sessions?.onPushed(sessionSent, sessionResults)
                pushed += chunk.size
                if (applied?.bedtimeChanged == true) store.onBedtimeApplied(applied.local.bedtime)
            }
        }
        SyncReport(pulled, pushed, rejected = store.transact { l -> l to SyncLogic.rejectedCount(l.state) })
    }

    private companion object {
        const val MAX_PAGES = 1000
        const val MAX_PUSH_ROUNDS = 3
        const val MAX_CHANGES_PER_PUSH = 100   // the server's per-request limit
    }
}
