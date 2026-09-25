package app.getnowfocus.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android counterpart of macOS AppBlocker: watches only which package comes to
 * the foreground (no window content) and bounces blocked apps.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var rules: ActiveRules? = null
    // Per-package last-logged time, so one open-attempt's several foreground
    // events don't multi-count in Stats. Not persisted: fine to reset with the service.
    private val lastBlockLogged = mutableMapOf<String, Long>()
    private val historyDao by lazy { HistoryDatabase.get(this).dao() }

    override fun onServiceConnected() {
        scope.launch {
            Enforcement.activeRulesFlow(SessionRepository(this@FocusAccessibilityService)).collect { rules = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val active = rules ?: return
        val now = System.currentTimeMillis()
        if (pkg == packageName || now >= active.endAt || pkg !in active.packages) return

        if (HistoryStats.shouldLogBlockEvent(now, lastBlockLogged[pkg])) {
            lastBlockLogged[pkg] = now
            scope.launch { historyDao.insertBlockEvent(BlockEventRow(packageName = pkg, timestampMillis = now)) }
        }

        // Home first, so Back from the block screen can't land in the blocked app.
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BlockedActivity.EXTRA_END_AT, active.endAt)
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
