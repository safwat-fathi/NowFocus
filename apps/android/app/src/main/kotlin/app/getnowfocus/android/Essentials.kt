package app.getnowfocus.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.ConcurrentHashMap

/**
 * What a whitelist session must never close, so nobody is stranded.
 *
 * The rule is a scope, not a list of system packages: only an app the user could have picked, one with a
 * launcher icon, is ever closed. Everything else (the sign-in sheet, file picker, share sheet, installer,
 * permission dialogs, SystemUI, keyboards) is a helper window of whatever is in front, and no hand-written list
 * of them would keep up with every phone. A package this phone can't see is not closed either: failing open.
 *
 * On top of that, the apps that do have an icon but must stay reachable: the launcher (Home would otherwise
 * bounce itself in a loop), the dialer, the default SMS app (the block screen's "text someone"), Settings
 * (always allowed, so the Accessibility switch stays reachable) and the emergency app.
 *
 * Camera and Chrome are not exempt: both have icons, so they close unless the user allows them. A Custom Tab
 * opened for sign-in runs in Chrome, and a camera intent opens the camera app.
 * ponytail: resolved once per service start, a launcher or keyboard changed mid-session is picked up on the next start.
 */
class Essentials(context: Context) {
    private val pm = context.packageManager
    private val launchable = ConcurrentHashMap<String, Boolean>()
    private val essential: Set<String> = buildSet {
        addAll(FIXED)
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let(::add)
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
        runCatching { context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName } }
            .getOrNull()?.let(::addAll)
    }

    /** True for what a whitelist must leave open. [FocusAccessibilityService] hands this to [ActiveRules.windowsBlocking]. */
    fun exempt(pkg: String): Boolean = isExempt(pkg, essential) { launchable.getOrPut(it) { pm.getLaunchIntentForPackage(it) != null } }

    companion object {
        val FIXED = setOf("com.android.settings", "com.android.emergency")

        /** The rule itself, apart from the Android lookups, so it is tested without a device. */
        fun isExempt(pkg: String, essential: Set<String>, launchable: (String) -> Boolean): Boolean =
            pkg in essential || !launchable(pkg)
    }
}
