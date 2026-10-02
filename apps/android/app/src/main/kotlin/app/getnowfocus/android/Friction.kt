package app.getnowfocus.android

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Apps you've asked NowFocus to make you pause for ([SessionRepository.frictionAppsFlow]): opening one
 * shows [FrictionActivity] first, a breath and a question. Answering lets it open and stay open for
 * [OPEN_MS], so it doesn't ask again every time you glance at it. In memory only: a restart asks again.
 */
object FrictionGate {
    const val OPEN_MS = 10 * 60_000L
    const val BREATH_SECONDS = 10

    private val allowedUntil = ConcurrentHashMap<String, Long>()

    fun allow(pkg: String, now: Long) { allowedUntil[pkg] = now + OPEN_MS }
    fun isAllowed(pkg: String, now: Long): Boolean = (allowedUntil[pkg] ?: 0L) > now
}

val FRICTION_INTENTS = listOf("Something specific", "Just bored", "Avoiding something")

class FrictionActivity : ComponentActivity() {
    companion object { const val EXTRA_PACKAGE = "package" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("this app")
        setContent {
            NowFocusTheme {
                Surface(Modifier.fillMaxSize(), color = NowFocusColors.text) {
                    FrictionScreen(
                        appLabel = label,
                        onOpen = { FrictionGate.allow(pkg, System.currentTimeMillis()); finish() },
                        onNotNow = {
                            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FrictionScreen(appLabel: String, onOpen: () -> Unit, onNotNow: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(FrictionGate.BREATH_SECONDS) }
    var intent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (secondsLeft > 0) { delay(1000); secondsLeft-- } }
    val ready = secondsLeft == 0 && intent != null

    Column(Modifier.fillMaxSize().background(NowFocusColors.text).padding(NowFocusSpace.s6)) {
        Text("A PAUSE BEFORE ${appLabel.uppercase()}", style = kickerStyle(NowFocusColors.neutral400))
        Spacer(Modifier.height(NowFocusSpace.s8))
        // Four seconds in, four out: the text changes, nothing animates, so it stays calm and cheap.
        val phase = if ((FrictionGate.BREATH_SECONDS - secondsLeft) / 4 % 2 == 0) "Breathe in" else "Breathe out"
        Text(if (secondsLeft > 0) phase else "Ready when you are.", style = headingStyle(44.sp, color = NowFocusColors.bg))
        Spacer(Modifier.height(NowFocusSpace.s2))
        if (secondsLeft > 0) Text("$secondsLeft", style = headingStyle(72.sp, color = NowFocusColors.neutral400))
        Spacer(Modifier.height(NowFocusSpace.s6))
        Text("What do you want to do here?", style = TextStyle(fontFamily = ArchivoSemiBold, fontSize = 18.sp, color = NowFocusColors.bg))
        Spacer(Modifier.height(NowFocusSpace.s3))
        FRICTION_INTENTS.forEach { option ->
            Chip(option, intent == option) { intent = option }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Open $appLabel", enabled = ready, onClick = onOpen)
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("Not now", onClick = onNotNow)
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}
