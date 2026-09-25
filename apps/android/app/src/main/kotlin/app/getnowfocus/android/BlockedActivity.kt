package app.getnowfocus.android

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
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
        const val EXTRA_SOURCE = "source"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderContent()
    }

    // singleTask means a block screen left open (Home button, not Back) is
    // REUSED for the next block rather than recreated - without this, its
    // extras (and so its "until" time and whether it's shield- or
    // session-sourced) would go stale, up to showing "I really need it" for
    // an actually shield-sourced block.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderContent()
    }

    private fun renderContent() {
        val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
        // Date, not just time: a Commitment Shield block can be many days out,
        // and a bare time ("3:45 PM") would read as today.
        val until = DateUtils.formatDateTime(this, endAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        // Defaults to SESSION: absent only if this Activity is ever launched some other way.
        val fromShield = intent.getStringExtra(EXTRA_SOURCE) == BlockSource.COMMITMENT_SHIELD.name
        setContent {
            NowFocusTheme {
                Surface(Modifier.fillMaxSize(), color = NowFocusColors.text) {
                    ShieldScreen(until = until, fromShield = fromShield, onReturn = { goHome() }, onNeedIt = { goUnlock() })
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // Whether this actually offers an exit depends on the session's mode,
    // decided by MainActivity/UnlockScreen — this screen doesn't need to know.
    private fun goUnlock() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_UNLOCK)
        )
        finish()
    }
}

@Composable
private fun ShieldScreen(until: String, fromShield: Boolean, onReturn: () -> Unit, onNeedIt: () -> Unit) {
    Column(Modifier.fillMaxSize().background(NowFocusColors.text).padding(NowFocusSpace.s6)) {
        Text(if (fromShield) "Always blocked by NowFocus" else "Shielded by NowFocus", style = kickerStyle(NowFocusColors.neutral400))
        Spacer(Modifier.height(NowFocusSpace.s8))
        Text(
            "This can wait.",
            style = headingStyle(48.sp, color = NowFocusColors.bg),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            if (fromShield) "Locked by your Commitment Shield until $until." else "You're in a focus session until $until.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 17.sp, color = NowFocusColors.neutral300),
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Back to focus", onClick = onReturn)
        // The Commitment Shield has no exit at all - not even the friction of Unlock.
        if (!fromShield) {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton("I really need it", onClick = onNeedIt)
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}
