package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy

internal class FakeStore(var local: Local, val clock: Clock) : SyncStore {
    val bedtimeApplied = mutableListOf<BedtimeSettings>()
    var referenced: Set<String> = emptySet()
    override suspend fun <R> transact(block: (Local) -> Pair<Local, R>): R { val (n, r) = block(local); local = n; return r }
    override suspend fun referencedPolicyIds() = referenced
    override suspend fun onBedtimeApplied(settings: BedtimeSettings) { bedtimeApplied += settings }

    /** What SessionRepository.updatePolicies / saveBedtimeSettings do on a user edit. */
    fun edit(f: (List<BlockPolicy>) -> List<BlockPolicy>) { val next = f(local.policies); local = local.copy(policies = next, state = SyncLogic.stampPolicies(local.state, local.policies, next, clock.now)) }
    fun setBedtime(b: BedtimeSettings) { local = local.copy(bedtime = b, state = SyncLogic.stampBedtime(local.state, local.bedtime, b, clock.now)) }
}

internal class Clock { var now = 1_000_000L; fun tick(ms: Long = 1000) { now += ms } }
