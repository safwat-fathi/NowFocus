package app.getnowfocus.android

import android.content.Intent
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
    if (show) ConfirmDialog(
        title = stringResource(R.string.a11y_disclosure_title),
        message = stringResource(R.string.accessibility_description),
        confirmLabel = stringResource(R.string.a11y_agree),
        onConfirm = { show = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onDismiss = { show = false },
    )
    return { show = true }
}
