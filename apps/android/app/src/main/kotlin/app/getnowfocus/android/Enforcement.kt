package app.getnowfocus.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
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
    /** The session is a Bedtime wind-down, which the block screen names as such. */
    val bedtime: Boolean = false,
    /** [packages] are the only apps left open and everything else is closed. Never set on the Shield. */
    val allowlist: Boolean = false,
) {
    val reason: BlockReason
        get() = when {
            source == BlockSource.COMMITMENT_SHIELD -> BlockReason.COMMITMENT_SHIELD
            bedtime -> BlockReason.BEDTIME
            allowlist -> BlockReason.ALLOWLIST_SESSION
            else -> BlockReason.FOCUS_SESSION
        }

    /**
     * Whether this window closes [pkg]. An allowlist with no app on this phone closes nothing: its apps may all
     * belong to another platform, and closing everything would be the worst way to find that out. [exempt] is what
     * an allowlist must never close (see [Essentials]); a blocklist ignores it.
     */
    fun closes(pkg: String, exempt: (String) -> Boolean): Boolean =
        if (allowlist) packages.isNotEmpty() && pkg !in packages && !exempt(pkg) else pkg in packages
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
    // An allowlist never filters sites: its leftover domains must not be DNS-blocked, they are not what to close.
    fun liveDomains(now: Long): Set<String> = blocking(now).filterNot { it.allowlist }.flatMap { it.domains }.toSet()
    /** The apps blocklists close right now; an allowlist closes "everything else", which has no list. */
    fun livePackages(now: Long): Set<String> = blocking(now).filterNot { it.allowlist }.flatMap { w -> w.packages.filter { !w.passed(it, now) } }.toSet()
    fun livePartial(now: Long): Set<PartialRule> = blocking(now).flatMap { it.partial }.toSet()
    fun nextExpiryAfter(now: Long): Long? = liveWindows(now).flatMap { it.changesAfter(now) }.minOrNull()
    fun hasLiveWindow(now: Long): Boolean = liveWindows(now).isNotEmpty()

    /**
     * The live windows blocking [pkg] right now. The Commitment Shield takes
     * priority when both match: it is the more restrictive source (no exit
     * exists for it), so that's what the block screen must communicate, and
     * its endAt (not the session's, if any) is what "blocked until" means.
     */
    fun windowsBlocking(pkg: String, now: Long, exempt: (String) -> Boolean = { false }): List<RuleWindow> =
        blocking(now).filter { it.closes(pkg, exempt) && !it.passed(pkg, now) }

    /** Why [host] is blocked: the Shield if it blocks it too (the stricter source), else the session; null if no window does. */
    fun reasonForDomain(host: String, now: Long): BlockReason? =
        blocking(now).filter { !it.allowlist && DomainValidation.matches(host, it.domains) }
            .maxByOrNull { it.source == BlockSource.COMMITMENT_SHIELD }?.reason
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
                            bedtime = it.sessionType == SessionType.BEDTIME_WINDDOWN,
                            allowlist = it.policyMode == PolicyMode.ALLOWLIST,
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

    /**
     * Provider hostname when Private DNS is in strict mode, else null. Strict mode only talks to DoT servers
     * the VPN can route to; ours routes just its fake resolver, so every lookup would fail with no plain-DNS
     * fallback. activeNetwork is the underlying one: we're excluded from our own VPN (see upstreamDns()).
     */
    fun strictPrivateDns(context: Context): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.privateDnsServerName
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(context, FocusAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
