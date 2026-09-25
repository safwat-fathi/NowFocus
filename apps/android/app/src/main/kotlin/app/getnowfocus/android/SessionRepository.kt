package app.getnowfocus.android

import android.content.Context
import androidx.datastore.preferences.core.edit
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
        )
    }

    val policiesFlow: Flow<List<BlockPolicy>> = store.data.map { p ->
        p[Keys.POLICIES]?.let { BlockPolicy.listFromJson(it) } ?: emptyList()
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
}
