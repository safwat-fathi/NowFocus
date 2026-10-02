package app.getnowfocus.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AlarmManager and the VPN service don't survive a reboot. This re-arms
 * Bedtime's alarms and restarts enforcement if a session OR the Commitment
 * Shield is still supposed to be running - the same recovery
 * SessionViewModel.init does on ordinary process death (via the shared
 * [Enforcement.shouldRun], after the two checks were once found to have
 * drifted apart - this one checked only the shield), just reachable without
 * the app being opened first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = SessionRepository(context)
                val settings = repo.bedtimeSettingsFlow.first()
                BedtimeScheduler.scheduleAll(context, settings)
                reconcileQuietNotifications(context, settings)
                // A reboot mid-window should re-arm the nightly locked session too.
                reconcileBedtimeSession(context, settings)
                if (Enforcement.shouldRun(repo)) Enforcement.start(context)
                // Alarms don't survive a reboot: a signed-in phone resumes its background sync passes.
                if (app.getnowfocus.android.sync.DataStoreAuthStore(context).load() != null) app.getnowfocus.android.sync.BackgroundSync.arm(context)
            } finally {
                pending.finish()
            }
        }
    }
}
