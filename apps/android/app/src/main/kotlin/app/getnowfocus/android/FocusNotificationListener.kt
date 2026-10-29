package app.getnowfocus.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds back notifications from blocked apps: snoozed now, back when the block ends. The decision is the same
 * one the accessibility service makes, [ActiveRules.windowsBlocking], so a session, a schedule, Bedtime, the
 * Shield and allow-only mode all count. Daily limits and partial blocks do not.
 *
 * Only the package, key, clearable flag and category are read, never a notification's text; the disclosure
 * dialog promises exactly that. Ongoing notifications (music, navigation) can't be snoozed, calls and alarms
 * are left alone so a block never makes one unanswerable.
 *
 * Android tells a listener about a notification after it has started alerting, so a short buzz can still happen.
 * ponytail: snoozed to the window's end. If an expired snooze re-posts silently (device check 1), chunk it to
 * ten minutes so an early end or a pass doesn't leave notifications waiting for the original end.
 */
class FocusNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val essentials by lazy { Essentials(this) }
    @Volatile private var rules = ActiveRules()
    private var rulesJob: Job? = null

    override fun onListenerConnected() {
        // This access is what lets the accessibility service list media sessions, and granting it is no rules change,
        // so tell it to look again: a video already playing in a blocked app's floating window gets paused now.
        FocusAccessibilityService.instance?.pauseBlockedMedia()
        val repo = SessionRepository(this)
        rulesJob?.cancel()
        rulesJob = scope.launch {
            Enforcement.activeRulesFlow(repo).collect {
                rules = it
                // The listener is bound from boot, so a session that starts later finds a full shade to clear.
                runCatching { activeNotifications.forEach(::hold) }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = hold(sbn)

    private fun hold(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !sbn.isClearable || sbn.notification.category in KEEP) return
        val now = System.currentTimeMillis()
        val until = rules.holdUntil(sbn.packageName, now, essentials::exempt) ?: return
        runCatching { snoozeNotification(sbn.key, until - now + SLACK_MS) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val KEEP = setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM)
        /** Past the end, so the notification doesn't come back a moment before the window reads as over. */
        const val SLACK_MS = 1_000L
    }
}
