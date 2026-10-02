package app.getnowfocus.android.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.getnowfocus.android.BuildConfig
import app.getnowfocus.android.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A sync pass while the app isn't open, every so often, so a session started on another device reaches this
 * phone without it being opened first. Only ever scheduled while signed in; signed out, no alarm exists and
 * the receiver makes no network call. Inexact: Android may batch it, and 15 minutes is a target, not a promise.
 */
object BackgroundSync {
    private const val REQUEST_CODE = 4
    private const val EVERY_MS = 15 * 60_000L

    fun userAgent() = "NowFocus-Android/${BuildConfig.VERSION_NAME}"
    fun deviceName() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(100)

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, BackgroundSyncReceiver::class.java).setAction(BackgroundSyncReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun arm(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, pending(context))
    }

    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context)) }

    /** One pass, built the same way the app builds its own. Quietly gives up when signed out or offline. */
    suspend fun once(context: Context) {
        val auth = DataStoreAuthStore(context)
        if (auth.load() == null) { cancel(context); return }
        val repo = SessionRepository(context)
        val engine = SyncEngine(
            RepositorySyncStore(context, repo), SyncApi(BuildConfig.SYNC_BASE_URL, userAgent(), auth),
            sessions = RepositorySessionSync(context, repo, deviceName()),
        )
        try { engine.syncOnce() } catch (_: Exception) { /* offline or signed out elsewhere: the next alarm tries again */ }
        if (auth.load() != null) arm(context) else cancel(context)
    }
}

class BackgroundSyncReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "app.getnowfocus.android.BACKGROUND_SYNC" }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { BackgroundSync.once(context) } finally { pending.finish() }
        }
    }
}
