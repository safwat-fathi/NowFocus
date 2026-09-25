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

    val policies: StateFlow<List<BlockPolicy>> =
        repository.policiesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val commitmentShield: StateFlow<CommitmentShield?> =
        repository.commitmentShieldFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val bedtimeSettings: StateFlow<BedtimeSettings> =
        repository.bedtimeSettingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, BedtimeSettings())

    init {
        viewModelScope.launch { repository.seedDefaultPolicyIfNeeded() }
        viewModelScope.launch {
            repository.sessionFlow.collect { stored ->
                _session.value = stored?.let { SessionEngine.evaluateState(it) }
            }
        }
        // Recovery, like macOS recoverSession(): a force-stop kills the VPN, so
        // re-arm it if a session OR the Commitment Shield is still supposed to
        // be enforcing. Enforcement.start() only needs to know whether to run
        // at all - once started, the service derives full up-to-date rules
        // (both windows) from activeRulesFlow itself.
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val sessionActive = repository.sessionFlow.first()?.let { SessionEngine.isActive(SessionEngine.evaluateState(it, now), now) } ?: false
            val shieldActive = repository.commitmentShieldFlow.first()?.let { it.endAt > now } ?: false
            if (sessionActive || shieldActive) Enforcement.start(getApplication())
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

    /** Starts the 14-day lock immediately - the only exit is [cancelCommitmentShield] within its grace period. */
    fun createCommitmentShield(domains: Set<String>, packages: Set<String>) {
        val now = System.currentTimeMillis()
        val shield = CommitmentShield(
            startAt = now, endAt = now + CommitmentShield.DURATION_MS,
            domains = domains, packages = packages, createdAt = now,
        )
        viewModelScope.launch {
            repository.saveCommitmentShield(shield)
            Enforcement.start(getApplication())
        }
    }

    /** Returns whether the cancel actually happened - false once the grace period has elapsed. */
    fun cancelCommitmentShield(): Boolean {
        val current = commitmentShield.value ?: return false
        if (!current.canCancel(System.currentTimeMillis())) return false
        viewModelScope.launch { repository.clearCommitmentShield() }
        return true
    }

    fun saveBedtimeSettings(settings: BedtimeSettings) {
        viewModelScope.launch {
            repository.saveBedtimeSettings(settings)
            BedtimeScheduler.scheduleAll(getApplication(), settings)
        }
    }
}
