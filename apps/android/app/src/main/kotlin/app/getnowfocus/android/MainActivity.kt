package app.getnowfocus.android

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NowFocusTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = NowFocusColors.bg) {
                    App(viewModel())
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data object Active : Screen
    data object Policies : Screen
    data class EditPolicy(val id: String) : Screen
}

// Same presets as the macOS menu bar picker.
private val DURATIONS = listOf(
    "25 min" to 25, "45 min" to 45, "1 hour" to 60, "1.5 hrs" to 90,
    "2 hrs" to 120, "3 hrs" to 180, "4 hrs" to 240,
)

@Composable
private fun App(viewModel: SessionViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val policies by viewModel.policies.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Bumped on resume so the health row re-reads permissions granted in Settings.
    var resumeCount by remember { mutableIntStateOf(0) }

    // Drives the displayed countdown only; endAt in persisted storage is the source of truth.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            viewModel.refreshNow()
            delay(1000)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeCount++
                viewModel.refreshNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val running = session?.let { SessionEngine.isActive(it, now) } ?: false
    // Auto-follow the underlying state: a session ending while Active is open
    // returns to Home; a session starting elsewhere (recovery) skips Setup.
    LaunchedEffect(running, screen) {
        if (running && screen == Screen.Home) screen = Screen.Active
        if (!running && screen == Screen.Active) screen = Screen.Home
    }

    val tabsVisible = screen is Screen.Home || screen is Screen.Policies || screen is Screen.EditPolicy

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    running = running,
                    session = session,
                    now = now,
                    resumeKey = resumeCount,
                    onPrimaryCta = { screen = if (running) Screen.Active else Screen.Setup },
                )
                Screen.Setup -> SetupScreen(
                    policies = policies,
                    onBack = { screen = Screen.Home },
                    onManagePolicies = { screen = Screen.Policies },
                    onStart = { policyId, minutes -> viewModel.startSession(policyId, minutes); screen = Screen.Active },
                )
                Screen.Active -> session?.let { active ->
                    ActiveScreen(session = active, now = now, onStop = { viewModel.cancelSession(); screen = Screen.Home })
                }
                Screen.Policies -> {
                    BackHandler { screen = Screen.Home }
                    PolicyListScreen(
                        policies = policies,
                        onOpen = { screen = Screen.EditPolicy(it) },
                        onAdd = { screen = Screen.EditPolicy(viewModel.addPolicy()) },
                        onDelete = viewModel::deletePolicy,
                        onBack = { screen = Screen.Home },
                    )
                }
                is Screen.EditPolicy -> {
                    BackHandler { screen = Screen.Policies }
                    val policy = policies.find { it.id == s.id }
                    if (policy != null) {
                        PolicyEditorScreen(policy, onSave = viewModel::savePolicy, onBack = { screen = Screen.Policies })
                    }
                }
            }
        }
        if (tabsVisible) {
            BottomTabBar(
                onFocus = { screen = Screen.Home },
                onRules = { screen = Screen.Policies },
                rulesSelected = screen is Screen.Policies || screen is Screen.EditPolicy,
            )
        }
    }
}

/** M1 ships Focus + Rules; Devices and Stats join in later milestones. */
@Composable
private fun BottomTabBar(onFocus: () -> Unit, onRules: () -> Unit, rulesSelected: Boolean) {
    SectionRule(thick = true)
    Row(Modifier.fillMaxWidth().background(NowFocusColors.bg)) {
        TabItem("Focus", selected = !rulesSelected, modifier = Modifier.weight(1f), onClick = onFocus)
        TabItem("Rules", selected = rulesSelected, modifier = Modifier.weight(1f), onClick = onRules)
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = NowFocusSpace.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(3.dp).fillMaxWidth().background(if (selected) NowFocusColors.accent else Color.Transparent))
        Spacer(Modifier.height(NowFocusSpace.s1))
        Text(
            label,
            style = TextStyle(
                fontFamily = ArchivoSemiBold,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = if (selected) NowFocusColors.text else NowFocusColors.neutral600,
            ),
        )
    }
}

@Composable
private fun HomeScreen(running: Boolean, session: FocusSession?, now: Long, resumeKey: Int, onPrimaryCta: () -> Unit) {
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")) }
    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.Bottom) {
            Text("NowFocus", style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), modifier = Modifier.weight(1f))
            Text(today, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
        }
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s6))

        Text(if (running) "Focusing now" else "Cross-device sync", style = kickerStyle())
        Spacer(Modifier.height(NowFocusSpace.s2))
        val heroTitle = if (running) "You're in a focus session." else "Your phone is ready."
        Text(heroTitle, style = headingStyle(38.sp))
        Spacer(Modifier.height(NowFocusSpace.s6))

        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s3))
        key(resumeKey) { HealthRow() }
        Spacer(Modifier.height(NowFocusSpace.s6))

        PrimaryButton(
            text = if (running) "Return to session" else "Start focus session",
            onClick = onPrimaryCta,
        )
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun SetupScreen(
    policies: List<BlockPolicy>,
    onBack: () -> Unit,
    onManagePolicies: () -> Unit,
    onStart: (String, Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var policyId by remember { mutableStateOf(policies.firstOrNull()?.id) }
    var minutes by remember { mutableIntStateOf(60) }
    var pendingStart by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingStart?.let { (id, mins) -> onStart(id, mins) }
        pendingStart = null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("‹ Back", onClick = onBack)
        }
        Text("New focus session", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))

        if (policies.isEmpty()) {
            Text("No policies yet.", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
            Spacer(Modifier.height(NowFocusSpace.s2))
            SecondaryButton("Manage policies", onClick = onManagePolicies)
            return@Column
        }
        val selected = policies.find { it.id == policyId } ?: policies.first()

        Text("PROFILE", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        policies.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable { policyId = p.id }.padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).background(if (p.id == selected.id) NowFocusColors.accent else Color.Transparent),
                )
                Spacer(Modifier.width(NowFocusSpace.s3))
                Column {
                    Text(p.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                    Text("${p.domains.size} sites · ${p.apps.size} apps", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
                }
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("Manage policies", onClick = onManagePolicies)

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("DURATION", style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(96.dp)) {
            items(DURATIONS) { (label, value) ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (minutes == value) NowFocusColors.text else Color.Transparent)
                        .clickable { minutes = value },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(start = NowFocusSpace.s2),
                        style = TextStyle(
                            fontFamily = ArchivoSemiBold,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (minutes == value) NowFocusColors.bg else NowFocusColors.text,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(NowFocusSpace.s6))
        PrimaryButton("Start ${DURATIONS.first { it.second == minutes }.first}") {
            val consentIntent = VpnService.prepare(context)
            if (consentIntent == null) {
                onStart(selected.id, minutes)
            } else {
                pendingStart = selected.id to minutes
                vpnConsent.launch(consentIntent)
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun ActiveScreen(session: FocusSession, now: Long, onStop: () -> Unit) {
    val remainingSeconds = (session.endAt - now).coerceAtLeast(0) / 1000
    val hours = remainingSeconds / 3600
    val minutes = remainingSeconds % 3600 / 60
    val seconds = remainingSeconds % 60
    val remaining = if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    val progress = ((now - session.startAt).toFloat() / (session.endAt - session.startAt).toFloat()).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().background(NowFocusColors.accent).padding(NowFocusSpace.s6)) {
        Text("Focus session", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.text))
        Spacer(Modifier.height(NowFocusSpace.s6))
        Text("You're in it. Keep going.", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.text))
        Text(remaining, style = headingStyle(72.sp, color = NowFocusColors.bg))
        Spacer(Modifier.height(NowFocusSpace.s3))
        Box(Modifier.fillMaxWidth().height(6.dp).background(NowFocusColors.text.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(NowFocusColors.bg))
        }
        Spacer(Modifier.weight(1f))
        // Exit friction (Strict/Locked) lands in a later milestone; Normal mode stops immediately.
        SecondaryButton("End session early", onClick = onStop)
    }
}

/** Android counterpart of the macOS health dot + "needs approval" hint. */
@Composable
private fun HealthRow() {
    val context = LocalContext.current
    val vpnOk = Enforcement.isVpnPermitted(context)
    val appsOk = Enforcement.isAccessibilityEnabled(context)

    Text(
        if (vpnOk) "Website blocking: ready" else "Website blocking: VPN permission asked on start",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = if (vpnOk) NowFocusColors.text else NowFocusColors.accent700),
    )
    Text(
        if (appsOk) "App blocking: ready" else "App blocking: off",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = if (appsOk) NowFocusColors.text else NowFocusColors.accent700),
    )
    if (!appsOk) {
        Spacer(Modifier.height(NowFocusSpace.s1))
        GhostButton("Enable in Accessibility Settings") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Text(
            "Toggle greyed out? Open App info → ⋮ → Allow restricted settings first.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
        )
    }
}
