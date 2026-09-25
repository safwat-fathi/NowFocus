package app.getnowfocus.android

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

class BlockedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_END_AT = "endAt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
        val until = DateFormat.getTimeFormat(this).format(endAt)
        setContent {
            NowFocusTheme {
                Surface(Modifier.fillMaxSize(), color = NowFocusColors.text) {
                    ShieldScreen(until = until, onReturn = { goHome() })
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun ShieldScreen(until: String, onReturn: () -> Unit) {
    Column(Modifier.fillMaxSize().background(NowFocusColors.text).padding(NowFocusSpace.s6)) {
        Text("Shielded by NowFocus", style = kickerStyle(NowFocusColors.neutral400))
        Spacer(Modifier.height(NowFocusSpace.s8))
        Text(
            "This can wait.",
            style = headingStyle(48.sp, color = NowFocusColors.bg),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            "You're in a focus session until $until.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 17.sp, color = NowFocusColors.neutral300),
        )
        Spacer(Modifier.weight(1f))
        // "I really need it" / Strict-Locked exit friction is added once Unlock exists (M2/M4).
        PrimaryButton("Back to focus", onClick = onReturn)
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}
