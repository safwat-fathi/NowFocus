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
                if (Enforcement.shouldRun(repo)) Enforcement.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
