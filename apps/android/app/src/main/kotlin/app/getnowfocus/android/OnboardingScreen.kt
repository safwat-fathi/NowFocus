package app.getnowfocus.android

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4 steps: Welcome, Goals, People, Permissions. The mockup's pairing-code step (a
 * Mac/PC) doesn't apply here - cross-device sync is Phase 6 in
 * native_tech_stack_spec.md, not built yet. Goals and People are skippable.
 */
@Composable
fun OnboardingScreen(
    resumeKey: Int,
    goals: List<Goal>,
    onAddGoal: (text: String, priority: GoalPriority) -> Unit,
    onRemoveGoal: (id: String) -> Unit,
    people: List<Person>,
    onAddPerson: (name: String, phone: String) -> Unit,
    onRemovePerson: (id: String) -> Unit,
    onSetLastTalked: (id: String, lastTalkedAt: Long?) -> Unit,
    onDone: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(NowFocusSpace.s6)) {
        Row(Modifier.fillMaxWidth()) {
            repeat(4) { i ->
                Spacer(Modifier.width(NowFocusSpace.s1))
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).height(4.dp).background(if (i <= step) NowFocusColors.accent else NowFocusColors.neutral300),
                )
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s6))
        // Scrolls: the Goals and People steps grow with each entry added.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> OnboardingWelcome()
                1 -> OnboardingGoals(goals, onAddGoal, onRemoveGoal)
                2 -> OnboardingPeople(people, onAddPerson, onRemovePerson, onSetLastTalked)
                else -> OnboardingPermissions(resumeKey)
            }
        }
        PrimaryButton(
            when {
                step == 0 -> "Let's set it up"
                step == 1 -> if (goals.isEmpty()) "Skip for now" else "Continue"
                step == 2 -> if (people.isEmpty()) "Skip for now" else "Continue"
                else -> "Get started"
            },
        ) {
            if (step < 3) step++ else onDone()
        }
    }
}

@Composable
private fun OnboardingGoals(
    goals: List<Goal>,
    onAdd: (text: String, priority: GoalPriority) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    Text("STEP 2 OF 4 · GOALS", style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text("What are you focusing for?", style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        "We'll remind you while you wait to end a Strict session early, and when a blocked app opens. Stays on this phone.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)
    GoalsEditor(goals, onAdd, onRemove)
}

@Composable
private fun OnboardingPeople(
    people: List<Person>,
    onAdd: (name: String, phone: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onSetLastTalked: (id: String, lastTalkedAt: Long?) -> Unit,
) {
    Text("STEP 3 OF 4 · PEOPLE", style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text("Who matters to you?", style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        "When a blocked app opens, we'll point you to someone you haven't talked to in a while, with one tap to call or text. Nothing leaves this phone.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)
    PeopleEditor(people, onAdd, onRemove, onSetLastTalked)
}

@Composable
private fun OnboardingWelcome() {
    Text("WELCOME TO NOWFOCUS", style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text("You decide what deserves your attention.", style = headingStyle(38.sp))
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(
        "Start a focus session and the sites and apps you pick stop reaching you.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 16.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s6))
    SectionRule(thick = true)
    listOf(
        "One tap starts a focus session",
        "Blocks the sites and apps you pick",
        "No 5-second off switch, if you choose Strict or Locked",
    ).forEachIndexed { i, text ->
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3)) {
            Text(
                String.format("%02d", i + 1),
                style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NowFocusColors.accent700),
                modifier = Modifier.width(24.dp),
            )
            Text(text, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
        }
        SectionRule()
    }
}

@Composable
private fun OnboardingPermissions(resumeKey: Int) {
    val context = LocalContext.current
    // Combined with the passed-in resumeKey (bumped on ON_RESUME by the
    // caller): granting Accessibility or Notification access happens in
    // Settings, away from this screen entirely, so only a resume signal -
    // not the VPN result alone - catches those.
    var vpnResumeKey by remember { mutableIntStateOf(0) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vpnResumeKey++ }

    Text("STEP 4 OF 4 · PERMISSIONS", style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text("A few permissions, so blocking actually holds.", style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        "Everything runs on this phone. Your browsing and app lists never leave it.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)

    key(resumeKey, vpnResumeKey) {
        val accessibilityOk = Enforcement.isAccessibilityEnabled(context)
        val vpnOk = Enforcement.isVpnPermitted(context)
        val notifOk = context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false

        PermissionRow("App blocking", "Spots when a blocked app opens. With partial blocking on, also reads the YouTube, Facebook, Instagram and X screens during a session (experimental), on-device only", accessibilityOk) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!accessibilityOk) {
            Text(
                "Toggle greyed out? Open App info → ⋮ → Allow restricted settings first.",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
            )
        }
        PermissionRow("Website filter", "Blocks sites on-device, no traffic routed anywhere", vpnOk) {
            val consentIntent = VpnService.prepare(context)
            if (consentIntent != null) vpnConsent.launch(consentIntent) else vpnResumeKey++
        }
        PermissionRow("Notification access", "Lets Bedtime Wind-Down quiet things down", notifOk) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }
}

@Composable
private fun PermissionRow(title: String, sub: String, granted: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
            Text(sub, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
        }
        if (granted) {
            TagPill("Allowed", accent = false)
        } else {
            // Not PrimaryButton: it fills max width by design, meant for a
            // standalone CTA, not an inline row action next to other content.
            SecondaryButton("Allow", onClick = onAllow)
        }
    }
    SectionRule()
}
