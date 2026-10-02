package app.getnowfocus.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class BlockSource { SESSION, COMMITMENT_SHIELD }

/**
 * One source's contribution: a manual session, or the Commitment Shield. Each expires on its own.
 * A window can also be switched off for a stretch without ending: by a cheat day ([pausedFrom] to
 * [pausedUntil]) or, for one app, by a pass ([passes]). Only session windows are ever given either.
 */
data class RuleWindow(
    val endAt: Long,
    val domains: Set<String>,
    val packages: Set<String>,
    val source: BlockSource = BlockSource.SESSION,
    val partial: Set<PartialRule> = emptySet(),
    val passes: Map<String, Long> = emptyMap(),
    val passesLeft: Int = 0,
    val pausedFrom: Long = 0L,
    val pausedUntil: Long = 0L,
) {
    fun paused(now: Long) = now >= pausedFrom && now < pausedUntil
    fun passed(pkg: String, now: Long) = (passes[pkg] ?: 0L) > now
    /** The moments after [now] when what this window blocks changes, so the services wake and re-check. */
    fun changesAfter(now: Long): List<Long> = (listOf(endAt, pausedFrom, pausedUntil) + passes.values).filter { it > now }
}

/**
 * What's blocked right now, as however many windows are currently active. A
 * manual session and the Commitment Shield can both be live at once, each
 * with its own endAt - so nothing here trusts a single scalar deadline;
 * every query re-filters by [now], the same live-recheck pattern the two
 * enforcement services already used for the single-window case.
 */
data class ActiveRules(val windows: List<RuleWindow> = emptyList()) {
    private fun liveWindows(now: Long) = windows.filter { it.endAt > now }
    /** Live and not paused by a cheat day: what is actually blocking. */
    private fun blocking(now: Long) = liveWindows(now).filter { !it.paused(now) }
    fun liveDomains(now: Long): Set<String> = blocking(now).flatMap { it.domains }.toSet()
    fun livePackages(now: Long): Set<String> = blocking(now).flatMap { w -> w.packages.filter { !w.passed(it, now) } }.toSet()
    fun livePartial(now: Long): Set<PartialRule> = blocking(now).flatMap { it.partial }.toSet()
    fun nextExpiryAfter(now: Long): Long? = liveWindows(now).flatMap { it.changesAfter(now) }.minOrNull()
    fun hasLiveWindow(now: Long): Boolean = liveWindows(now).isNotEmpty()

    /**
     * The live windows blocking [pkg] right now. The Commitment Shield takes
     * priority when both match: it is the more restrictive source (no exit
     * exists for it), so that's what the block screen must communicate, and
     * its endAt (not the session's, if any) is what "blocked until" means.
     */
    fun windowsBlocking(pkg: String, now: Long): List<RuleWindow> = blocking(now).filter { pkg in it.packages && !it.passed(pkg, now) }
}

/**
 * Android counterpart of the macOS SessionController: the one place that
 * starts/stops enforcement. Both services derive their rules from
 * [activeRulesFlow] and re-check liveness themselves against the current
 * time, so enforcement stops exactly at each window's endAt even if nothing
 * calls [stop]. Rules come from the session's own snapshot, never the live
 * policy list.
 */
object Enforcement {

    fun activeRulesFlow(repo: SessionRepository): Flow<ActiveRules> =
        combine(repo.sessionFlow, repo.commitmentShieldFlow, repo.cheatDayFlow) { session, shield, cheat ->
            val now = System.currentTimeMillis()
            val windows = buildList {
                // Only the session window is ever paused or passed. The Shield window below never is.
                session?.takeIf { SessionEngine.isActive(SessionEngine.evaluateState(it, now), now) }
                    ?.let {
                        add(RuleWindow(
                            it.endAt, it.domains, it.packages, BlockSource.SESSION, it.partial,
                            passes = it.passes.associate { p -> p.packageName to p.until },
                            passesLeft = Passes.passesLeft(it),
                            pausedFrom = cheat?.startAt ?: 0L,
                            pausedUntil = cheat?.endAt ?: 0L,
                        ))
                    }
                shield?.takeIf { it.endAt > now }
                    ?.let { add(RuleWindow(it.endAt, it.domains, it.packages, BlockSource.COMMITMENT_SHIELD, it.partial)) }
            }
            ActiveRules(windows)
        }

    /**
     * Whether enforcement should be running right now: a live session OR a
     * live Commitment Shield. Shared by SessionViewModel.init (recovery from
     * ordinary process death) and BootReceiver (recovery from a reboot) -
     * they drifted apart once before (BootReceiver checked only the shield),
     * so this is the one place that decision lives now.
     */
    suspend fun shouldRun(repo: SessionRepository, now: Long = System.currentTimeMillis()): Boolean {
        val sessionActive = repo.sessionFlow.first()?.let { SessionEngine.isActive(SessionEngine.evaluateState(it, now), now) } ?: false
        val shieldActive = repo.commitmentShieldFlow.first()?.let { it.endAt > now } ?: false
        return sessionActive || shieldActive
    }

    fun start(context: Context) {
        // Every start path (manual, recovery, Bedtime, boot) lands here, so this is the one place the
        // session notification gets posted; it re-derives from the stored session.
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch { SessionNotifier.sync(app) }
        // Without VPN consent only app blocking runs; the health row says so.
        if (isVpnPermitted(context)) context.startService(Intent(context, FocusVpnService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, FocusVpnService::class.java).setAction(FocusVpnService.ACTION_STOP))
    }

    fun isVpnPermitted(context: Context) = VpnService.prepare(context) == null

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(context, FocusAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
