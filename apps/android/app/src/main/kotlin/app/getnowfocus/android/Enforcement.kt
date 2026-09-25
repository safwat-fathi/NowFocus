package app.getnowfocus.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What's blocked right now. Null whenever no session is active. */
data class ActiveRules(val endAt: Long, val domains: Set<String>, val packages: Set<String>)

/**
 * Android counterpart of the macOS SessionController: the one place that
 * starts/stops enforcement. Both services derive their rules from
 * [activeRulesFlow] and re-check endAt themselves, so enforcement stops at
 * endAt even if nothing calls [stop]. Rules come from the session's own
 * snapshot, never the live policy list.
 */
object Enforcement {

    fun activeRulesFlow(repo: SessionRepository): Flow<ActiveRules?> =
        repo.sessionFlow.map { session ->
            session?.takeIf { SessionEngine.isActive(SessionEngine.evaluateState(it)) }
                ?.let { ActiveRules(it.endAt, it.domains, it.packages) }
        }

    fun start(context: Context) {
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
