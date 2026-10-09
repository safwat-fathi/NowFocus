package app.getnowfocus.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Play's prominent-disclosure rule: say what the accessibility service reads, and get an explicit
 * Agree, before sending the user to system Accessibility settings. Every entry point goes through this.
 * Call it at the top of a composable; it draws the dialog and returns the function that opens it.
 */
@Composable
fun accessibilityOpener(): () -> Unit {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.a11y_find_hint)
    if (show) ConfirmDialog(
        title = stringResource(R.string.a11y_disclosure_title),
        message = stringResource(R.string.accessibility_description),
        confirmLabel = stringResource(R.string.a11y_agree),
        onConfirm = { show = false; openAccessibilitySettings(context, hint) },
        onDismiss = { show = false },
    )
    return { show = true }
}

/**
 * No app can open its own service's page: ACTION_ACCESSIBILITY_DETAILS_SETTINGS needs a system-only permission
 * (verified on a Galaxy, Android 13). The highlight extras make stock Android scroll to and flash NowFocus's row;
 * One UI ignores them and lists the service under "Installed apps", so a toast says where to look.
 */
private fun openAccessibilitySettings(context: android.content.Context, hint: String) {
    val me = ComponentName(context, FocusAccessibilityService::class.java).flattenToString()
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", me)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", me) })
    )
    Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
}
