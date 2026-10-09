package app.getnowfocus.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android counterpart of macOS AppBlocker. Two jobs:
 *  - bounce blocked apps, using only which package comes to the foreground; and
 *  - partial blocking: while a session/shield has partial rules live, and only while YouTube,
 *    Facebook, Instagram or X is in front, read that app's screen to spot Shorts / Reels /
 *    recommendation lists. Experimental: the screen signatures are unverified (see PartialSignatures).
 * Screen content is read only in that case, is matched in memory and never stored or sent anywhere.
 *  - website daily limits: while a listed browser is in front and a site limit exists, read its address bar
 *    every few seconds and count the time on limited domains (see [SiteLimits]). Only the time per limited
 *    domain is stored; what else you browse is dropped from memory at once.
 */
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        // Bedtime's sleep-time lock needs a live AccessibilityService instance to
        // call performGlobalAction on (it's not a static API) - null whenever
        // Accessibility isn't enabled, in which case the lock silently doesn't fire.
        @Volatile var instance: FocusAccessibilityService? = null
            private set

        private const val CHECK_DELAY_MS = 150L
        /** SystemUI starts the launcher 20-60 ms after GLOBAL_ACTION_HOME; the block screen must start after that. */
        private const val HOME_SETTLE_MS = 300L
        /** One open fires several window events (the second came 480 ms after the first); only the first bounces. */
        private const val BOUNCE_DEDUPE_MS = 1_500L
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 30
        private const val SYSTEM_UI = "com.android.systemui"
        private const val PREFS = "nowfocus_cover"
        private const val KEY_MUTED = "muted_by_cover"
        private const val SITE_TICK_MS = 2_500L
        private const val SITE_FLUSH_MS = 15_000L
        /** How long the browser may be out of front (shade pulled down, a dialog) before the heartbeat stops. */
        private const val SITE_AWAY_MS = 60_000L

        /**
         * Address-bar view id per browser. UNVERIFIED on real devices and browsers rename these between
         * releases: a browser missing here, or whose id changed, simply isn't counted.
         */
        val BROWSER_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var rules: ActiveRules = ActiveRules()
    /** What a whitelist session leaves open; resolved when the service connects (it needs the package manager). */
    private val essentials by lazy { Essentials(this) }
    // Per-package last-logged time, so one open-attempt's several foreground
    // events don't multi-count in Stats. Not persisted: fine to reset with the service.
    private val lastBlockLogged = mutableMapOf<String, Long>()
    // Separate from lastBlockLogged: partial and site blocks write there too, and must not swallow a bounce.
    private val lastBounce = mutableMapOf<String, Long>()
    private val historyDao by lazy { HistoryDatabase.get(this).dao() }

    // Device-local settings the service reads alongside the block rules (see limitApp and frictionApp).
    @Volatile private var limits: List<AppLimit> = emptyList()
    @Volatile private var frictionPackages: Set<String> = emptySet()
    @Volatile private var cheat: CheatDay? = null
    private val lastFriction = mutableMapOf<String, Long>()
    private var limitJob: Job? = null
    private lateinit var repo: SessionRepository

    // Website limits: today's saved usage plus what the heartbeat has counted but not saved yet.
    @Volatile private var siteUsage = SiteUsage()
    @Volatile private var limitPasses = LimitPassState()
    private val pendingSite = mutableMapOf<String, Long>()
    private var lastSiteFlush = 0L
    private var siteJob: Job? = null
    private var siteBrowser: String? = null
    private var siteAwaySince = 0L
    private var viewIdsOn = false

    private var checkJob: Job? = null
    private var expiryJob: Job? = null
    private var contentEventsOn = false
    private val leaveGate = LeaveGate()
    private val matchLog = MatchLogGate()
    private val feedMemory = FeedMemory()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var overlay: View? = null
    private var overlayRule: PartialRule? = null
    /** True while this service has the media stream muted for a cover, so only it ever unmutes. */
    private var mutedByCover = false

    override fun onServiceConnected() {
        instance = this
        // A crash or a force-stop while a cover was up would have left the media stream muted for good.
        if (prefs.getBoolean(KEY_MUTED, false)) {
            mutedByCover = true
            unmuteMedia()
        }
        val repo = SessionRepository(this).also { this.repo = it }
        scope.launch { Enforcement.activeRulesFlow(repo).collect { applyRules(it) } }
        scope.launch { repo.limitsFlow.collect { limits = it; syncServiceInfo() } }
        scope.launch { repo.siteUsageFlow.collect { siteUsage = it } }
        scope.launch { repo.limitPassesFlow.collect { limitPasses = it } }
        scope.launch { repo.frictionAppsFlow.collect { apps -> frictionPackages = apps.map { it.packageName }.toSet() } }
        scope.launch { repo.cheatDayFlow.collect { cheat = it } }
    }

    /**
     * Content-change events fire for every app on every layout change, and having them on measurably
     * slows NowFocus's own scrolling, so the subscription is on only while a partial rule is live
     * (the XML declares just window-state events). Re-evaluated at each window's expiry, which also
     * drops the cover when a session ends without any further event arriving.
     */
    private fun syncServiceInfo() {
        val partialLive = rules.livePartial(System.currentTimeMillis()).isNotEmpty()
        val wantIds = partialLive || limits.any { SiteLimits.isSite(it) } // site limits need view ids to find the address bar
        if (partialLive == contentEventsOn && wantIds == viewIdsOn) return
        serviceInfo = serviceInfo?.apply {
            eventTypes = if (partialLive) eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                else eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
            flags = if (wantIds) flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                else flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
        }
        contentEventsOn = partialLive
        viewIdsOn = wantIds
    }

    private fun applyRules(r: ActiveRules) {
        rules = r
        val now = System.currentTimeMillis()
        val partialLive = r.livePartial(now).isNotEmpty()
        if (!partialLive) { clearOverlay(); matchLog.clear() }
        syncServiceInfo()
        expiryJob?.cancel()
        r.nextExpiryAfter(now)?.let { end ->
            expiryJob = scope.launch {
                delay(end - now + 50)
                applyRules(rules)
                // A pass or a cheat day just ended: whatever is in front may be blocked again, and the
                // notification's wording may have changed. No window event will say so.
                SessionNotifier.sync(this@FocusAccessibilityService)
                rootInActiveWindow?.packageName?.toString()?.takeIf { it != packageName }?.let { blockApp(it) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (blockApp(pkg)) return
                // Not blocked by a session or the Shield: a daily limit may still stop it, or a pause may precede it.
                if (limitApp(pkg)) return
                watchSites(pkg)
                frictionApp(pkg)
                // The cover is a system-level window: drop it once the user is somewhere else.
                if (pkg !in PartialSignatures.PACKAGES && pkg != SYSTEM_UI) { clearOverlay(); matchLog.clear(); feedMemory.xForYou = true }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {}
            else -> return
        }
        if (pkg in PartialSignatures.PACKAGES) scheduleCheck(pkg)
    }

    /** Whole-app block: returns true when [pkg] was bounced. */
    private fun blockApp(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val matching = rules.windowsBlocking(pkg, now, essentials::exempt)
        if (matching.isEmpty()) return false
        // The Commitment Shield is the more restrictive source when both match - see windowsBlocking.
        val bySource = matching.sortedByDescending { it.source == BlockSource.COMMITMENT_SHIELD }
        val blocking = bySource.first()

        logBlock(pkg, now)
        bounce(
            pkg,
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BlockedActivity.EXTRA_END_AT, blocking.endAt)
                .putExtra(BlockedActivity.EXTRA_SOURCE, blocking.source.name)
                .putExtra(BlockedActivity.EXTRA_BEDTIME, blocking.bedtime)
                .putExtra(BlockedActivity.EXTRA_ALLOWLIST, blocking.allowlist)
                .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockedActivity.EXTRA_PASSES_LEFT, blocking.passesLeft)
        )
        return true
    }

    /**
     * Home first, so Back from the block screen can't land in the blocked app, then the screen.
     * GLOBAL_ACTION_HOME is only a request to SystemUI: started at once, the screen ends up under the
     * launcher and is destroyed unseen, so it starts after a short wait. A repeat event of the same open
     * is dropped, or its second HOME would bury the screen again.
     * ponytail: fixed wait; if a slow device needs more, start on the launcher's own window event instead.
     */
    private fun bounce(pkg: String, screen: Intent) {
        val now = System.currentTimeMillis()
        if (!HistoryStats.shouldLogBlockEvent(now, lastBounce[pkg], BOUNCE_DEDUPE_MS)) return
        lastBounce[pkg] = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        scope.launch {
            delay(HOME_SETTLE_MS)
            startActivity(screen)
        }
    }

    /**
     * Daily limit for [pkg]: true when it was bounced because today's time is used up. Otherwise, while the
     * app is in front, a timer re-checks when the time would run out, since no window event marks that moment.
     * Paused on a cheat day, and off without usage access (the screen says so).
     */
    private fun limitApp(pkg: String): Boolean {
        limitJob?.cancel()
        val limit = limits.find { it.packageName == pkg } ?: return false
        val now = System.currentTimeMillis()
        if (cheat?.isActive(now) == true || limit.minutesAt(now) == 0) return false
        val used = UsageReader.foregroundToday(this, setOf(pkg), now)[pkg] ?: return false
        val left = limit.limitMillisAt(now) - used
        val day = SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())
        if (left <= 0) {
            // A five-minute pass keeps it open: look again when the pass ends.
            val passUntil = LimitPasses.activeUntil(limitPasses, pkg, now, day)
            if (passUntil > now) { recheckLimitAfter(pkg, passUntil - now); return false }
            logBlock(pkg, now)
            bounce(
                pkg,
                Intent(this, BlockedActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BlockedActivity.EXTRA_END_AT, UsageMath.nextMidnight(now, java.time.ZoneId.systemDefault()))
                    .putExtra(BlockedActivity.EXTRA_SOURCE, BlockedActivity.SOURCE_LIMIT)
                    .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
                    .putExtra(BlockedActivity.EXTRA_PASS_KEY, pkg)
                    .putExtra(BlockedActivity.EXTRA_PASSES_LEFT, LimitPasses.passesLeft(limitPasses, pkg, day))
                    .putExtra(BlockedActivity.EXTRA_LIMIT_MINUTES, limit.minutesAt(now))
            )
            return true
        }
        recheckLimitAfter(pkg, left)
        return false
    }

    /** No window event marks the moment time (or a pass) runs out, so look again then if [pkg] is still in front. */
    private fun recheckLimitAfter(pkg: String, millis: Long) {
        limitJob = scope.launch {
            delay(millis + 500)
            val stillThere = rootInActiveWindow?.packageName?.toString() == pkg
            if (stillThere && getSystemService(android.os.PowerManager::class.java)?.isInteractive == true) limitApp(pkg)
        }
    }

    /** Starts the address-bar heartbeat when a listed browser comes to the front and a site limit exists. */
    private fun watchSites(pkg: String) {
        val barId = BROWSER_BARS[pkg] ?: return
        if (limits.none { SiteLimits.isSite(it) }) return
        if (siteJob?.isActive == true && siteBrowser == pkg) return
        siteJob?.cancel()
        siteBrowser = pkg
        siteAwaySince = 0L
        siteJob = scope.launch {
            var tick: SiteTick? = null
            while (isActive) {
                delay(SITE_TICK_MS)
                tick = siteTick(pkg, barId, tick) ?: break
            }
            flushSites()
        }
    }

    /**
     * The limit key of the site the address bar shows, or null when unknown (typing, search box). A bar that
     * isn't on screen at all (fullscreen video, a scrolled-away toolbar) keeps [prevKey]: the page hasn't changed.
     */
    private fun siteKeyInFront(barId: String, prevKey: String?): String? {
        val root = rootInActiveWindow ?: return null
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return prevKey
        val host = if (bar.isFocused) null else SiteLimits.hostOf(bar.text?.toString())
        return host?.let { SiteLimits.keyFor(it, limits) }
    }

    /** One heartbeat: credits the time since the last one, then bounces if the day's budget is spent. Null ends the loop. */
    private suspend fun siteTick(pkg: String, barId: String, prev: SiteTick?): SiteTick? {
        val now = System.currentTimeMillis()
        if (limits.none { SiteLimits.isSite(it) }) return null
        val awake = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (!awake || rootInActiveWindow?.packageName?.toString() != pkg) {
            // Not in front right now: credit nothing, but keep polling a while, since coming back may send no window event.
            if (siteAwaySince == 0L) siteAwaySince = now
            return if (now - siteAwaySince > SITE_AWAY_MS) null else SiteTick(null, now)
        }
        siteAwaySince = 0L
        val credit = SiteTimer.credit(prev, now, SITE_TICK_MS * 4)
        if (credit > 0) pendingSite.merge(prev!!.key!!, credit, Long::plus)
        if (now - lastSiteFlush > SITE_FLUSH_MS) flushSites()

        val key = siteKeyInFront(barId, prev?.key)
        val limit = key?.let { k -> limits.find { it.packageName == k } }
        if (limit == null || cheat?.isActive(now) == true || limit.minutesAt(now) == 0) return SiteTick(key, now)
        val used = siteUsage.usedMs(key, SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())) + (pendingSite[key] ?: 0L)
        if (used < limit.limitMillisAt(now)) return SiteTick(key, now)
        val day = SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())
        // A five-minute pass keeps the site open; the next tick after it ends bounces again.
        if (LimitPasses.activeUntil(limitPasses, key, now, day) > now) return SiteTick(key, now)

        // Used up. BACK first (twice at most) so the tab isn't left parked on the blocked page, then HOME.
        logBlock(pkg, now)
        for (i in 0 until 3) {
            if (siteKeyInFront(barId, null) != key) break
            performGlobalAction(if (i < 2) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
            delay(700)
        }
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BlockedActivity.EXTRA_END_AT, UsageMath.nextMidnight(now, java.time.ZoneId.systemDefault()))
                .putExtra(BlockedActivity.EXTRA_SOURCE, BlockedActivity.SOURCE_LIMIT)
                .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockedActivity.EXTRA_SITE, SiteLimits.domain(limit))
                .putExtra(BlockedActivity.EXTRA_PASS_KEY, key)
                .putExtra(BlockedActivity.EXTRA_PASSES_LEFT, LimitPasses.passesLeft(limitPasses, key, day))
                .putExtra(BlockedActivity.EXTRA_LIMIT_MINUTES, limit.minutesAt(now))
        )
        return null
    }

    private fun flushSites() {
        lastSiteFlush = System.currentTimeMillis()
        if (pendingSite.isEmpty()) return
        val credits = pendingSite.toMap()
        pendingSite.clear()
        val day = SiteLimits.dayOf(lastSiteFlush, java.time.ZoneId.systemDefault())
        scope.launch { repo.addSiteUsage(day, credits) }
    }

    /** An app you asked to pause for: show the breath and question first (see FrictionGate). */
    private fun frictionApp(pkg: String) {
        if (pkg !in frictionPackages) return
        val now = System.currentTimeMillis()
        if (cheat?.isActive(now) == true || FrictionGate.isAllowed(pkg, now)) return
        if (now - (lastFriction[pkg] ?: 0L) < 2_000) return // one open fires several window events
        lastFriction[pkg] = now
        startActivity(
            Intent(this, FrictionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(FrictionActivity.EXTRA_PACKAGE, pkg)
        )
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

        val match = PartialBlocking.detect(pkg, snapshot, enabled, feedMemory)
        if (match == null) {
            clearOverlay()
            matchLog.clear()
            return
        }
        if (matchLog.shouldLog(pkg, match.rule)) logBlock(pkg, now)
        when (match.action) {
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (m.passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.OPAQUE,
        // LEFT, not START: x is an absolute screen coordinate, and START would put it on the right edge in Arabic.
        ).apply { @Suppress("RtlHardcoded") gravity = Gravity.TOP or Gravity.LEFT; x = m.left; y = m.top }
        try {
            val existing = overlay
            if (existing != null && overlayRule == m.rule) {
                wm.updateViewLayout(existing, lp)
            } else {
                clearOverlay()
                val view = coverView(m)
                wm.addView(view, lp)
                overlay = view
                overlayRule = m.rule
            }
            muteMedia()
        } catch (_: RuntimeException) {
            overlay = null // window token gone (service being torn down): nothing to cover
        }
    }

    /** Logo, what was hidden and why. Built from the localized context so the text and its direction follow the app language. */
    private fun coverView(m: PartialMatch): View {
        val ctx = localized()
        val dp = ctx.resources.displayMetrics.density
        fun label(text: String, sp: Float, color: Int, bold: Boolean, topDp: Int) = TextView(ctx).apply {
            this.text = text; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (topDp * dp).toInt(), 0, 0)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
            setBackgroundColor(NowFocusColors.bg.toArgb())
            isClickable = true // swallow touches so the list underneath can't be scrolled or tapped (unless passThrough)
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_overlay_logo) })
            addView(label(ctx.getString(R.string.overlay_recommendations_off), 20f, NowFocusColors.text.toArgb(), true, 20))
            addView(label(ctx.getString(R.string.overlay_why, ctx.getString(m.rule.label)), 14f, NowFocusColors.neutral700.toArgb(), false, 10))
        }
    }

    /** The video keeps playing under a cover, so silence it; [clearOverlay] gives the sound back. */
    private fun muteMedia() {
        if (mutedByCover) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) return // the user's own mute: leave it alone
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedByCover = true
        prefs.edit().putBoolean(KEY_MUTED, true).apply()
    }

    private fun unmuteMedia() {
        if (!mutedByCover) return
        mutedByCover = false
        prefs.edit().putBoolean(KEY_MUTED, false).apply()
        getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }

    private fun clearOverlay() {
        unmuteMedia()
        val view = overlay ?: return
        overlay = null
        overlayRule = null
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
