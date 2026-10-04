package app.getnowfocus.android

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.stringResource

/**
 * A local lock on the Account screen only. Sessions, blocking and background sync never touch it.
 * It fails open: with no usable fingerprint (removed, or API < 28) the screen opens, so Sign out and
 * Delete account can never be stranded behind a lock the phone can no longer open.
 */
internal fun accountLocked(enabled: Boolean, available: Boolean, signedIn: Boolean, unlocked: Boolean) =
    enabled && available && signedIn && !unlocked

// ponytail: API 26-27 get no lock; androidx.biometric's legacy dialog may need an AppCompat theme this app doesn't use.
internal fun fingerprintAvailable(context: Context) =
    Build.VERSION.SDK_INT >= 28 &&
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/** Cancel leaves things as they were. A real error (e.g. too many tries) is reported, so Unlock never silently does nothing. */
internal fun FragmentActivity.askFingerprint(onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
    val t = localized()
    val prompt = BiometricPrompt(
        this, ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) onError(errString.toString())
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(t.getString(R.string.lock_prompt_title))
            .setNegativeButtonText(t.getString(R.string.cancel))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build(),
    )
}

/** Shown in place of [AccountScreen]. The prompt opens only on the button, never by itself, so Cancel can't loop. */
@Composable
fun LockedAccount(message: String?, onUnlock: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back_devices), onClick = onBack)
        Text(stringResource(R.string.account_title), style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(
            stringResource(R.string.lock_body),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
        )
        message?.let {
            Text(it, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2))
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        PrimaryButton(stringResource(R.string.lock_unlock), modifier = Modifier.fillMaxWidth(), onClick = onUnlock)
    }
}
