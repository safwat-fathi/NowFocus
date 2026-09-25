package app.getnowfocus.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Fires at each bedtime boundary (wind-down, wake) to apply or clear Do Not
 * Disturb, and at sleep time to lock the screen once. Every firing
 * reschedules from scratch for tomorrow via [BedtimeScheduler.scheduleAll] -
 * simpler and more self-correcting than tracking which specific alarm this
 * was, and matches the DataStore-backed settings being the source of truth.
 */
class BedtimeAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BOUNDARY = "app.getnowfocus.android.BEDTIME_BOUNDARY"
        const val ACTION_SLEEP_LOCK = "app.getnowfocus.android.BEDTIME_SLEEP_LOCK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = SessionRepository(context)
                val settings = repo.bedtimeSettingsFlow.first()
                if (settings.enabled) {
                    when (intent.action) {
                        ACTION_BOUNDARY -> applyQuietState(context, settings)
                        ACTION_SLEEP_LOCK -> if (settings.lockAtSleep && Build.VERSION.SDK_INT >= 28) {
                            FocusAccessibilityService.instance?.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            )
                        }
                    }
                    BedtimeScheduler.scheduleAll(context, settings)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

private fun applyQuietState(context: Context, settings: BedtimeSettings) {
    if (!settings.quietNotifications) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (!nm.isNotificationPolicyAccessGranted) return
    val quiet = BedtimeSchedule.isQuietTimeNow(settings, System.currentTimeMillis(), ZoneId.systemDefault())
    // Never call setNotificationPolicy - that would overwrite the user's own DND exceptions.
    // Only touch the filter itself, and only flip it if it's still the state we'd have set,
    // so a manual change the user made mid-window isn't clobbered at the next boundary.
    val current = nm.currentInterruptionFilter
    if (quiet && current == NotificationManager.INTERRUPTION_FILTER_ALL) {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    } else if (!quiet && current == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }
}

object BedtimeScheduler {

    fun scheduleAll(context: Context, settings: BedtimeSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        if (!settings.enabled) {
            cancelAll(context)
            return
        }
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val windowToday = BedtimeSchedule.windowFor(settings, today, zone)
        // Next boundary is whichever of "starts" or "ends" hasn't happened yet.
        val nextBoundary = listOf(windowToday.first, windowToday.last + 1, BedtimeSchedule.windowFor(settings, today.plusDays(1), zone).first)
            .first { it > now }
        schedule(am, context, nextBoundary, BedtimeAlarmReceiver.ACTION_BOUNDARY, requestCode = 1)
        schedule(am, context, BedtimeSchedule.nextSleepTrigger(settings, now, zone), BedtimeAlarmReceiver.ACTION_SLEEP_LOCK, requestCode = 2)
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntentFor(context, BedtimeAlarmReceiver.ACTION_BOUNDARY, 1))
        am.cancel(pendingIntentFor(context, BedtimeAlarmReceiver.ACTION_SLEEP_LOCK, 2))
    }

    private fun schedule(am: AlarmManager, context: Context, triggerAtMillis: Long, action: String, requestCode: Int) {
        // Inexact: exact alarms need a permission that's off by default on recent
        // Android, and being off by a few minutes doesn't matter for this.
        am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAtMillis, pendingIntentFor(context, action, requestCode))
    }

    private fun pendingIntentFor(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, BedtimeAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
