package app.getnowfocus.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.getnowfocus.android.sync.DataStoreAuthStore
import app.getnowfocus.android.sync.RepositorySyncStore
import app.getnowfocus.android.sync.SyncApi
import app.getnowfocus.android.sync.SyncController
import app.getnowfocus.android.sync.SyncEngine
import app.getnowfocus.android.sync.SyncSocket
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

    val people: StateFlow<List<Person>> =
        repository.peopleFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val goals: StateFlow<List<Goal>> =
        repository.goalsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cheatDay: StateFlow<CheatDay?> =
        repository.cheatDayFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val limits: StateFlow<List<AppLimit>> =
        repository.limitsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val frictionApps: StateFlow<List<AppRule>> =
        repository.frictionAppsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val schedules: StateFlow<List<Schedule>> =
        repository.schedulesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Defaults true (skip onboarding) until the real, persisted value loads, so
    // an existing user is never bounced back into onboarding for one frame.
    val onboardingDone: StateFlow<Boolean> =
        repository.onboardingDoneFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Null until the saved value loads: unlike onboardingDone, a lock must not default open for a frame. */
    val accountLock: StateFlow<Boolean?> =
        repository.accountLockFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setAccountLock(on: Boolean) {
        viewModelScope.launch { repository.setAccountLock(on) }
    }

    /**
     * Optional account sync (profiles + bedtime). Makes no network calls until the user signs in; local
     * enforcement never depends on it. See sync/ and services/api/WIRE_FORMAT.md.
     */
    val sync: SyncController = run {
        val userAgent = "NowFocus-Android/${BuildConfig.VERSION_NAME}"
        val auth = DataStoreAuthStore(application)
        val api = SyncApi(BuildConfig.SYNC_BASE_URL, userAgent, auth)
        val store = RepositorySyncStore(application, repository)
        SyncController(
            scope = viewModelScope, engine = SyncEngine(store, api), api = api, auth = auth, store = store,
            socket = SyncSocket(BuildConfig.SYNC_BASE_URL, userAgent),
            localChanges = combine(repository.policiesFlow, repository.bedtimeSettingsFlow) { p, b -> p to b }.distinctUntilChanged(),
            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(100),
        )
    }

    init {
        sync.start()
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
                    // A focus session that ended inside the bedtime window (so
                    // the wind-down alarm skipped it) should hand off to the
                    // nightly locked session now. No-ops outside the window.
                    reconcileBedtimeSession(getApplication(), repository.bedtimeSettingsFlow.first())
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
            // Recovery: if the app opens mid-window with bedtime configured and
            // nothing running, start the nightly locked session now.
            reconcileBedtimeSession(getApplication(), settings)
        }
    }

    fun startSession(policyId: String, durationMinutes: Int, mode: EnforcementMode = EnforcementMode.NORMAL) {
        val policy = policies.value.find { it.id == policyId } ?: return
        startSessionOn(policy, durationMinutes, mode)
    }

    /**
     * Onboarding's first win: a 10-minute Normal session on a new profile holding the apps the user
     * just picked, then onboarding is done. A new profile rather than the seeded "Deep Work": the
     * first sign-in drops an untouched seed ([SyncLogic.isUntouchedSeed]), and adding apps to it
     * would make nearly every seed "touched" and duplicate it on the user's next device.
     */
    fun startFirstSession(apps: List<AppRule>) {
        val policy = BlockPolicy(name = "First focus", apps = apps)
        viewModelScope.launch {
            repository.updatePolicies { it + policy }
            repository.setOnboardingDone()
            startSessionOn(policy, 10) // after the profile exists, so the session never points at a missing one
        }
    }

    private fun startSessionOn(policy: BlockPolicy, durationMinutes: Int, mode: EnforcementMode = EnforcementMode.NORMAL) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        // Only STRICT uses a note. Purging on every start also clears the last
        // session's note and any pending one a non-STRICT start left behind.
        val voiceNotePath = if (mode == EnforcementMode.STRICT) VoiceNote.adoptPending(getApplication(), id) else null
        VoiceNote.purgeExcept(getApplication(), keepId = id)
        val scheduled = FocusSession(
            id = id,
            policyId = policy.id,
            startAt = now,
            endAt = now + durationMinutes * 60_000L,
            status = FocusSessionStatus.SCHEDULED,
            createdAt = now,
            domains = policy.domains.toSet(),
            packages = policy.apps.map { it.packageName }.toSet(),
            partial = policy.partial,
            enforcementMode = mode,
            voiceNotePath = voiceNotePath,
        )
        viewModelScope.launch {
            repository.save(SessionEngine.evaluateState(scheduled, now))
            Enforcement.start(getApplication())
        }
    }

    /**
     * The only path that ends a session early. Strict requires the Unlock
     * screen's type-sentence-then-wait flow to finish first
     * ([unlockCompleted]), plus the session's voice note played through
     * ([listened]) when it has one; Locked never allows it at all — see
     * [SessionEngine.canCancel]. Returns whether the cancel actually happened,
     * so the caller (e.g. the Unlock screen) knows whether to navigate away.
     */
    fun cancelSession(unlockCompleted: Boolean = false, listened: Boolean = false): Boolean {
        val current = _session.value ?: return false
        val hasVoiceNote = VoiceNote.playableFile(current) != null
        if (!SessionEngine.canCancel(current.enforcementMode, unlockCompleted, hasVoiceNote, listened)) return false
        val cancelled = current.copy(status = FocusSessionStatus.CANCELLED, cancelledAt = System.currentTimeMillis())
        viewModelScope.launch {
            // No direct Enforcement.stop() here: saving CANCELLED makes
            // activeRulesFlow re-derive and drop just this window - both
            // enforcement services are reactive to it. Calling stop()
            // directly would tear down the VPN even if a Commitment Shield
            // is still live, since it doesn't know about other windows.
            repository.save(cancelled)
            historyDao.insertSession(cancelled.toHistoryRow())
            SessionNotifier.sync(getApplication())
            // Ending a session inside the bedtime window hands off to the
            // nightly locked session immediately (no-ops outside the window).
            reconcileBedtimeSession(getApplication(), repository.bedtimeSettingsFlow.first())
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
        val policy = BlockPolicy(name = "New profile")
        viewModelScope.launch { repository.updatePolicies { it + policy } }
        return policy.id
    }

    /**
     * Saves a policy edit. If a focus session is active on this policy's
     * profile, additions are applied immediately (the session's domain/package
     * snapshot is updated so both enforcement services pick it up
     * reactively), while removals are silently rejected — you can't weaken
     * enforcement mid-session (all modes).
     *
     * Returns `false` when the edit was rejected so the caller can show
     * feedback (currently the composable reloads from the policy list, which
     * restores the removed row automatically).
     */
    fun savePolicy(policy: BlockPolicy): Boolean {
        val current = _session.value
        val now = System.currentTimeMillis()
        val isLiveEdit = current != null &&
            SessionEngine.isActive(current, now) &&
            current.policyId == policy.id

        if (isLiveEdit) {
            // Load the stored (pre-edit) version to detect weakening.
            val stored = policies.value.find { it.id == policy.id } ?: run {
                // Profile was deleted out from under us — plain save.
                viewModelScope.launch { repository.updatePolicies { list -> list.map { if (it.id == policy.id) policy else it } } }
                return true
            }

            if (weakensEnforcement(stored, policy)) {
                // Reject: don't persist the removal. The UI will reload
                // from policiesFlow and the removed row reappears.
                return false
            }

            // Pure addition / rename / reorder — save and update the
            // session's enforcement snapshot so both services pick it up.
            viewModelScope.launch {
                repository.updatePolicies { list -> list.map { if (it.id == policy.id) policy else it } }
                val updatedSession = current!!.copy(
                    domains = policy.domains.toSet(),
                    packages = policy.apps.map { it.packageName }.toSet(),
                    partial = policy.partial,
                )
                repository.save(updatedSession)
            }
        } else {
            // No active session on this profile — plain save.
            viewModelScope.launch { repository.updatePolicies { list -> list.map { if (it.id == policy.id) policy else it } } }
        }
        return true
    }

    /**
     * Returns `true` when the edit removes a domain or app that the stored
     * version still has — i.e. it weakens enforcement.
     */
    private fun weakensEnforcement(old: BlockPolicy, new: BlockPolicy): Boolean {
        if (!new.domains.containsAll(old.domains)) return true
        val oldPackages = old.apps.map { it.packageName }.toSet()
        val newPackages = new.apps.map { it.packageName }.toSet()
        if (!newPackages.containsAll(oldPackages)) return true
        return !new.partial.containsAll(old.partial)
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
    fun createCommitmentShield(domains: Set<String>, packages: Set<String>, partial: Set<PartialRule> = emptySet()) {
        val current = commitmentShield.value
        val now = System.currentTimeMillis()
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        val bootNow = currentBootCount(getApplication())
        if (current != null && !current.isOver(now, elapsedNow, bootNow)) return
        val shield = CommitmentShield(
            startAt = now, endAt = now + CommitmentShield.DURATION_MS,
            domains = domains, packages = packages, createdAt = now,
            createdElapsedRealtime = elapsedNow, createdBootCount = bootNow, partial = partial,
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
            // And enabling bedtime (or picking a profile) mid-window should
            // start the locked session now, not wait for the next boundary.
            reconcileBedtimeSession(getApplication(), settings)
        }
    }

    /** Re-arms everything that depends on the cheat day or the schedules: the schedule alarm, Do Not Disturb, Bedtime. */
    private fun reconcileAutomation() {
        viewModelScope.launch {
            val settings = repository.bedtimeSettingsFlow.first()
            reconcileQuietNotifications(getApplication(), settings)
            reconcileBedtimeSession(getApplication(), settings)
        }
    }

    /** Returns false (and does nothing) when [dayStart] breaks the rules in [CheatDays.canSchedule]. */
    fun scheduleCheatDay(dayStart: Long): Boolean {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        if (!CheatDays.canSchedule(now, dayStart, cheatDay.value, zone)) return false
        viewModelScope.launch {
            repository.setCheatDay(CheatDays.forDay(dayStart, now, zone))
            SessionNotifier.sync(getApplication())
            reconcileAutomation()
        }
        return true
    }

    fun cancelCheatDay() {
        val current = cheatDay.value ?: return
        viewModelScope.launch {
            repository.setCheatDay(CheatDays.cancel(current, System.currentTimeMillis()))
            // A live cheat day ending early re-blocks at once: re-check the foreground and the notification.
            SessionNotifier.sync(getApplication())
            reconcileAutomation()
        }
    }

    /** Sets [minutes] on [limit] (0 removes it). Tighter counts now, looser from midnight: see [AppLimit.withMinutes]. */
    fun changeLimit(limit: AppLimit, minutes: Int) {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        viewModelScope.launch {
            repository.updateLimits { list ->
                val isNew = list.none { it.packageName == limit.packageName }
                val base = if (isNew) limit.copy(minutesPerDay = minutes) else list.first { it.packageName == limit.packageName }.withMinutes(minutes, now, zone)
                // Fold due changes and drop removed limits while we're here.
                (list.filterNot { it.packageName == limit.packageName } + base).mapNotNull { it.settled(now) }
            }
        }
    }

    fun addFrictionApp(app: AppRule) {
        viewModelScope.launch { repository.updateFrictionApps { cur -> if (cur.none { it.packageName == app.packageName }) cur + app else cur } }
    }

    fun removeFrictionApp(pkg: String) {
        viewModelScope.launch { repository.updateFrictionApps { l -> l.filterNot { it.packageName == pkg } } }
    }

    /**
     * For the Stats insight "you try X most at 11 PM": a schedule that blocks [app] for that hour, every day. It
     * uses a profile that already blocks the app, or makes one, so the schedule can't silently block nothing.
     */
    fun scheduleBlockFor(app: AppRule, hour: Int): Schedule {
        val policy = policies.value.firstOrNull { p -> p.apps.any { it.packageName == app.packageName } }
            ?: BlockPolicy(name = app.label, apps = listOf(app)).also { created -> viewModelScope.launch { repository.updatePolicies { it + created } } }
        return Schedule(
            name = "No ${app.label}", days = java.time.DayOfWeek.entries.toSet(),
            startMinute = hour * 60, endMinute = (hour + 1) % 24 * 60, policyId = policy.id,
        )
    }

    fun saveSchedule(schedule: Schedule) {
        viewModelScope.launch {
            repository.updateSchedules { list -> if (list.any { it.id == schedule.id }) list.map { if (it.id == schedule.id) schedule else it } else list + schedule }
            reconcileAutomation()
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            repository.updateSchedules { list -> list.filterNot { it.id == id } }
            reconcileAutomation()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repository.setOnboardingDone() }
    }

    /** Ignored past [Person.MAX], or if that number is already on the list. */
    fun addPerson(name: String, phone: String, lastTalkedAt: Long?) {
        viewModelScope.launch {
            repository.updatePeople { list ->
                if (list.size >= Person.MAX || list.any { it.phone == phone }) list
                else list + Person(name = name, phone = phone, lastTalkedAt = lastTalkedAt)
            }
        }
    }

    fun removePerson(id: String) {
        viewModelScope.launch { repository.updatePeople { list -> list.filterNot { it.id == id } } }
    }

    fun addGoal(text: String, priority: GoalPriority) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repository.updateGoals { it + Goal(text = clean, priority = priority) } }
    }

    fun removeGoal(id: String) {
        viewModelScope.launch { repository.updateGoals { list -> list.filterNot { it.id == id } } }
    }

    fun setLastTalked(id: String, lastTalkedAt: Long?) {
        viewModelScope.launch {
            repository.updatePeople { list -> list.map { if (it.id == id) it.copy(lastTalkedAt = lastTalkedAt) else it } }
        }
    }
}

/** 0 as a fallback is safe: it only ever collides with a real device's boot count if that device has never rebooted since this field started being recorded, in which case there's nothing to distinguish anyway. */
private fun currentBootCount(context: android.content.Context): Int =
    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
