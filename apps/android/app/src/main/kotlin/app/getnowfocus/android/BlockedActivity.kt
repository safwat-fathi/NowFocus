package app.getnowfocus.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_END_AT = "endAt"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LIMIT_MINUTES = "limitMinutes"
        /** A [BlockSource] name, or this: the app's daily limit is used up. */
        const val SOURCE_LIMIT = "LIMIT"
        const val EXTRA_PASSES_LEFT = "passesLeft"
    }

    // Same "activity builds its own repository" pattern as FocusAccessibilityService.
    private val repository by lazy { SessionRepository(this) }

    // Reloaded on every resume, not just onCreate: a singleTask screen is reused
    // for the next block, and returning from the dialer/SMS app after Call/Text
    // must rotate to whoever is now the longest-ago.
    private var person by mutableStateOf<Person?>(null)
    private var goal by mutableStateOf<String?>(null)
    private var triesToday by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderContent()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            person = PeopleRotation.next(repository.peopleFlow.first())
            goal = Goals.pick(repository.goalsFlow.first())?.text
            val now = System.currentTimeMillis()
            val from = HistoryStats.startOfDayMillis(now, java.time.ZoneId.systemDefault())
            triesToday = HistoryStats.turnedAwayCount(HistoryDatabase.get(this@BlockedActivity).dao().blockEventsBetween(from, now + 1), from, now + 1)
        }
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
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val fromShield = source == BlockSource.COMMITMENT_SHIELD.name
        val fromLimit = source == SOURCE_LIMIT
        // The pass offer: only a session window ever carries passesLeft > 0 (Normal and Strict, see Passes).
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val passesLeft = if (fromShield || fromLimit) 0 else intent.getIntExtra(EXTRA_PASSES_LEFT, 0)
        val appLabel = pkg?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        setContent {
            NowFocusTheme {
                Surface(Modifier.fillMaxSize(), color = NowFocusColors.text) {
                    ShieldScreen(
                        until = until,
                        endAt = endAt,
                        fromShield = fromShield,
                        limitMessage = if (fromLimit) "You've used your ${intent.getIntExtra(EXTRA_LIMIT_MINUTES, 0)} minutes of ${appLabel ?: "this app"} today. It's back at midnight." else null,
                        person = person,
                        goal = goal,
                        triesToday = triesToday,
                        onCall = { person?.let { reachOut(it, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(it.phone)}"))) } },
                        onText = { person?.let { reachOut(it, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(it.phone)}"))) } },
                        onReturn = { goHome() },
                        onNeedIt = { goUnlock() },
                        passLabel = if (pkg != null && passesLeft > 0) "Open ${appLabel ?: "it"} for 5 min ($passesLeft left)" else null,
                        onPass = { pkg?.let { openWithPass(it) } },
                    )
                }
            }
        }
    }

    // The write is awaited before leaving, and this screen stays open (no
    // finish()): finishing right after would cancel lifecycleScope mid-write and
    // lose the timestamp. No CALL_PHONE needed - ACTION_DIAL only opens the dialer.
    private fun reachOut(p: Person, intent: Intent) {
        lifecycleScope.launch {
            repository.updatePeople { list -> list.map { if (it.id == p.id) it.copy(lastTalkedAt = System.currentTimeMillis()) else it } }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No dialer / SMS app installed: nothing to hand off to.
            }
        }
    }

    // The pass is awaited and given a moment to reach the services before the app opens, or they would
    // bounce it again on its first window event.
    private fun openWithPass(pkg: String) {
        lifecycleScope.launch {
            if (!repository.grantPass(pkg, System.currentTimeMillis())) return@launch
            delay(400)
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            finish()
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
private fun ShieldScreen(
    until: String,
    endAt: Long,
    fromShield: Boolean,
    limitMessage: String?,
    person: Person?,
    goal: String?,
    triesToday: Int,
    onCall: () -> Unit,
    onText: () -> Unit,
    onReturn: () -> Unit,
    onNeedIt: () -> Unit,
    passLabel: String?,
    onPass: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(NowFocusColors.text).padding(NowFocusSpace.s6)) {
        Text(
            when { fromShield -> "Always blocked by NowFocus"; limitMessage != null -> "Daily limit"; else -> "Shielded by NowFocus" },
            style = kickerStyle(NowFocusColors.neutral400),
        )
        Spacer(Modifier.height(NowFocusSpace.s8))
        Text(
            "This can wait.",
            style = headingStyle(48.sp, color = NowFocusColors.bg),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            when { fromShield -> "Locked by your Commitment Shield until $until."; limitMessage != null -> limitMessage; else -> "You're in a focus session until $until." },
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 17.sp, color = NowFocusColors.neutral300),
        )
        Spacer(Modifier.height(NowFocusSpace.s4))
        // Ticks each half-minute: "1h 12m" doesn't need a per-second clock.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
        SideRow(if (fromShield) "Locked for" else if (limitMessage != null) "Back in" else "Left in session", DurationFormat.remaining(endAt - now))
        SideRow("Tries today", "$triesToday")
        if (person != null) {
            Spacer(Modifier.height(NowFocusSpace.s8))
            ReachOutCard(person, onCall, onText)
        } else if (goal != null) {
            Spacer(Modifier.height(NowFocusSpace.s8))
            Text("REMEMBER", style = kickerStyle(NowFocusColors.neutral400))
            Spacer(Modifier.height(NowFocusSpace.s2))
            Text(goal, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = NowFocusColors.bg))
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Back to focus", onClick = onReturn)
        // The Commitment Shield has no exit at all - not even the friction of Unlock.
        // The Commitment Shield and a used-up daily limit have no exit here; a limit lifts at midnight or on a cheat day.
        if (!fromShield && limitMessage == null) {
            if (passLabel != null) {
                Spacer(Modifier.height(NowFocusSpace.s2))
                ReachButton(passLabel, Modifier.fillMaxWidth(), onPass)
            }
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton("I really need it", onClick = onNeedIt)
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun SideRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s1), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = kickerStyle(NowFocusColors.neutral400), modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.bg))
    }
}

/** The urge to connect is real - point it at someone real. */
@Composable
private fun ReachOutCard(person: Person, onCall: () -> Unit, onText: () -> Unit) {
    val since = remember(person) { person.sinceLabel(System.currentTimeMillis()) }
    Text("OR REACH OUT INSTEAD", style = kickerStyle(NowFocusColors.neutral400))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        if (since != null) "You haven't talked to ${person.name} in about $since." else "Reach out to ${person.name} instead.",
        style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = NowFocusColors.bg),
    )
    Spacer(Modifier.height(NowFocusSpace.s3))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NowFocusSpace.s2)) {
        ReachButton("Call", Modifier.weight(1f), onCall)
        ReachButton("Text", Modifier.weight(1f), onText)
    }
}

// Not SecondaryButton (dark text) or PrimaryButton (red, competes with "Back to focus"): this screen is dark.
@Composable
private fun ReachButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.border(1.dp, NowFocusColors.neutral400).clickable(onClick = onClick).padding(vertical = NowFocusSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = NowFocusColors.bg))
    }
}
