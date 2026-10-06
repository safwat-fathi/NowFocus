package app.getnowfocus.android

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.map
import app.getnowfocus.android.sync.Local
import app.getnowfocus.android.sync.SyncLogic
import app.getnowfocus.android.sync.SyncState

private val Context.sessionDataStore by preferencesDataStore(name = "focus_session")

/**
 * The latest session plus the policy list, in one DataStore. The UI and both
 * enforcement services read these same flows, so they can't drift apart.
 * Add Room when session history needs more than one row.
 */
class SessionRepository(context: Context) {

    private val store = context.applicationContext.sessionDataStore

    private object Keys {
        val ID = stringPreferencesKey("id")
        val POLICY_ID = stringPreferencesKey("policyId")
        val START_AT = longPreferencesKey("startAt")
        val END_AT = longPreferencesKey("endAt")
        val STATUS = stringPreferencesKey("status")
        val CREATED_AT = longPreferencesKey("createdAt")
        val DOMAINS = stringSetPreferencesKey("domains")
        val PACKAGES = stringSetPreferencesKey("packages")
        val PARTIAL = stringSetPreferencesKey("partial")
        val POLICIES = stringPreferencesKey("policies")
        val ENFORCEMENT_MODE = stringPreferencesKey("enforcementMode")
        val SESSION_TYPE = stringPreferencesKey("sessionType")
        val CANCELLED_AT = longPreferencesKey("cancelledAt")
        val VOICE_NOTE_PATH = stringPreferencesKey("voiceNotePath")
        val LIMITS = stringPreferencesKey("appLimits")
        val SITE_USAGE = stringPreferencesKey("siteUsage")
        val LIMIT_PASSES = stringPreferencesKey("limitPasses")
        val FRICTION_APPS = stringPreferencesKey("frictionApps")
        val SCHEDULES = stringPreferencesKey("schedules")
        val SCHEDULE_RUNS = stringPreferencesKey("scheduleRuns")
        val SESSION_PASSES = stringPreferencesKey("sessionPasses")
        val SESSION_ORIGIN = stringPreferencesKey("sessionOrigin")
        val SESSION_STARTED_ON = stringPreferencesKey("sessionStartedOn")
        val SESSION_POLICY_MODE = stringPreferencesKey("sessionPolicyMode")
        val SESSION_SYNC = stringPreferencesKey("sessionSync")
        val JOIN_REMOTE = booleanPreferencesKey("joinRemoteSessions")
        val CHEAT_START = longPreferencesKey("cheatStartAt")
        val CHEAT_END = longPreferencesKey("cheatEndAt")
        val CHEAT_CREATED = longPreferencesKey("cheatCreatedAt")
        val SHIELD_START_AT = longPreferencesKey("shieldStartAt")
        val SHIELD_END_AT = longPreferencesKey("shieldEndAt")
        val SHIELD_DOMAINS = stringSetPreferencesKey("shieldDomains")
        val SHIELD_PACKAGES = stringSetPreferencesKey("shieldPackages")
        val SHIELD_PARTIAL = stringSetPreferencesKey("shieldPartial")
        val SHIELD_CREATED_AT = longPreferencesKey("shieldCreatedAt")
        val SHIELD_CREATED_ELAPSED = longPreferencesKey("shieldCreatedElapsed")
        val SHIELD_CREATED_BOOT_COUNT = intPreferencesKey("shieldCreatedBootCount")
        val BEDTIME_WINDDOWN_MIN = intPreferencesKey("bedtimeWindDownMin")
        val BEDTIME_SLEEP_MIN = intPreferencesKey("bedtimeSleepMin")
        val BEDTIME_WAKE_MIN = intPreferencesKey("bedtimeWakeMin")
        val BEDTIME_ENABLED = booleanPreferencesKey("bedtimeEnabled")
        val BEDTIME_QUIET = booleanPreferencesKey("bedtimeQuietNotifications")
        val BEDTIME_LOCK = booleanPreferencesKey("bedtimeLockAtSleep")
        val BEDTIME_POLICY_ID = stringPreferencesKey("bedtimePolicyId")
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val ACCOUNT_LOCK = booleanPreferencesKey("accountLock")
        val PEOPLE = stringPreferencesKey("people")
        val GOALS = stringPreferencesKey("goals")
        // Sync bookkeeping (see sync/SyncLogic). Same DataStore as the data it describes so one edit covers both.
        val SYNC_STATE = stringPreferencesKey("syncState")
    }

    val sessionFlow: Flow<FocusSession?> = store.data.map(::readSession)

    private fun readSession(p: Preferences): FocusSession? {
        return FocusSession(
            id = p[Keys.ID] ?: return null,
            policyId = p[Keys.POLICY_ID] ?: return null,
            startAt = p[Keys.START_AT] ?: return null,
            endAt = p[Keys.END_AT] ?: return null,
            status = p[Keys.STATUS]?.let { FocusSessionStatus.valueOf(it) } ?: return null,
            createdAt = p[Keys.CREATED_AT] ?: return null,
            domains = p[Keys.DOMAINS] ?: emptySet(),
            packages = p[Keys.PACKAGES] ?: emptySet(),
            partial = BlockPolicy.partialFromNames(p[Keys.PARTIAL] ?: emptySet()),
            // Default, not return@map null: a session written before this field
            // existed must keep enforcing as NORMAL, not vanish from the flow.
            enforcementMode = p[Keys.ENFORCEMENT_MODE]?.let { EnforcementMode.valueOf(it) } ?: EnforcementMode.NORMAL,
            // Default, not return@map null: a session written before this field
            // existed keeps enforcing as a FOCUS session.
            sessionType = p[Keys.SESSION_TYPE]?.let { SessionType.valueOf(it) } ?: SessionType.FOCUS,
            cancelledAt = p[Keys.CANCELLED_AT],
            // Null for any session written before this field existed.
            voiceNotePath = p[Keys.VOICE_NOTE_PATH],
            passes = p[Keys.SESSION_PASSES]?.let { Passes.fromJson(it) } ?: emptyList(),
            origin = p[Keys.SESSION_ORIGIN]?.let { runCatching { SessionOrigin.valueOf(it) }.getOrNull() } ?: SessionOrigin.USER,
            startedOn = p[Keys.SESSION_STARTED_ON],
            // Absent in sessions written before whitelist mode: those closed the listed apps.
            policyMode = PolicyMode.entries.find { it.name == p[Keys.SESSION_POLICY_MODE] } ?: PolicyMode.BLOCKLIST,
        )
    }

    /** Adds a pass for [pkg] to the running session if [Passes.grant] allows it. Awaited, so the services see it before the app opens. */
    suspend fun grantPass(pkg: String, now: Long): Boolean {
        var granted = false
        store.edit { p ->
            val session = readSession(p)?.let { SessionEngine.evaluateState(it, now) } ?: return@edit
            val updated = Passes.grant(session, pkg, now) ?: return@edit
            p[Keys.SESSION_PASSES] = Passes.toJson(updated.passes)
            granted = true
        }
        return granted
    }

    /** Daily per-app time budgets. Device-local (usage is per phone). */
    val limitsFlow: Flow<List<AppLimit>> = store.data.map { p -> p[Keys.LIMITS]?.let { AppLimit.listFromJson(it) } ?: emptyList() }

    suspend fun updateLimits(transform: (List<AppLimit>) -> List<AppLimit>) {
        store.edit { p -> p[Keys.LIMITS] = AppLimit.listToJson(transform(p[Keys.LIMITS]?.let { AppLimit.listFromJson(it) } ?: emptyList())) }
    }

    /** Today's time per limited website ([SiteUsage]); only limited domains are ever stored, never what was browsed. */
    val siteUsageFlow: Flow<SiteUsage> = store.data.map { p -> p[Keys.SITE_USAGE]?.let { SiteUsage.fromJson(it) } ?: SiteUsage() }

    suspend fun addSiteUsage(today: String, credits: Map<String, Long>) {
        store.edit { p ->
            var u = p[Keys.SITE_USAGE]?.let { SiteUsage.fromJson(it) } ?: SiteUsage()
            credits.forEach { (key, ms) -> u = u.plus(today, key, ms) }
            p[Keys.SITE_USAGE] = u.toJson()
        }
    }

    /** Today's passes on used-up daily limits ([LimitPasses]). Device-local. */
    val limitPassesFlow: Flow<LimitPassState> = store.data.map { p -> p[Keys.LIMIT_PASSES]?.let { LimitPasses.fromJson(it) } ?: LimitPassState() }

    /** Grants a pass of [minutes] on the limit [key] if one may be given; true when it was. */
    suspend fun grantLimitPass(key: String, now: Long, minutes: Int): Boolean {
        var granted = false
        val day = SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())
        store.edit { p ->
            val current = p[Keys.LIMIT_PASSES]?.let { LimitPasses.fromJson(it) } ?: LimitPassState()
            val updated = LimitPasses.grant(current, key, now, day, minutes) ?: return@edit
            p[Keys.LIMIT_PASSES] = LimitPasses.toJson(updated)
            granted = true
        }
        return granted
    }

    /** Apps that ask for a pause before opening (see FrictionGate). Device-local. */
    val frictionAppsFlow: Flow<List<AppRule>> = store.data.map { p ->
        p[Keys.FRICTION_APPS]?.let { j -> JSONArray(j).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> AppRule(o.getString("pkg"), o.getString("label")) } } } } ?: emptyList()
    }

    suspend fun updateFrictionApps(transform: (List<AppRule>) -> List<AppRule>) {
        store.edit { p ->
            val current = p[Keys.FRICTION_APPS]?.let { j -> JSONArray(j).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> AppRule(o.getString("pkg"), o.getString("label")) } } } } ?: emptyList()
            p[Keys.FRICTION_APPS] = JSONArray().apply { transform(current).forEach { put(JSONObject().put("pkg", it.packageName).put("label", it.label)) } }.toString()
        }
    }

    /** Recurring sessions. Device-local for now (not synced). */
    val schedulesFlow: Flow<List<Schedule>> = store.data.map { p -> p[Keys.SCHEDULES]?.let { Schedule.listFromJson(it) } ?: emptyList() }

    suspend fun updateSchedules(transform: (List<Schedule>) -> List<Schedule>) {
        store.edit { p ->
            val next = transform(p[Keys.SCHEDULES]?.let { Schedule.listFromJson(it) } ?: emptyList())
            p[Keys.SCHEDULES] = Schedule.listToJson(next)
            // Forget runs of schedules that no longer exist.
            p[Keys.SCHEDULE_RUNS]?.let { r -> p[Keys.SCHEDULE_RUNS] = Schedule.runsToJson(Schedule.runsFromJson(r).filterKeys { id -> next.any { it.id == id } }) }
        }
    }

    val scheduleRunsFlow: Flow<Map<String, Long>> = store.data.map { p -> p[Keys.SCHEDULE_RUNS]?.let { Schedule.runsFromJson(it) } ?: emptyMap() }

    suspend fun noteScheduleRun(scheduleId: String, windowStart: Long) {
        store.edit { p ->
            val runs = p[Keys.SCHEDULE_RUNS]?.let { Schedule.runsFromJson(it) } ?: emptyMap()
            p[Keys.SCHEDULE_RUNS] = Schedule.runsToJson(runs + (scheduleId to windowStart))
        }
    }

    /** Whether this device joins sessions started on the account's other devices. On unless the user turns it off. */
    val joinRemoteFlow: Flow<Boolean> = store.data.map { p -> p[Keys.JOIN_REMOTE] ?: true }

    suspend fun setJoinRemote(on: Boolean) { store.edit { p -> p[Keys.JOIN_REMOTE] = on } }

    /** Session-sync bookkeeping (see sync/SessionSync): one JSON text, edited atomically. */
    suspend fun <R> editSessionSync(block: (String?) -> Pair<String?, R>): R {
        var result: R? = null
        store.edit { p ->
            val (next, r) = block(p[Keys.SESSION_SYNC])
            if (next == null) p.remove(Keys.SESSION_SYNC) else p[Keys.SESSION_SYNC] = next
            result = r
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    /** Device-local, never synced: a remote write must not be able to loosen blocking. Null if none was ever set. */
    val cheatDayFlow: Flow<CheatDay?> = store.data.map { p ->
        CheatDay(
            startAt = p[Keys.CHEAT_START] ?: return@map null,
            endAt = p[Keys.CHEAT_END] ?: return@map null,
            createdAt = p[Keys.CHEAT_CREATED] ?: return@map null,
        )
    }

    suspend fun setCheatDay(cheat: CheatDay?) {
        store.edit { p ->
            if (cheat == null) {
                p.remove(Keys.CHEAT_START); p.remove(Keys.CHEAT_END); p.remove(Keys.CHEAT_CREATED)
            } else {
                p[Keys.CHEAT_START] = cheat.startAt; p[Keys.CHEAT_END] = cheat.endAt; p[Keys.CHEAT_CREATED] = cheat.createdAt
            }
        }
    }

    val policiesFlow: Flow<List<BlockPolicy>> = store.data.map { p ->
        p[Keys.POLICIES]?.let { BlockPolicy.listFromJson(it) } ?: emptyList()
    }

    /** Null once there's never been a shield, or its row was cleared by cancelling within the grace period. */
    val commitmentShieldFlow: Flow<CommitmentShield?> = store.data.map { p ->
        CommitmentShield(
            startAt = p[Keys.SHIELD_START_AT] ?: return@map null,
            endAt = p[Keys.SHIELD_END_AT] ?: return@map null,
            domains = p[Keys.SHIELD_DOMAINS] ?: emptySet(),
            packages = p[Keys.SHIELD_PACKAGES] ?: emptySet(),
            createdAt = p[Keys.SHIELD_CREATED_AT] ?: return@map null,
            createdElapsedRealtime = p[Keys.SHIELD_CREATED_ELAPSED] ?: return@map null,
            createdBootCount = p[Keys.SHIELD_CREATED_BOOT_COUNT] ?: return@map null,
            partial = BlockPolicy.partialFromNames(p[Keys.SHIELD_PARTIAL] ?: emptySet()),
        )
    }

    /** Defaults (see BedtimeSettings) until the user has ever saved their own. */
    val bedtimeSettingsFlow: Flow<BedtimeSettings> = store.data.map { p -> readBedtime(p) }

    private fun readBedtime(p: Preferences): BedtimeSettings {
        val defaults = BedtimeSettings()
        return BedtimeSettings(
            windDownMinute = p[Keys.BEDTIME_WINDDOWN_MIN] ?: defaults.windDownMinute,
            sleepMinute = p[Keys.BEDTIME_SLEEP_MIN] ?: defaults.sleepMinute,
            wakeMinute = p[Keys.BEDTIME_WAKE_MIN] ?: defaults.wakeMinute,
            enabled = p[Keys.BEDTIME_ENABLED] ?: defaults.enabled,
            quietNotifications = p[Keys.BEDTIME_QUIET] ?: defaults.quietNotifications,
            lockAtSleep = p[Keys.BEDTIME_LOCK] ?: defaults.lockAtSleep,
            policyId = p[Keys.BEDTIME_POLICY_ID],
        )
    }

    private fun writeBedtime(p: MutablePreferences, settings: BedtimeSettings) {
        p[Keys.BEDTIME_WINDDOWN_MIN] = settings.windDownMinute
        p[Keys.BEDTIME_SLEEP_MIN] = settings.sleepMinute
        p[Keys.BEDTIME_WAKE_MIN] = settings.wakeMinute
        p[Keys.BEDTIME_ENABLED] = settings.enabled
        p[Keys.BEDTIME_QUIET] = settings.quietNotifications
        p[Keys.BEDTIME_LOCK] = settings.lockAtSleep
        if (settings.policyId != null) p[Keys.BEDTIME_POLICY_ID] = settings.policyId else p.remove(Keys.BEDTIME_POLICY_ID)
    }

    suspend fun saveBedtimeSettings(settings: BedtimeSettings) {
        store.edit { p ->
            val old = readBedtime(p)
            writeBedtime(p, settings)
            stampSync(p) { SyncLogic.stampBedtime(it, old, settings, System.currentTimeMillis()) }
        }
    }

    /** Empty until the user adds someone - the block screen then shows no card. */
    val peopleFlow: Flow<List<Person>> = store.data.map { p ->
        p[Keys.PEOPLE]?.let { Person.listFromJson(it) } ?: emptyList()
    }

    /** Read-modify-write inside one edit, like [updatePolicies]. */
    suspend fun updatePeople(transform: (List<Person>) -> List<Person>) {
        store.edit { p ->
            val current = p[Keys.PEOPLE]?.let { Person.listFromJson(it) } ?: emptyList()
            p[Keys.PEOPLE] = Person.listToJson(transform(current))
        }
    }

    /** Empty until the user adds a goal - the Strict pause and block screen then show none. */
    val goalsFlow: Flow<List<Goal>> = store.data.map { p ->
        p[Keys.GOALS]?.let { Goal.listFromJson(it) } ?: emptyList()
    }

    suspend fun updateGoals(transform: (List<Goal>) -> List<Goal>) {
        store.edit { p ->
            val current = p[Keys.GOALS]?.let { Goal.listFromJson(it) } ?: emptyList()
            p[Keys.GOALS] = Goal.listToJson(transform(current))
        }
    }

    val onboardingDoneFlow: Flow<Boolean> = store.data.map { p -> p[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone() {
        store.edit { p -> p[Keys.ONBOARDING_DONE] = true }
    }

    /** Device-local fingerprint lock on the Account screen. Not part of syncState; never synced. */
    val accountLockFlow: Flow<Boolean> = store.data.map { p -> p[Keys.ACCOUNT_LOCK] ?: false }

    suspend fun setAccountLock(on: Boolean) {
        store.edit { p -> p[Keys.ACCOUNT_LOCK] = on }
    }

    suspend fun save(session: FocusSession) {
        store.edit { p ->
            p[Keys.ID] = session.id
            p[Keys.POLICY_ID] = session.policyId
            p[Keys.START_AT] = session.startAt
            p[Keys.END_AT] = session.endAt
            p[Keys.STATUS] = session.status.name
            p[Keys.CREATED_AT] = session.createdAt
            p[Keys.DOMAINS] = session.domains
            p[Keys.PACKAGES] = session.packages
            p[Keys.PARTIAL] = session.partial.map { it.name }.toSet()
            p[Keys.ENFORCEMENT_MODE] = session.enforcementMode.name
            p[Keys.SESSION_TYPE] = session.sessionType.name
            if (session.cancelledAt != null) p[Keys.CANCELLED_AT] = session.cancelledAt else p.remove(Keys.CANCELLED_AT)
            if (session.voiceNotePath != null) p[Keys.VOICE_NOTE_PATH] = session.voiceNotePath else p.remove(Keys.VOICE_NOTE_PATH)
            if (session.passes.isNotEmpty()) p[Keys.SESSION_PASSES] = Passes.toJson(session.passes) else p.remove(Keys.SESSION_PASSES)
            p[Keys.SESSION_ORIGIN] = session.origin.name
            if (session.startedOn != null) p[Keys.SESSION_STARTED_ON] = session.startedOn else p.remove(Keys.SESSION_STARTED_ON)
            p[Keys.SESSION_POLICY_MODE] = session.policyMode.name
        }
    }

    /** Read-modify-write inside one edit, so rapid edits can't overwrite each other. */
    suspend fun updatePolicies(transform: (List<BlockPolicy>) -> List<BlockPolicy>) {
        store.edit { p ->
            val current = readPolicies(p)
            val next = transform(current)
            p[Keys.POLICIES] = BlockPolicy.listToJson(next)
            // The only place a profile is created, edited or deleted: record it for sync in this same edit.
            stampSync(p) { SyncLogic.stampPolicies(it, current, next, System.currentTimeMillis()) }
        }
    }

    private fun readPolicies(p: Preferences): List<BlockPolicy> = p[Keys.POLICIES]?.let { BlockPolicy.listFromJson(it) } ?: emptyList()

    /** No-op until the device has been linked to an account, so a device that never syncs pays nothing. */
    private fun stampSync(p: MutablePreferences, f: (SyncState) -> SyncState) {
        val text = p[Keys.SYNC_STATE] ?: return
        val state = SyncState.decode(text)
        if (state.userId == null) return
        val next = f(state)
        if (next != state) p[Keys.SYNC_STATE] = next.encode()
    }

    /**
     * Reads policies, bedtime and sync bookkeeping, lets [block] decide, and writes back whatever changed, all in
     * one DataStore edit. Writes made here are NOT stamped as user edits: this is how server data is applied.
     */
    suspend fun <R> syncTransaction(block: (Local) -> Pair<Local, R>): R {
        var result: R? = null
        store.edit { p ->
            val before = Local(readPolicies(p), readBedtime(p), SyncState.decode(p[Keys.SYNC_STATE]))
            val (after, r) = block(before)
            if (after.policies != before.policies) p[Keys.POLICIES] = BlockPolicy.listToJson(after.policies)
            if (after.bedtime != before.bedtime) writeBedtime(p, after.bedtime)
            if (after.state != before.state) p[Keys.SYNC_STATE] = after.state.encode()
            result = r
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    suspend fun seedDefaultPolicyIfNeeded() {
        store.edit { p ->
            if (p[Keys.POLICIES] == null) p[Keys.POLICIES] = BlockPolicy.listToJson(listOf(BlockPolicy.DEFAULT))
        }
    }

    suspend fun saveCommitmentShield(shield: CommitmentShield) {
        store.edit { p ->
            p[Keys.SHIELD_START_AT] = shield.startAt
            p[Keys.SHIELD_END_AT] = shield.endAt
            p[Keys.SHIELD_DOMAINS] = shield.domains
            p[Keys.SHIELD_PACKAGES] = shield.packages
            p[Keys.SHIELD_PARTIAL] = shield.partial.map { it.name }.toSet()
            p[Keys.SHIELD_CREATED_AT] = shield.createdAt
            p[Keys.SHIELD_CREATED_ELAPSED] = shield.createdElapsedRealtime
            p[Keys.SHIELD_CREATED_BOOT_COUNT] = shield.createdBootCount
        }
    }

    /** Only reachable during the grace period - see [CommitmentShield.canCancel]. */
    suspend fun clearCommitmentShield() {
        store.edit { p ->
            p.remove(Keys.SHIELD_START_AT)
            p.remove(Keys.SHIELD_END_AT)
            p.remove(Keys.SHIELD_DOMAINS)
            p.remove(Keys.SHIELD_PACKAGES)
            p.remove(Keys.SHIELD_PARTIAL)
            p.remove(Keys.SHIELD_CREATED_AT)
        }
    }
}
