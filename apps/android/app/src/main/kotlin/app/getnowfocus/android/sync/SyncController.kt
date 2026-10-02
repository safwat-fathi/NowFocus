package app.getnowfocus.android.sync

import android.content.Context
import app.getnowfocus.android.BedtimeScheduler
import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.FocusSessionStatus
import app.getnowfocus.android.SessionRepository
import app.getnowfocus.android.reconcileBedtimeSession
import app.getnowfocus.android.reconcileQuietNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [SyncStore] over the real DataStore. */
class RepositorySyncStore(private val context: Context, private val repository: SessionRepository) : SyncStore {
    override suspend fun <R> transact(block: (Local) -> Pair<Local, R>): R = repository.syncTransaction(block)

    override suspend fun referencedPolicyIds(): Set<String> {
        val ids = HashSet<String>()
        repository.bedtimeSettingsFlow.first().policyId?.let { ids += it.lowercase() }
        repository.sessionFlow.first()?.let { s ->
            if (s.status == FocusSessionStatus.SCHEDULED || s.status == FocusSessionStatus.ACTIVE) ids += s.policyId.lowercase()
        }
        return ids
    }

    // Same side effects as SessionViewModel.saveBedtimeSettings: server data must reach the alarms, not just the DataStore.
    override suspend fun onBedtimeApplied(settings: BedtimeSettings) {
        BedtimeScheduler.scheduleAll(context, settings)
        reconcileQuietNotifications(context, settings)
        reconcileBedtimeSession(context, settings)
    }
}

data class SyncStatus(
    /** False until the stored sign-in has been read, so the UI doesn't flash "signed out". */
    val loaded: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    /** Human-readable, e.g. "Offline: will retry". Null when all is well. */
    val problem: String? = null,
    /** Changes the server refused (they stay on this device and are retried only after you edit them). */
    val rejected: Int = 0,
)

/**
 * Owns the account and keeps [SyncEngine] running while the app is open: a pass after sign-in, after local edits,
 * after a WebSocket nudge, when the app returns to the foreground, and on a growing delay after a failure.
 * Signed out, it makes no network calls at all.
 */
class SyncController(
    private val scope: CoroutineScope,
    private val engine: SyncEngine,
    private val api: SyncApi,
    private val auth: AuthStore,
    private val store: SyncStore,
    private val socket: SyncSocket,
    /** Emits whenever local profiles or bedtime change (the first emission starts the first pass). */
    private val localChanges: Flow<*>,
    private val deviceName: String,
    /** True when the device is linked to an account and a background pass should be scheduled; false to cancel it. */
    private val backgroundSync: (Boolean) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val foreground = MutableStateFlow(false)
    private var session: Job? = null

    fun start() {
        scope.launch {
            val stored = auth.load()
            _status.update { it.copy(loaded = true, signedIn = stored != null, email = stored?.email) }
            if (stored != null) begin()
        }
    }

    /** Foreground, "Sync now", etc. */
    fun syncNow() { trigger.trySend(Unit) }

    /**
     * The realtime socket only runs while the app is on screen (the process can outlive the UI for hours, kept alive
     * by the blocking services); passes still run on edits, on return to the foreground and by retry.
     */
    fun setForeground(on: Boolean) { foreground.value = on }

    /** Returns an error message for the user, or null on success. */
    suspend fun signIn(email: String, password: String, createAccount: Boolean): String? = try {
        val s = if (createAccount) api.register(email, password, deviceName) else api.login(email, password, deviceName)
        // A different account than before starts clean; the same one resumes where it left off.
        store.transact { l -> l.copy(state = SyncLogic.link(l.state, s.userId)) to Unit }
        _status.update { it.copy(loaded = true, signedIn = true, email = s.email, problem = null) }
        begin()
        null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { friendly(e) }

    /** Local data and the Commitment Shield are never touched: signing out only forgets the tokens. */
    suspend fun signOut() {
        backgroundSync(false)
        session?.cancel()
        api.logout()
        _status.update { SyncStatus(loaded = true) }
    }

    /** Deletes the account and its server data; this phone keeps everything it has. Returns an error message or null. */
    suspend fun deleteAccount(password: String): String? = try {
        api.deleteAccount(password)
        backgroundSync(false)
        session?.cancel()
        store.transact { l -> l.copy(state = SyncLogic.unlink()) to Unit }
        _status.update { SyncStatus(loaded = true) }
        null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { friendly(e) }

    suspend fun devices(): List<DeviceInfo> = api.devices()

    suspend fun revokeDevice(id: String): String? = try { api.revokeDevice(id); null } catch (e: CancellationException) { throw e } catch (e: Exception) { friendly(e) }

    // ----------------------------------------------------------------

    private fun begin() {
        backgroundSync(true)
        session?.cancel()
        session = scope.launch {
            launch { localChanges.collect { trigger.trySend(Unit) } }
            launch { foreground.collectLatest { on -> if (on) socketLoop() } }
            passLoop()
        }
    }

    private suspend fun CoroutineScope.passLoop() {
        var backoff = 0L
        var first = true
        trigger.trySend(Unit)
        for (t in trigger) {
            if (!first) delay(DEBOUNCE_MS)   // coalesce a burst of edits into one pass
            first = false
            trigger.tryReceive()
            _status.update { it.copy(syncing = true) }
            try {
                val report = engine.syncOnce()
                backoff = 0
                _status.update { it.copy(syncing = false, lastSyncedAt = clock(), problem = null, rejected = report.rejected) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpired) {
                expired(); return
            } catch (e: Exception) {
                backoff = if (backoff == 0L) 5_000 else minOf(backoff * 2, 300_000)
                _status.update { it.copy(syncing = false, problem = if (e is NetworkException) "Offline. Will retry." else friendly(e)) }
                val wait = backoff
                launch { delay(wait); trigger.trySend(Unit) }
            }
        }
    }

    private suspend fun socketLoop() {
        var wait = 1_000L
        var refused: String? = null   // the token the server just rejected: the next connection must use a fresh one
        while (true) {
            try {
                val token = refused?.let { api.refreshedAccessToken(it) } ?: api.accessTokenForSocket()
                refused = null
                socket.connect(token).collect { ev ->
                    when (ev) {
                        is SocketEvent.Changes -> { wait = 1_000; if (ev.cursor > store.transact { l -> l to l.state.cursor }) trigger.trySend(Unit) }
                        SocketEvent.Revoked -> throw AuthExpired()
                        is SocketEvent.Closed -> when (ev.code) {
                            4403 -> throw AuthExpired()
                            4401, 401 -> refused = token   // access tokens last 15 minutes; a long-lived socket outlives one
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpired) {
                expired(); return
            } catch (_: Exception) { /* offline etc.: just reconnect */ }
            delay(wait)
            wait = minOf(wait * 2, 60_000)
        }
    }

    private suspend fun expired() {
        backgroundSync(false)
        api.forget()
        _status.update { SyncStatus(loaded = true, problem = "This device was signed out. Sign in again to keep syncing.") }
        session?.cancel()
    }

    private fun friendly(e: Exception): String = when {
        e is NetworkException -> "Can't reach NowFocus. Check your connection and try again."
        e is AuthExpired -> "Your session ended. Sign in again."
        e is ApiException && e.code == "invalid_credentials" -> "Wrong email or password."
        e is ApiException && e.code == "email_taken" -> "An account with this email already exists. Sign in instead."
        e is ApiException && e.code == "wrong_password" -> "Wrong password."
        e is ApiException && e.status == 429 -> "Too many attempts. Wait a minute and try again."
        e is ApiException && e.status in 500..599 -> "NowFocus is having trouble right now. Try again shortly."
        e is ApiException -> e.message ?: "Something went wrong."
        else -> e.message ?: "Something went wrong."
    }

    private companion object { const val DEBOUNCE_MS = 1_500L }
}
