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
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()

    suspend fun syncOnce(): SyncReport = lock.withLock {
        var pulled = 0
        var pushed = 0

        // 1. Pull until caught up. Each page and its cursor are applied in one atomic step.
        var guard = 0
        while (guard++ < MAX_PAGES) {
            val cursor = store.transact { l -> l to l.state.cursor.takeIf { l.state.userId != null } } ?: return@withLock SyncReport(0, 0, 0)
            val page = api.pull(cursor)
            val applied = store.transact { l -> if (l.state.userId == null) l to null else SyncLogic.applyPulled(l, page.changes, page.cursor).let { it.local to it } }
            pulled += page.changes.size
            if (applied?.bedtimeChanged == true) store.onBedtimeApplied(applied.local.bedtime)
            if (!page.hasMore || page.cursor <= cursor) break
        }

        // 2. The first pull after linking is complete: decide what the account already has versus what only this phone has.
        val referenced = store.referencedPolicyIds()
        store.transact { l -> if (l.state.userId != null && !l.state.initialPullDone) SyncLogic.finishInitialPull(l, referenced) to Unit else l to Unit }

        // 3. Push. A stale answer can leave something new to send (a rebased edit), so look again, a few times at most.
        repeat(MAX_PUSH_ROUNDS) {
            val plan = store.transact { l -> l to SyncLogic.planPush(l, clock()) }
            if (plan.isEmpty()) return@repeat
            for (chunk in plan.chunked(MAX_CHANGES_PER_PUSH)) {
                val results = api.push(chunk)
                val applied = store.transact { l -> if (l.state.userId == null) l to null else SyncLogic.applyPushResults(l, chunk, results).let { it.local to it } }
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
