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
 * Bedtime's alarms and restarts enforcement if the Commitment Shield is
 * still supposed to be running - the same recovery SessionViewModel.init
 * does on ordinary process death, just reachable without the app being
 * opened first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = SessionRepository(context)
                BedtimeScheduler.scheduleAll(context, repo.bedtimeSettingsFlow.first())
                val now = System.currentTimeMillis()
                val shieldActive = repo.commitmentShieldFlow.first()?.let { it.endAt > now } ?: false
                if (shieldActive) Enforcement.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
