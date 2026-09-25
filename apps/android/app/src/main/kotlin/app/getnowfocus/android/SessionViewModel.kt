package app.getnowfocus.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SessionRepository(application)
    private val historyDao by lazy { HistoryDatabase.get(application).dao() }

    private val _session = MutableStateFlow<FocusSession?>(null)
    val session: StateFlow<FocusSession?> = _session.asStateFlow()

    // False until the first real read of sessionFlow completes. A screen
    // that gates navigation on "is a session running" must wait for this -
    // otherwise a freshly created ViewModel (e.g. MainActivity recreated via
    // CLEAR_TOP when the Shield's "I really need it" launches it at the
    // Unlock route) briefly reports no session running at all and gets
    // redirected to Home before the real, active session ever loads.
    private val _sessionLoaded = MutableStateFlow(false)
    val sessionLoaded: StateFlow<Boolean> = _sessionLoaded.asStateFlow()

    val policies: StateFlow<List<BlockPolicy>> =
        repository.policiesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val commitmentShield: StateFlow<CommitmentShield?> =
        repository.commitmentShieldFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val bedtimeSettings: StateFlow<BedtimeSettings> =
        repository.bedtimeSettingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, BedtimeSettings())

    // Defaults true (skip onboarding) until the real, persisted value loads, so
    // an existing user is never bounced back into onboarding for one frame.
    val onboardingDone: StateFlow<Boolean> =
        repository.onboardingDoneFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch { repository.seedDefaultPolicyIfNeeded() }
        viewModelScope.launch {
            repository.sessionFlow.collect { stored ->
                // Captured before overwriting: a session that completed while
                // this ViewModel didn't exist (app closed, process killed, or
                // this is simply the first collection after a fresh launch)
                // arrives here already evaluated as COMPLETED with no prior
                // in-memory state to compare against - that transition must
                // still be logged, or Stats silently undercounts every
                // completion the app wasn't open to see finish live.
                val previousStatus = _session.value?.status
                val evaluated = stored?.let { SessionEngine.evaluateState(it) }
                _session.value = evaluated
                _sessionLoaded.value = true
                if (evaluated != null && evaluated.status == FocusSessionStatus.COMPLETED && previousStatus != FocusSessionStatus.COMPLETED) {
                    historyDao.insertSession(evaluated.toHistoryRow())
                }
            }
        }
        // Recovery, like macOS recoverSession(): a force-stop kills the VPN
        // and cancels Bedtime's alarms, so re-arm both.
        viewModelScope.launch {
            if (Enforcement.shouldRun(repository)) Enforcement.start(getApplication())
        }
        viewModelScope.launch {
            val settings = repository.bedtimeSettingsFlow.first()
            BedtimeScheduler.scheduleAll(getApplication(), settings)
            reconcileQuietNotifications(getApplication(), settings)
        }
    }

    fun startSession(policyId: String, durationMinutes: Int, mode: EnforcementMode = EnforcementMode.NORMAL) {
        val policy = policies.value.find { it.id == policyId } ?: return
        val now = System.currentTimeMillis()
        val scheduled = FocusSession(
            id = UUID.randomUUID().toString(),
            policyId = policy.id,
            startAt = now,
            endAt = now + durationMinutes * 60_000L,
            status = FocusSessionStatus.SCHEDULED,
            createdAt = now,
            domains = policy.domains.toSet(),
            packages = policy.apps.map { it.packageName }.toSet(),
            enforcementMode = mode,
        )
        viewModelScope.launch {
            repository.save(SessionEngine.evaluateState(scheduled, now))
            Enforcement.start(getApplication())
        }
    }

    /**
     * The only path that ends a session early. Strict requires the Unlock
     * screen's type-sentence-then-wait flow to finish first
     * ([unlockCompleted]); Locked never allows it at all — see
     * [SessionEngine.canCancel]. Returns whether the cancel actually happened,
     * so the caller (e.g. the Unlock screen) knows whether to navigate away.
     */
    fun cancelSession(unlockCompleted: Boolean = false): Boolean {
        val current = _session.value ?: return false
        if (!SessionEngine.canCancel(current.enforcementMode, unlockCompleted)) return false
        val cancelled = current.copy(status = FocusSessionStatus.CANCELLED, cancelledAt = System.currentTimeMillis())
        viewModelScope.launch {
            // No direct Enforcement.stop() here: saving CANCELLED makes
            // activeRulesFlow re-derive and drop just this window - both
            // enforcement services are reactive to it. Calling stop()
            // directly would tear down the VPN even if a Commitment Shield
            // is still live, since it doesn't know about other windows.
            repository.save(cancelled)
            historyDao.insertSession(cancelled.toHistoryRow())
        }
        return true
    }

    /** Re-derives state from persisted data + current time, and writes back any change. */
    fun refreshNow() {
        val current = _session.value ?: return
        val evaluated = SessionEngine.evaluateState(current)
        if (evaluated.status != current.status) {
            viewModelScope.launch {
                repository.save(evaluated)
                // A session that expired from SCHEDULED (never actually ran) has
                // nothing to log - only completion of a session that ran counts.
                if (evaluated.status == FocusSessionStatus.COMPLETED) historyDao.insertSession(evaluated.toHistoryRow())
            }
        }
    }

    fun addPolicy(): String {
        val policy = BlockPolicy(name = "New Policy")
        viewModelScope.launch { repository.updatePolicies { it + policy } }
        return policy.id
    }

    fun savePolicy(policy: BlockPolicy) {
        viewModelScope.launch { repository.updatePolicies { list -> list.map { if (it.id == policy.id) policy else it } } }
    }

    fun deletePolicy(id: String) {
        viewModelScope.launch { repository.updatePolicies { list -> list.filterNot { it.id == id } } }
    }

    /**
     * Starts the 14-day lock immediately - the only exit is
     * [cancelCommitmentShield] within its grace period. Refuses to replace a
     * shield that isn't [CommitmentShield.isOver] yet: without this guard,
     * winding the wall clock forward to make a live shield merely *look*
     * expired would let a throwaway shield be created over it and then
     * cancelled (or the clock wound back), permanently defeating the real
     * one - see CommitmentShieldTest for the boot-relative reasoning.
     */
    fun createCommitmentShield(domains: Set<String>, packages: Set<String>) {
        val current = commitmentShield.value
        val now = System.currentTimeMillis()
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        val bootNow = currentBootCount(getApplication())
        if (current != null && !current.isOver(now, elapsedNow, bootNow)) return
        val shield = CommitmentShield(
            startAt = now, endAt = now + CommitmentShield.DURATION_MS,
            domains = domains, packages = packages, createdAt = now,
            createdElapsedRealtime = elapsedNow, createdBootCount = bootNow,
        )
        viewModelScope.launch {
            repository.saveCommitmentShield(shield)
            Enforcement.start(getApplication())
        }
    }

    /** Returns whether the cancel actually happened - false once the grace period has elapsed. */
    fun cancelCommitmentShield(): Boolean {
        val current = commitmentShield.value ?: return false
        if (!current.canCancel(android.os.SystemClock.elapsedRealtime(), currentBootCount(getApplication()))) return false
        viewModelScope.launch { repository.clearCommitmentShield() }
        return true
    }

    fun saveBedtimeSettings(settings: BedtimeSettings) {
        viewModelScope.launch {
            repository.saveBedtimeSettings(settings)
            BedtimeScheduler.scheduleAll(getApplication(), settings)
            // Immediately, not just at the next scheduled alarm: turning
            // Bedtime or its quiet-notifications toggle off mid-window must
            // restore the filter right away, not leave it stuck quiet.
            reconcileQuietNotifications(getApplication(), settings)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repository.setOnboardingDone() }
    }
}

/** 0 as a fallback is safe: it only ever collides with a real device's boot count if that device has never rebooted since this field started being recorded, in which case there's nothing to distinguish anyway. */
private fun currentBootCount(context: android.content.Context): Int =
    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
