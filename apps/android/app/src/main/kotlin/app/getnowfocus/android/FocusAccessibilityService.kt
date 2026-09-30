package app.getnowfocus.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android counterpart of macOS AppBlocker. Two jobs:
 *  - bounce blocked apps, using only which package comes to the foreground; and
 *  - partial blocking: while a session/shield has partial rules live, and only while YouTube,
 *    Facebook, Instagram or X is in front, read that app's screen to spot Shorts / Reels /
 *    recommendation lists. Experimental: the screen signatures are unverified (see PartialSignatures).
 * Screen content is read only in that case, is matched in memory and never stored or sent anywhere.
 */
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        // Bedtime's sleep-time lock needs a live AccessibilityService instance to
        // call performGlobalAction on (it's not a static API) - null whenever
        // Accessibility isn't enabled, in which case the lock silently doesn't fire.
        @Volatile var instance: FocusAccessibilityService? = null
            private set

        private const val CHECK_DELAY_MS = 150L
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 30
        private const val SYSTEM_UI = "com.android.systemui"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var rules: ActiveRules = ActiveRules()
    // Per-package last-logged time, so one open-attempt's several foreground
    // events don't multi-count in Stats. Not persisted: fine to reset with the service.
    private val lastBlockLogged = mutableMapOf<String, Long>()
    private val historyDao by lazy { HistoryDatabase.get(this).dao() }

    private var checkJob: Job? = null
    private var expiryJob: Job? = null
    private var contentEventsOn = false
    private val leaveGate = LeaveGate()
    private val matchLog = MatchLogGate()
    private var overlay: View? = null

    override fun onServiceConnected() {
        instance = this
        scope.launch {
            Enforcement.activeRulesFlow(SessionRepository(this@FocusAccessibilityService)).collect { applyRules(it) }
        }
    }

    /**
     * Content-change events fire for every app on every layout change, and having them on measurably
     * slows NowFocus's own scrolling, so the subscription is on only while a partial rule is live
     * (the XML declares just window-state events). Re-evaluated at each window's expiry, which also
     * drops the cover when a session ends without any further event arriving.
     */
    private fun applyRules(r: ActiveRules) {
        rules = r
        val now = System.currentTimeMillis()
        val partialLive = r.livePartial(now).isNotEmpty()
        if (!partialLive) { clearOverlay(); matchLog.clear() }
        if (partialLive != contentEventsOn) {
            serviceInfo = serviceInfo?.apply {
                eventTypes = if (partialLive) eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    else eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
                flags = if (partialLive) flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    else flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
            }
            contentEventsOn = partialLive
        }
        expiryJob?.cancel()
        r.nextExpiryAfter(now)?.let { end ->
            expiryJob = scope.launch { delay(end - now + 50); applyRules(rules) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (blockApp(pkg)) return
                // The cover is a system-level window: drop it once the user is somewhere else.
                if (pkg !in PartialSignatures.PACKAGES && pkg != SYSTEM_UI) { clearOverlay(); matchLog.clear() }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {}
            else -> return
        }
        if (pkg in PartialSignatures.PACKAGES) scheduleCheck(pkg)
    }

    /** Whole-app block: returns true when [pkg] was bounced. */
    private fun blockApp(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val matching = rules.windowsBlocking(pkg, now)
        if (matching.isEmpty()) return false
        // The Commitment Shield is the more restrictive source when both match - see windowsBlocking.
        val bySource = matching.sortedByDescending { it.source == BlockSource.COMMITMENT_SHIELD }
        val blocking = bySource.first()

        logBlock(pkg, now)

        // Home first, so Back from the block screen can't land in the blocked app.
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BlockedActivity.EXTRA_END_AT, blocking.endAt)
                .putExtra(BlockedActivity.EXTRA_SOURCE, blocking.source.name)
        )
        return true
    }

    private fun logBlock(pkg: String, now: Long) {
        if (HistoryStats.shouldLogBlockEvent(now, lastBlockLogged[pkg])) {
            lastBlockLogged[pkg] = now
            scope.launch { historyDao.insertBlockEvent(BlockEventRow(packageName = pkg, timestampMillis = now)) }
        }
    }

    // Throttle, not debounce: YouTube emits content events constantly, so waiting for quiet could starve the check.
    private fun scheduleCheck(pkg: String) {
        if (rules.livePartial(System.currentTimeMillis()).isEmpty()) { clearOverlay(); return }
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            delay(CHECK_DELAY_MS)
            checkPartial(pkg)
        }
    }

    private fun checkPartial(pkg: String) {
        val now = System.currentTimeMillis()
        val enabled = rules.livePartial(now)
        val root = rootInActiveWindow
        // The active window can be the keyboard or a dialog owned by another package.
        val snapshot = if (root != null && root.packageName?.toString() == pkg) root.snapshot(intArrayOf(MAX_NODES), 0) else null
        @Suppress("DEPRECATION") root?.recycle()

        val match = PartialBlocking.detect(pkg, snapshot, enabled)
        if (match == null) {
            clearOverlay()
            matchLog.clear()
            return
        }
        if (matchLog.shouldLog(pkg, match.rule)) logBlock(pkg, now)
        when (match.rule.action) {
            PartialAction.COVER -> cover(match)
            PartialAction.LEAVE -> {
                clearOverlay()
                when (leaveGate.next(now)) {
                    LeaveStep.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                    LeaveStep.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                    LeaveStep.WAIT -> {}
                }
            }
        }
    }

    /** Copies a bounded slice of the tree into plain data; text is clipped since only short labels are ever matched. */
    private fun AccessibilityNodeInfo.snapshot(budget: IntArray, depth: Int): NodeSnapshot {
        budget[0]--
        val r = Rect().also { getBoundsInScreen(it) }
        val kids = ArrayList<NodeSnapshot>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until childCount) {
                if (budget[0] <= 0) break
                val child = getChild(i) ?: continue
                kids.add(child.snapshot(budget, depth + 1))
                @Suppress("DEPRECATION") child.recycle()
            }
        }
        return NodeSnapshot(
            viewId = viewIdResourceName,
            text = text?.toString()?.take(40),
            desc = contentDescription?.toString()?.take(40),
            selected = isSelected,
            left = r.left, top = r.top, right = r.right, bottom = r.bottom,
            children = kids,
        )
    }

    /** Hides just the recommendation list; the rest of the app stays usable. */
    private fun cover(m: PartialMatch) {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val lp = WindowManager.LayoutParams(
            m.right - m.left, m.bottom - m.top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = m.left; y = m.top }
        try {
            val existing = overlay
            if (existing != null) {
                wm.updateViewLayout(existing, lp)
            } else {
                val view = TextView(this).apply {
                    text = "Recommendations are off while you focus"
                    gravity = Gravity.CENTER
                    setTextColor(NowFocusColors.text.toArgb())
                    setBackgroundColor(NowFocusColors.bg.toArgb())
                    isClickable = true // swallow touches so the list underneath can't be scrolled or tapped
                }
                wm.addView(view, lp)
                overlay = view
            }
        } catch (_: RuntimeException) {
            overlay = null // window token gone (service being torn down): nothing to cover
        }
    }

    private fun clearOverlay() {
        val view = overlay ?: return
        overlay = null
        try { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) } catch (_: RuntimeException) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        clearOverlay()
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }
}
