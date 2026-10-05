package app.getnowfocus.android

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/** Only a developer's `adb shell pm grant … WRITE_SECURE_SETTINGS` unlocks this; users get a pointer to Android's own Bedtime mode instead. */
fun canWriteSecureSettings(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

private const val ENABLED_KEY = "accessibility_display_daltonizer_enabled"
private const val MODE_KEY = "accessibility_display_daltonizer"
private const val MONOCHROMACY = 0
private const val NO_MODE = -1

/**
 * Drains the colour (system "colour correction" set to monochromacy) for the
 * wind-down window and hands the previous setting back at wake. Called from
 * [reconcileQuietNotifications], so every path that re-arms Bedtime (alarm,
 * boot, save, sync, cheat-day end) also reconciles this.
 *
 * Safe to call often. Without the adb grant it only clears a stale "applied"
 * flag. If the user already runs their own colour correction we never touch
 * it (and so never "restore" it either).
 */
fun reconcileGreyscale(context: Context, settings: BedtimeSettings, cheating: Boolean) {
    val prefs = context.applicationContext.getSharedPreferences("bedtime_greyscale", Context.MODE_PRIVATE)
    val applied = prefs.getBoolean("applied", false)
    if (!canWriteSecureSettings(context)) {
        if (applied) prefs.edit().putBoolean("applied", false).apply()
        return
    }
    val resolver = context.contentResolver
    val effective = if (cheating) settings.copy(enabled = false) else settings
    when (BedtimeSchedule.decideGreyscale(effective, System.currentTimeMillis(), ZoneId.systemDefault(), applied)) {
        GreyscaleDecision.APPLY -> {
            if (Settings.Secure.getInt(resolver, ENABLED_KEY, 0) == 1) return // the user's own filter
            prefs.edit()
                .putBoolean("applied", true)
                .putInt("previousMode", Settings.Secure.getInt(resolver, MODE_KEY, NO_MODE))
                .apply()
            Settings.Secure.putInt(resolver, MODE_KEY, MONOCHROMACY)
            Settings.Secure.putInt(resolver, ENABLED_KEY, 1)
        }
        GreyscaleDecision.RESTORE -> {
            Settings.Secure.putInt(resolver, ENABLED_KEY, 0)
            val previous = prefs.getInt("previousMode", NO_MODE)
            if (previous != NO_MODE) Settings.Secure.putInt(resolver, MODE_KEY, previous)
            prefs.edit().putBoolean("applied", false).apply()
        }
        GreyscaleDecision.NONE -> {}
    }
}
