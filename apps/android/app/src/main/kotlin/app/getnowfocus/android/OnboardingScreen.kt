package app.getnowfocus.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.stringResource

/**
 * 5 steps: Welcome, Goals, People, Permissions, First session. The mockup's pairing-code step (a
 * Mac/PC) doesn't apply here - cross-device sync is Phase 6 in
 * native_tech_stack_spec.md, not built yet. Goals and People are skippable, and so is the last step.
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
    onStartFirstSession: (apps: List<AppRule>) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<List<AppRule>>(emptyList()) }
    // Re-read on resume (resumeKey): Accessibility is granted in Settings, away from this screen.
    val accessibilityOk = remember(resumeKey) { Enforcement.isAccessibilityEnabled(context) }
    // For the session notification and the blocked-site note; denied just means neither appears.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(step) {
        if (step == LAST_STEP && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column(Modifier.fillMaxSize().padding(NowFocusSpace.s6)) {
        Row(Modifier.fillMaxWidth()) {
            repeat(LAST_STEP + 1) { i ->
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
                3 -> OnboardingPermissions(resumeKey)
                else -> OnboardingFirstSession(
                    picked = picked,
                    onPick = { picked = picked + it },
                    onUnpick = { pkg -> picked = picked.filterNot { it.packageName == pkg } },
                    accessibilityOk = accessibilityOk,
                )
            }
        }
        if (step < LAST_STEP) {
            PrimaryButton(
                stringResource(
                    when (step) {
                        0 -> R.string.onb_setup
                        1 -> if (goals.isEmpty()) R.string.onb_skip else R.string.onb_continue
                        2 -> if (people.isEmpty()) R.string.onb_skip else R.string.onb_continue
                        else -> R.string.onb_continue
                    },
                ),
            ) { step++ }
        } else {
            // Blocking has to work before the "win": with Accessibility off the picked apps are never bounced.
            PrimaryButton(stringResource(R.string.onb_start), enabled = picked.isNotEmpty() && accessibilityOk) { onStartFirstSession(picked) }
            GhostButton(stringResource(R.string.friction_not_now), onClick = onDone)
        }
    }
}

private const val LAST_STEP = 4

@Composable
private fun OnboardingFirstSession(
    picked: List<AppRule>,
    onPick: (AppRule) -> Unit,
    onUnpick: (packageName: String) -> Unit,
    accessibilityOk: Boolean,
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    Text(stringResource(R.string.onb_step5), style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(stringResource(R.string.onb_step5_title), style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.onb_step5_body),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)
    picked.forEach { app -> RuleRow(app.label, app.packageName) { onUnpick(app.packageName) } }
    GhostButton(stringResource(if (picked.isEmpty()) R.string.onb_pick_app else R.string.onb_add_another)) { picking = true }
    if (!accessibilityOk) {
        Spacer(Modifier.height(NowFocusSpace.s4))
        PermissionRow(stringResource(R.string.devices_app_blocking), stringResource(R.string.onb_blocking_needed), false) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Text(stringResource(R.string.restricted_settings_hint), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
    }
    if (picking) {
        AppPickerDialog(
            exclude = picked.map { it.packageName }.toSet(),
            onPick = { onPick(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun OnboardingGoals(
    goals: List<Goal>,
    onAdd: (text: String, priority: GoalPriority) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    Text(stringResource(R.string.onb_step2), style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(stringResource(R.string.onb_step2_title), style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.onb_step2_body),
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
    Text(stringResource(R.string.onb_step3), style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(stringResource(R.string.onb_step3_title), style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.onb_step3_body),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)
    PeopleEditor(people, onAdd, onRemove, onSetLastTalked)
}

@Composable
private fun OnboardingWelcome() {
    Text(stringResource(R.string.onb_welcome), style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(stringResource(R.string.onb_welcome_title), style = headingStyle(38.sp))
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(
        stringResource(R.string.onb_welcome_body),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 16.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s6))
    SectionRule(thick = true)
    listOf(
        stringResource(R.string.onb_point1),
        stringResource(R.string.onb_point2),
        stringResource(R.string.onb_point3),
    ).forEachIndexed { i, text ->
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3)) {
            Text(
                String.format(java.util.Locale.ROOT, "%02d", i + 1),
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

    Text(stringResource(R.string.onb_step4), style = kickerStyle())
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(stringResource(R.string.onb_step4_title), style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.onb_step4_body),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    SectionRule(thick = true)

    key(resumeKey, vpnResumeKey) {
        val accessibilityOk = Enforcement.isAccessibilityEnabled(context)
        val vpnOk = Enforcement.isVpnPermitted(context)
        val notifOk = context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false

        PermissionRow(stringResource(R.string.devices_app_blocking), stringResource(R.string.onb_perm_blocking), accessibilityOk) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!accessibilityOk) {
            Text(stringResource(R.string.restricted_settings_hint), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
        }
        PermissionRow(stringResource(R.string.devices_website_filter), stringResource(R.string.onb_perm_filter), vpnOk) {
            val consentIntent = VpnService.prepare(context)
            if (consentIntent != null) vpnConsent.launch(consentIntent) else vpnResumeKey++
        }
        PermissionRow(stringResource(R.string.devices_dnd), stringResource(R.string.onb_perm_dnd), notifOk) { Enforcement.openDndAccess(context) }
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
            TagPill(stringResource(R.string.onb_allowed), accent = false)
        } else {
            // Not PrimaryButton: it fills max width by design, meant for a
            // standalone CTA, not an inline row action next to other content.
            SecondaryButton(stringResource(R.string.onb_allow), onClick = onAllow)
        }
    }
    SectionRule()
}
