package app.getnowfocus.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        val POLICIES = stringPreferencesKey("policies")
        val ENFORCEMENT_MODE = stringPreferencesKey("enforcementMode")
        val CANCELLED_AT = longPreferencesKey("cancelledAt")
        val SHIELD_START_AT = longPreferencesKey("shieldStartAt")
        val SHIELD_END_AT = longPreferencesKey("shieldEndAt")
        val SHIELD_DOMAINS = stringSetPreferencesKey("shieldDomains")
        val SHIELD_PACKAGES = stringSetPreferencesKey("shieldPackages")
        val SHIELD_CREATED_AT = longPreferencesKey("shieldCreatedAt")
        val SHIELD_CREATED_ELAPSED = longPreferencesKey("shieldCreatedElapsed")
        val SHIELD_CREATED_BOOT_COUNT = intPreferencesKey("shieldCreatedBootCount")
        val BEDTIME_WINDDOWN_MIN = intPreferencesKey("bedtimeWindDownMin")
        val BEDTIME_SLEEP_MIN = intPreferencesKey("bedtimeSleepMin")
        val BEDTIME_WAKE_MIN = intPreferencesKey("bedtimeWakeMin")
        val BEDTIME_ENABLED = booleanPreferencesKey("bedtimeEnabled")
        val BEDTIME_QUIET = booleanPreferencesKey("bedtimeQuietNotifications")
        val BEDTIME_LOCK = booleanPreferencesKey("bedtimeLockAtSleep")
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
    }

    val sessionFlow: Flow<FocusSession?> = store.data.map { p ->
        FocusSession(
            id = p[Keys.ID] ?: return@map null,
            policyId = p[Keys.POLICY_ID] ?: return@map null,
            startAt = p[Keys.START_AT] ?: return@map null,
            endAt = p[Keys.END_AT] ?: return@map null,
            status = p[Keys.STATUS]?.let { FocusSessionStatus.valueOf(it) } ?: return@map null,
            createdAt = p[Keys.CREATED_AT] ?: return@map null,
            domains = p[Keys.DOMAINS] ?: emptySet(),
            packages = p[Keys.PACKAGES] ?: emptySet(),
            // Default, not return@map null: a session written before this field
            // existed must keep enforcing as NORMAL, not vanish from the flow.
            enforcementMode = p[Keys.ENFORCEMENT_MODE]?.let { EnforcementMode.valueOf(it) } ?: EnforcementMode.NORMAL,
            cancelledAt = p[Keys.CANCELLED_AT],
        )
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
        )
    }

    /** Defaults (see BedtimeSettings) until the user has ever saved their own. */
    val bedtimeSettingsFlow: Flow<BedtimeSettings> = store.data.map { p ->
        val defaults = BedtimeSettings()
        BedtimeSettings(
            windDownMinute = p[Keys.BEDTIME_WINDDOWN_MIN] ?: defaults.windDownMinute,
            sleepMinute = p[Keys.BEDTIME_SLEEP_MIN] ?: defaults.sleepMinute,
            wakeMinute = p[Keys.BEDTIME_WAKE_MIN] ?: defaults.wakeMinute,
            enabled = p[Keys.BEDTIME_ENABLED] ?: defaults.enabled,
            quietNotifications = p[Keys.BEDTIME_QUIET] ?: defaults.quietNotifications,
            lockAtSleep = p[Keys.BEDTIME_LOCK] ?: defaults.lockAtSleep,
        )
    }

    suspend fun saveBedtimeSettings(settings: BedtimeSettings) {
        store.edit { p ->
            p[Keys.BEDTIME_WINDDOWN_MIN] = settings.windDownMinute
            p[Keys.BEDTIME_SLEEP_MIN] = settings.sleepMinute
            p[Keys.BEDTIME_WAKE_MIN] = settings.wakeMinute
            p[Keys.BEDTIME_ENABLED] = settings.enabled
            p[Keys.BEDTIME_QUIET] = settings.quietNotifications
            p[Keys.BEDTIME_LOCK] = settings.lockAtSleep
        }
    }

    val onboardingDoneFlow: Flow<Boolean> = store.data.map { p -> p[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone() {
        store.edit { p -> p[Keys.ONBOARDING_DONE] = true }
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
            p[Keys.ENFORCEMENT_MODE] = session.enforcementMode.name
            if (session.cancelledAt != null) p[Keys.CANCELLED_AT] = session.cancelledAt else p.remove(Keys.CANCELLED_AT)
        }
    }

    /** Read-modify-write inside one edit, so rapid edits can't overwrite each other. */
    suspend fun updatePolicies(transform: (List<BlockPolicy>) -> List<BlockPolicy>) {
        store.edit { p ->
            val current = p[Keys.POLICIES]?.let { BlockPolicy.listFromJson(it) } ?: emptyList()
            p[Keys.POLICIES] = BlockPolicy.listToJson(transform(current))
        }
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
            p.remove(Keys.SHIELD_CREATED_AT)
        }
    }
}
