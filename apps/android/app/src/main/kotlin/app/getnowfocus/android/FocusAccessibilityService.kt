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

    override fun onServiceConnected() {
        scope.launch {
            Enforcement.activeRulesFlow(SessionRepository(this@FocusAccessibilityService)).collect { rules = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val active = rules ?: return
        if (pkg == packageName || System.currentTimeMillis() >= active.endAt || pkg !in active.packages) return

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
