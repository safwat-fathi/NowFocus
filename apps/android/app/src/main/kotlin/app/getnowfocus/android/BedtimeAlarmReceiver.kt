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
 * Fires at each bedtime boundary (wind-down, wake) to reconcile Do Not
 * Disturb, and at sleep time to lock the screen once. Every firing
 * reschedules from scratch for tomorrow via [BedtimeScheduler.scheduleAll] -
 * simpler and more self-correcting than tracking which specific alarm this
 * was, and matches the DataStore-backed settings being the source of truth.
 *
 * [reconcileQuietNotifications] is also called from BootReceiver, from
 * SessionViewModel on save and on app init - not just from here - so
 * disabling Bedtime, turning its toggle off, a force-stop, or a reboot all
 * restore the filter immediately rather than leaving it stuck quiet until
 * the next scheduled alarm happens to fire.
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
                reconcileQuietNotifications(context, settings)
                if (settings.enabled && intent.action == ACTION_SLEEP_LOCK && settings.lockAtSleep && Build.VERSION.SDK_INT >= 28) {
                    FocusAccessibilityService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    )
                }
                BedtimeScheduler.scheduleAll(context, settings)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Reconciles the interruption filter to what it should be right now. Safe to
 * call unconditionally and often: [BedtimeSchedule.decideQuietFilter] only
 * ever moves the filter between ALL and PRIORITY, and only away from
 * PRIORITY when it's the current value - so it never touches a filter this
 * function didn't itself have a hand in.
 */
fun reconcileQuietNotifications(context: Context, settings: BedtimeSettings) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (!nm.isNotificationPolicyAccessGranted) return
    val now = System.currentTimeMillis()
    val currentIsPriority = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
    when (BedtimeSchedule.decideQuietFilter(settings, now, ZoneId.systemDefault(), currentIsPriority)) {
        QuietDecision.SET_PRIORITY -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        QuietDecision.RESTORE_ALL -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        QuietDecision.NONE -> {}
    }
}

object BedtimeScheduler {

    fun scheduleAll(context: Context, settings: BedtimeSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (!settings.enabled) {
            cancelAll(context)
            return
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        schedule(am, context, BedtimeSchedule.nextBoundary(settings, now, zone), BedtimeAlarmReceiver.ACTION_BOUNDARY, requestCode = 1)
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
        // RTC_WAKEUP (not RTC): a non-wakeup alarm only delivers once the
        // device next wakes for some other reason, which could be hours late
        // and is exactly the kind of delay that leaves the filter stuck.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntentFor(context, action, requestCode))
    }

    private fun pendingIntentFor(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, BedtimeAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
