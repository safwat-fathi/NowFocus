package app.getnowfocus.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_UNLOCK = "unlock"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startOnUnlock = intent.getStringExtra(EXTRA_ROUTE) == ROUTE_UNLOCK
        setContent {
            NowFocusTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = NowFocusColors.bg) {
                    App(viewModel(), startOnUnlock = startOnUnlock)
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data object Active : Screen
    data object Unlock : Screen
    data object Policies : Screen
    data class EditPolicy(val id: String) : Screen
    data object Stats : Screen
    data object Commitment : Screen
    data object Bedtime : Screen
    data object Devices : Screen
    data object Onboarding : Screen
    data object People : Screen
    data object Goals : Screen
}

// Same presets as the macOS menu bar picker.
private val DURATIONS = listOf(
    "25 min" to 25, "45 min" to 45, "1 hour" to 60, "1.5 hrs" to 90,
    "2 hrs" to 120, "3 hrs" to 180, "4 hrs" to 240,
)

private const val UNLOCK_SENTENCE = "I am choosing to end this focus session early."
private const val UNLOCK_WAIT_MS = 30_000L

@Composable
private fun App(viewModel: SessionViewModel, startOnUnlock: Boolean = false) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(if (startOnUnlock) Screen.Unlock else Screen.Home) }
    // Commitment and Bedtime open from both Home and Rules; Back returns to whichever opened them.
    var backTo by remember { mutableStateOf<Screen>(Screen.Home) }
    val policies by viewModel.policies.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val sessionLoaded by viewModel.sessionLoaded.collectAsStateWithLifecycle()
    val shield by viewModel.commitmentShield.collectAsStateWithLifecycle()
    val bedtime by viewModel.bedtimeSettings.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    // Boot count doesn't change during a session, so it's read once, not ticked.
    val bootCount = remember {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
    }
    // Bumped on resume so the health row re-reads permissions granted in Settings.
    var resumeCount by remember { mutableIntStateOf(0) }

    // Drives the displayed countdown only; endAt in persisted storage is the source of truth.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            elapsedNow = android.os.SystemClock.elapsedRealtime()
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
    // Auto-follow the underlying state: a session ending while Active/Unlock is
    // open returns to Home; a session starting elsewhere (recovery) skips Setup.
    // Gated on sessionLoaded: a just-recreated ViewModel (e.g. the Shield's "I
    // really need it" launches MainActivity fresh at the Unlock route) briefly
    // reports running=false before its first real read of sessionFlow - acting
    // on that stale value would bounce straight back to Home before the
    // actually-active session ever gets a chance to load.
    LaunchedEffect(running, screen, sessionLoaded) {
        if (!sessionLoaded) return@LaunchedEffect
        if (running && screen == Screen.Home) screen = Screen.Active
        if (!running && (screen == Screen.Active || screen == Screen.Unlock)) screen = Screen.Home
    }
    // onboardingDone starts eagerly true (see SessionViewModel) until the real,
    // persisted value loads - this redirects the moment a first-run app learns
    // it hasn't onboarded yet, rather than gating the initial screen on a value
    // that isn't available synchronously at composition time.
    LaunchedEffect(onboardingDone) {
        if (!onboardingDone && screen == Screen.Home) screen = Screen.Onboarding
    }

    val tabsVisible = screen is Screen.Home || screen is Screen.Policies || screen is Screen.EditPolicy ||
        screen is Screen.Stats || screen is Screen.Devices || screen is Screen.Active

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    running = running,
                    session = session,
                    shield = shield,
                    bedtime = bedtime,
                    now = now,
                    resumeKey = resumeCount,
                    onPrimaryCta = { screen = if (running) Screen.Active else Screen.Setup },
                    onOpenCommitment = { backTo = Screen.Home; screen = Screen.Commitment },
                    onOpenBedtime = { backTo = Screen.Home; screen = Screen.Bedtime },
                )
                Screen.Setup -> SetupScreen(
                    policies = policies,
                    onBack = { screen = Screen.Home },
                    onManagePolicies = { screen = Screen.Policies },
                    onStart = { policyId, minutes, mode -> viewModel.startSession(policyId, minutes, mode); screen = Screen.Active },
                )
                Screen.Active -> session?.let { active ->
                    ActiveScreen(
                        session = active,
                        now = now,
                        onEndEarly = {
                            if (active.enforcementMode == EnforcementMode.NORMAL) {
                                viewModel.cancelSession()
                                screen = Screen.Home
                            } else {
                                screen = Screen.Unlock
                            }
                        },
                    )
                }
                Screen.Unlock -> session?.let { active ->
                    UnlockScreen(
                        session = active,
                        now = now,
                        onCancel = { completed, listened -> viewModel.cancelSession(completed, listened) },
                        onCancelled = { screen = Screen.Home },
                        onBack = { screen = Screen.Active },
                        goals = goals,
                    )
                }
                Screen.Policies -> {
                    BackHandler { screen = Screen.Home }
                    PolicyListScreen(
                        policies = policies,
                        shield = shield,
                        bedtime = bedtime,
                        now = now,
                        onOpen = { screen = Screen.EditPolicy(it) },
                        onAdd = { screen = Screen.EditPolicy(viewModel.addPolicy()) },
                        onDelete = viewModel::deletePolicy,
                        onBack = { screen = Screen.Home },
                        onOpenCommitment = { backTo = Screen.Policies; screen = Screen.Commitment },
                        onOpenBedtime = { backTo = Screen.Policies; screen = Screen.Bedtime },
                        peopleCount = people.size,
                        onOpenPeople = { screen = Screen.People },
                        goalsCount = goals.size,
                        onOpenGoals = { screen = Screen.Goals },
                    )
                }
                is Screen.EditPolicy -> {
                    BackHandler { screen = Screen.Policies }
                    val policy = policies.find { it.id == s.id }
                    if (policy != null) {
                        PolicyEditorScreen(policy, onSave = viewModel::savePolicy, onBack = { screen = Screen.Policies })
                    }
                }
                Screen.Stats -> StatsScreen()
                Screen.Commitment -> CommitmentScreen(
                    shield = shield,
                    now = now,
                    elapsedNow = elapsedNow,
                    bootCount = bootCount,
                    onCreate = viewModel::createCommitmentShield,
                    onCancel = { viewModel.cancelCommitmentShield() },
                    onBack = { screen = backTo },
                )
                Screen.Bedtime -> BedtimeScreen(settings = bedtime, policies = policies, onSave = viewModel::saveBedtimeSettings, onBack = { screen = backTo })
                Screen.Devices -> DevicesScreen(resumeKey = resumeCount)
                Screen.Onboarding -> OnboardingScreen(
                    resumeKey = resumeCount,
                    goals = goals,
                    onAddGoal = viewModel::addGoal,
                    onRemoveGoal = viewModel::removeGoal,
                    people = people,
                    onAddPerson = { name, phone -> viewModel.addPerson(name, phone, lastTalkedAt = null) },
                    onRemovePerson = viewModel::removePerson,
                    onSetLastTalked = viewModel::setLastTalked,
                    onDone = { viewModel.completeOnboarding(); screen = Screen.Home },
                )
                Screen.People -> PeopleScreen(
                    people = people,
                    onAdd = { name, phone -> viewModel.addPerson(name, phone, lastTalkedAt = null) },
                    onRemove = viewModel::removePerson,
                    onSetLastTalked = viewModel::setLastTalked,
                    onBack = { screen = Screen.Policies },
                )
                Screen.Goals -> GoalsScreen(
                    goals = goals,
                    onAdd = viewModel::addGoal,
                    onRemove = viewModel::removeGoal,
                    onBack = { screen = Screen.Policies },
                )
            }
        }
        if (tabsVisible) {
            BottomTabBar(
                onFocus = { screen = if (running) Screen.Active else Screen.Home },
                onRules = { screen = Screen.Policies },
                onDevices = { screen = Screen.Devices },
                onStats = { screen = Screen.Stats },
                selected = screen,
            )
        }
    }
}

@Composable
private fun BottomTabBar(onFocus: () -> Unit, onRules: () -> Unit, onDevices: () -> Unit, onStats: () -> Unit, selected: Screen) {
    val rulesSelected = selected is Screen.Policies || selected is Screen.EditPolicy
    val devicesSelected = selected is Screen.Devices
    val statsSelected = selected is Screen.Stats
    SectionRule(thick = true)
    Row(Modifier.fillMaxWidth().background(NowFocusColors.bg)) {
        TabItem("Focus", selected = !rulesSelected && !devicesSelected && !statsSelected, modifier = Modifier.weight(1f), onClick = onFocus)
        TabItem("Rules", selected = rulesSelected, modifier = Modifier.weight(1f), onClick = onRules)
        TabItem("Devices", selected = devicesSelected, modifier = Modifier.weight(1f), onClick = onDevices)
        TabItem("Stats", selected = statsSelected, modifier = Modifier.weight(1f), onClick = onStats)
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Bar first and flush with the rule above (no top padding), inset from the tab edges.
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(horizontal = NowFocusSpace.s4).height(3.dp).fillMaxWidth()
                .background(if (selected) NowFocusColors.accent else Color.Transparent),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            label,
            style = TextStyle(
                fontFamily = ArchivoSemiBold,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = if (selected) NowFocusColors.text else NowFocusColors.neutral600,
            ),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
    }
}

@Composable
private fun HomeScreen(
    running: Boolean,
    session: FocusSession?,
    shield: CommitmentShield?,
    bedtime: BedtimeSettings,
    now: Long,
    resumeKey: Int,
    onPrimaryCta: () -> Unit,
    onOpenCommitment: () -> Unit,
    onOpenBedtime: () -> Unit,
) {
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")) }
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    // Today's numbers, re-read when the user returns or a session starts/ends.
    var todayRows by remember { mutableStateOf<List<SessionHistoryRow>>(emptyList()) }
    var todayEvents by remember { mutableStateOf<List<BlockEventRow>>(emptyList()) }
    LaunchedEffect(resumeKey, running) {
        val from = HistoryStats.startOfDayMillis(System.currentTimeMillis(), ZoneId.systemDefault())
        val to = from + 24 * 60 * 60 * 1000L
        val dao = HistoryDatabase.get(context).dao()
        todayRows = dao.sessionsBetween(from, to)
        todayEvents = dao.blockEventsBetween(from, to)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.Bottom) {
            Text("NowFocus", style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), modifier = Modifier.weight(1f))
            Text(today, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
        }
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s6))

        // "Cross-device sync" was M1 leftover mockup copy for a feature this
        // build doesn't have (that's Phase 6, not built) - left in by mistake.
        Text(if (running) "Focusing now" else "Ready when you are", style = kickerStyle())
        Spacer(Modifier.height(NowFocusSpace.s2))
        val heroTitle = if (running) "You're in a focus session." else "Your phone is ready."
        Text(heroTitle, style = headingStyle(38.sp))
        Spacer(Modifier.height(NowFocusSpace.s6))

        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s3))
        key(resumeKey) { HealthRow() }
        SectionRule()
        val dayFrom = HistoryStats.startOfDayMillis(now, ZoneId.systemDefault())
        val dayTo = dayFrom + 24 * 60 * 60 * 1000L
        val focusedMinutes = HistoryStats.totalFocusedMillis(todayRows, dayFrom, dayTo) / 60_000
        Row(Modifier.fillMaxWidth()) {
            StatCell("Today", "${focusedMinutes / 60}h ${focusedMinutes % 60}m", Modifier.weight(1f))
            StatCell("Turned away", "${HistoryStats.turnedAwayCount(todayEvents, dayFrom, dayTo)}", Modifier.weight(1f))
            StatCell("Sessions", "${HistoryStats.sessionsCount(todayRows, dayFrom, dayTo)}", Modifier.weight(1f))
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s6))

        PrimaryButton(
            text = if (running) "Return to session" else "Start focus session",
            onClick = onPrimaryCta,
        )

        val showShieldRow = shield != null && shield.endAt > now
        if (showShieldRow || bedtime.enabled) {
            Spacer(Modifier.height(NowFocusSpace.s6))
            Text("ALWAYS ON", style = kickerStyle(NowFocusColors.neutral700))
            if (showShieldRow) {
                val daysLeft = ((shield!!.endAt - now) / 86_400_000L + 1).coerceAtLeast(1)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenCommitment).padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Commitment Shield", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    TagPill("$daysLeft days left")
                }
                SectionRule()
            }
            if (bedtime.enabled) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenBedtime).padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Bedtime Wind-Down", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    TagPill("Tonight ${formatClock(bedtime.windDownMinute, is24Hour)}", accent = false)
                }
                SectionRule()
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun SetupScreen(
    policies: List<BlockPolicy>,
    onBack: () -> Unit,
    onManagePolicies: () -> Unit,
    onStart: (String, Int, EnforcementMode) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var policyId by remember { mutableStateOf(policies.firstOrNull()?.id) }
    var minutes by remember { mutableIntStateOf(60) }
    var mode by remember { mutableStateOf(EnforcementMode.NORMAL) }
    var pendingStart by remember { mutableStateOf<Triple<String, Int, EnforcementMode>?>(null) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingStart?.let { (id, mins, m) -> onStart(id, mins, m) }
        pendingStart = null
    }
    // For the ongoing session notification; denied just means no notification.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // STRICT's optional voice note (see VoiceNote): recorded to pending.m4a, which startSession adopts.
    var hasNote by remember { mutableStateOf(VoiceNote.pending(context).exists()) }
    var recording by remember { mutableStateOf(false) }
    var recordedSeconds by remember { mutableIntStateOf(0) }
    var noteMessage by remember { mutableStateOf<String?>(null) }
    val recorder = remember {
        VoiceRecorder(context, VoiceNote.pending(context)) { usable ->
            recording = false
            hasNote = usable
            noteMessage = if (usable) null else "That recording didn't work. Try again."
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) {
        recordedSeconds = 0
        while (recording) {
            delay(1000)
            recordedSeconds++
        }
    }
    fun startRecording() {
        noteMessage = null
        recording = recorder.start()
        if (!recording) {
            hasNote = false // a failed start leaves no file behind
            noteMessage = "Couldn't start recording. Is another app using the mic?"
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else noteMessage = "Mic access is off, so Strict will use the typed sentence and 30-second wait."
    }

    // Scrolls: the STRICT voice-note section makes this taller than a small screen.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("‹ Back", onClick = onBack)
        }
        Text("New focus session", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))

        if (policies.isEmpty()) {
            Text("No profiles yet.", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
            Spacer(Modifier.height(NowFocusSpace.s2))
            SecondaryButton("Manage profiles", onClick = onManagePolicies)
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
        GhostButton("Manage profiles", onClick = onManagePolicies)

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

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("IF I WANT TO STOP EARLY", style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            options = listOf("Normal" to EnforcementMode.NORMAL, "Strict" to EnforcementMode.STRICT, "Locked" to EnforcementMode.LOCKED),
            selected = mode,
            onSelect = { mode = it },
        )
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            when (mode) {
                EnforcementMode.NORMAL -> "You can end any time. Good for light days."
                EnforcementMode.STRICT -> "To leave early you'll type a short sentence, then wait 30 seconds."
                EnforcementMode.LOCKED -> "No early exit on this device until the timer ends."
            },
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral800),
        )

        if (mode == EnforcementMode.STRICT) {
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text("YOUR VOICE NOTE", style = kickerStyle(NowFocusColors.neutral700))
            Spacer(Modifier.height(NowFocusSpace.s1))
            Text(
                "Optional. Record 10 seconds to yourself; to leave early you'll have to hear it first. Skip it and Strict works as described above.",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral800),
            )
            Spacer(Modifier.height(NowFocusSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    SecondaryButton("Stop · ${recordedSeconds}s") { recorder.stop() }
                } else {
                    SecondaryButton(if (hasNote) "Re-record" else "Record") {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (hasNote) {
                        Spacer(Modifier.width(NowFocusSpace.s2))
                        TagPill("Recorded", accent = false)
                    }
                }
            }
            noteMessage?.let {
                Text(
                    it,
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
                    modifier = Modifier.padding(top = NowFocusSpace.s1),
                )
            }
        }

        Spacer(Modifier.height(NowFocusSpace.s6))
        PrimaryButton("Start ${DURATIONS.first { it.second == minutes }.first}") {
            // Finalize an in-progress note first so startSession adopts a complete file.
            if (recording) recorder.stop()
            val consentIntent = VpnService.prepare(context)
            if (consentIntent == null) {
                onStart(selected.id, minutes, mode)
            } else {
                pendingStart = Triple(selected.id, minutes, mode)
                vpnConsent.launch(consentIntent)
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun ActiveScreen(session: FocusSession, now: Long, onEndEarly: () -> Unit) {
    val remainingSeconds = (session.endAt - now).coerceAtLeast(0) / 1000
    val hours = remainingSeconds / 3600
    val minutes = remainingSeconds % 3600 / 60
    val seconds = remainingSeconds % 60
    val remaining = if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    val progress = ((now - session.startAt).toFloat() / (session.endAt - session.startAt).toFloat()).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().background(NowFocusColors.accent).padding(NowFocusSpace.s6)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Focus session", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.text), modifier = Modifier.weight(1f))
            Text(
                session.enforcementMode.name,
                style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NowFocusColors.text, letterSpacing = 1.sp),
            )
        }
        Spacer(Modifier.height(NowFocusSpace.s6))
        Text("You're in it. Keep going.", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.text))
        Text(remaining, style = headingStyle(72.sp, color = NowFocusColors.bg))
        Spacer(Modifier.height(NowFocusSpace.s3))
        Box(Modifier.fillMaxWidth().height(6.dp).background(NowFocusColors.text.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(NowFocusColors.bg))
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton(
            if (session.enforcementMode == EnforcementMode.LOCKED) "Locked until timer ends" else "End session early",
            onClick = onEndEarly,
        )
    }
}

@Composable
private fun UnlockScreen(
    session: FocusSession,
    now: Long,
    // (unlockCompleted, listened). `listened` must reach SessionViewModel.cancelSession, or the
    // button below enables but the ViewModel refuses, and a STRICT session with a note acts LOCKED.
    onCancel: (Boolean, Boolean) -> Boolean,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
    goals: List<Goal>,
) {
    BackHandler(onBack = onBack)
    // Picked once per visit, so the reminder doesn't change under the user's eyes each second.
    val goal = remember { Goals.pick(goals)?.text }
    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back to focus", onClick = onBack)
        Text("End early?", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))

        when (session.enforcementMode) {
            EnforcementMode.NORMAL -> {
                Text("End this session now?", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 16.sp))
                Spacer(Modifier.height(NowFocusSpace.s4))
                PrimaryButton("End session now") { if (onCancel(false, false)) onCancelled() }
            }
            EnforcementMode.LOCKED -> {
                Text("This one's locked, by you.", style = headingStyle(28.sp))
                Spacer(Modifier.height(NowFocusSpace.s2))
                Text(
                    "You chose Locked when you started this session, so there's no early exit. You've got this.",
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                )
                Spacer(Modifier.height(NowFocusSpace.s4))
                PrimaryButton("Back to focus", onClick = onBack)
            }
            EnforcementMode.STRICT -> {
                var typed by remember { mutableStateOf("") }
                var waitEndAt by remember { mutableStateOf<Long?>(null) }
                val waiting = waitEndAt != null
                val waitRemainingMs = waitEndAt?.let { (it - now).coerceAtLeast(0) } ?: 0L
                val waitDone = waiting && waitRemainingMs == 0L
                val matched = typed.trim() == UNLOCK_SENTENCE

                // A note to hear before leaving, if this session has one. Same helper as
                // SessionViewModel.cancelSession, so both agree on whether a note exists.
                val note = remember(session.voiceNotePath) { VoiceNote.playableFile(session) }
                var playing by remember { mutableStateOf(false) }
                var listened by remember { mutableStateOf(false) }
                val player = remember(note) { note?.let { VoiceNotePlayer(it) { playing = false; listened = true } } }
                DisposableEffect(player) { onDispose { player?.release() } }
                val canEnd = waitDone && (player == null || listened)

                if (!waiting) {
                    Text(
                        "No judgement. Type this out, word for word, so it's a choice and not a reflex.",
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                    )
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    Text("“$UNLOCK_SENTENCE”", style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp))
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(NowFocusSpace.s4))
                    PrimaryButton("Start 30-second pause", enabled = matched) { waitEndAt = System.currentTimeMillis() + UNLOCK_WAIT_MS }
                } else {
                    Text(
                        if (player == null) "Take a breath. If you still want out when this hits zero, it's yours."
                        else "Take a breath, then hear what you told yourself. You can leave once you have.",
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                    )
                    if (goal != null) {
                        Spacer(Modifier.height(NowFocusSpace.s3))
                        Text("REMEMBER WHY YOU STARTED", style = kickerStyle(NowFocusColors.neutral700))
                        Text(goal, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    }
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    Text("${waitRemainingMs / 1000 + if (waitRemainingMs % 1000 > 0) 1 else 0}", style = headingStyle(96.sp, color = NowFocusColors.accent))
                    Spacer(Modifier.height(NowFocusSpace.s4))
                    if (player != null) {
                        SecondaryButton(
                            when {
                                playing -> "Playing…"
                                listened -> "Play again"
                                else -> "Play your note"
                            },
                        ) {
                            if (!playing) {
                                playing = true
                                player.play()
                            }
                        }
                        Spacer(Modifier.height(NowFocusSpace.s2))
                    }
                    PrimaryButton(
                        when {
                            canEnd -> "End session now"
                            waitDone -> "Hear your note first"
                            else -> "Waiting…"
                        },
                        enabled = canEnd,
                    ) { if (onCancel(true, listened)) onCancelled() }
                }
                Spacer(Modifier.height(NowFocusSpace.s2))
                SecondaryButton("Stay focused", onClick = onBack)
            }
        }
    }
}

@Composable
private fun StatsScreen() {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val monday = remember { today.with(DayOfWeek.MONDAY) }
    val weekFrom = remember { monday.atStartOfDay(zone).toInstant().toEpochMilli() }
    val weekTo = remember { monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() }

    var allSessions by remember { mutableStateOf<List<SessionHistoryRow>>(emptyList()) }
    var weekEvents by remember { mutableStateOf<List<BlockEventRow>>(emptyList()) }
    var urgeEvents by remember { mutableStateOf<List<BlockEventRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        val dao = HistoryDatabase.get(context).dao()
        allSessions = dao.sessionsBetween(0L, Long.MAX_VALUE)
        weekEvents = dao.blockEventsBetween(weekFrom, weekTo)
        // A week is too thin to show a time-of-day pattern; 4 weeks isn't.
        val now = System.currentTimeMillis()
        urgeEvents = dao.blockEventsBetween(now - URGE_WINDOW_DAYS * 24 * 60 * 60 * 1000L, now + 1)
    }

    val weekMinutes = HistoryStats.weekBucketsMinutes(allSessions, today, zone)
    val totalMinutes = weekMinutes.sum()
    val sessionsThisWeek = HistoryStats.sessionsCount(allSessions, weekFrom, weekTo)
    val completion = HistoryStats.completionRate(allSessions, weekFrom, weekTo)
    val streak = HistoryStats.currentStreakDays(allSessions, today, zone)
    val topBlocked = HistoryStats.topBlockedPackages(weekEvents, weekFrom, weekTo)
    val maxMinutes = (weekMinutes.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

    // Scrolls: with the YOUR URGES section this is taller than a phone screen.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Text("This week", style = headingStyle(28.sp), modifier = Modifier.weight(1f))
            Text(
                "${monday.format(DateTimeFormatter.ofPattern("MMM d"))} – ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("d"))}",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
            )
        }
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))

        Row(verticalAlignment = Alignment.Bottom) {
            Text("${totalMinutes / 60}h ${totalMinutes % 60}m", style = headingStyle(56.sp))
            Spacer(Modifier.width(NowFocusSpace.s2))
            Text("focused", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral700), modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(NowFocusSpace.s4))

        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            weekMinutes.forEach { minutes ->
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height((120 * (minutes.toFloat() / maxMinutes)).dp.coerceAtLeast(2.dp))
                            .background(NowFocusColors.text),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            dayLabels.forEach { d -> Text(d, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule()

        Row(Modifier.fillMaxWidth()) {
            StatCell("Sessions", "$sessionsThisWeek", Modifier.weight(1f))
            StatCell("Completed", "${(completion * 100).toInt()}%", Modifier.weight(1f))
        }
        SectionRule()
        Row(Modifier.fillMaxWidth()) {
            StatCell("Turned away", "${HistoryStats.turnedAwayCount(weekEvents, weekFrom, weekTo)}", Modifier.weight(1f))
            StatCell("Streak", "$streak days", Modifier.weight(1f))
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s4))

        Text("MOST TURNED AWAY", style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s1))
        if (topBlocked.isEmpty()) {
            Text("Nothing yet this week.", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral700))
        } else {
            topBlocked.forEach { (pkg, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabelFor(context, pkg), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    Text("$count", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral700))
                }
                SectionRule()
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))

        Text("YOUR URGES", style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s1))
        val urge = HistoryStats.peakUrge(urgeEvents, zone)
        if (urge == null) {
            Text("Nothing to map yet.", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral700))
        } else {
            // "tried to open a blocked app", not "reached for your phone": attempts are only logged while a session or shield is on.
            Text(
                "You tried to open a blocked app ${if (urge.count == 1) "once" else "${urge.count} times"} between ${hourLabel(urge.hour)} and ${hourLabel(urge.hour + 1)}, usually ${appLabelFor(context, urge.topPackage)}. (last $URGE_WINDOW_DAYS days)",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp),
            )
            Spacer(Modifier.height(NowFocusSpace.s3))
            val byHour = HistoryStats.urgeByHour(urgeEvents, zone)
            val maxByHour = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                byHour.forEachIndexed { hour, n ->
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.6f)
                                .height((60 * (n.toFloat() / maxByHour)).dp.coerceAtLeast(1.dp))
                                .background(if (hour == urge.hour) NowFocusColors.accent else NowFocusColors.text),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 6, 12, 18).forEach { h ->
                    Text(hourLabel(h), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 11.sp, color = NowFocusColors.neutral700))
                }
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            "Counted on your phone. We never see what you browse.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
        )
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = NowFocusSpace.s3)) {
        Text(label, style = kickerStyle(NowFocusColors.neutral700))
        Text(value, style = headingStyle(24.sp))
    }
}

private const val URGE_WINDOW_DAYS = 28

/** 0-24 -> "12 AM" ... "3 PM"; 24 wraps to midnight so an hour's closing edge reads right. */
private fun hourLabel(hour: Int): String = LocalTime.of(hour % 24, 0).format(DateTimeFormatter.ofPattern("h a"))

private fun appLabelFor(context: android.content.Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (e: android.content.pm.PackageManager.NameNotFoundException) {
    packageName
}

/**
 * The mockup this was built from only shows the shield already running - it
 * has no creation flow at all. This one exists in three states depending on
 * [shield]: no shield (setup form), within the grace period (cancel-or-wait),
 * or locked (detail view, no cancel action anywhere).
 */
@Composable
private fun CommitmentScreen(
    shield: CommitmentShield?,
    now: Long,
    elapsedNow: Long,
    bootCount: Int,
    onCreate: (Set<String>, Set<String>, Set<PartialRule>) -> Unit,
    onCancel: () -> Boolean,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)

        // isOver/canCancel are boot-relative (elapsedRealtime + boot count),
        // not wall-clock - see CommitmentShield's kdoc for why that matters.
        when {
            shield == null || shield.isOver(now, elapsedNow, bootCount) -> CommitmentSetup(onCreate = onCreate)
            shield.canCancel(elapsedNow, bootCount) -> CommitmentGrace(shield = shield, elapsedNow = elapsedNow, onCancel = { if (onCancel()) onBack() })
            else -> CommitmentDetail(shield = shield, now = now)
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun CommitmentSetup(onCreate: (Set<String>, Set<String>, Set<PartialRule>) -> Unit) {
    val context = LocalContext.current
    var newDomain by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf(setOf<String>()) }
    var apps by remember { mutableStateOf(listOf<AppRule>()) }
    var partial by remember { mutableStateOf(setOf<PartialRule>()) }
    var pickingApp by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Without this, a shield with sites but no VPN consent would silently
    // enforce nothing for 14 days - the same consent flow SetupScreen uses.
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onCreate(domains, apps.map { it.packageName }.toSet(), partial)
    }

    fun addDomain() {
        val d = DomainValidation.normalize(newDomain) ?: return
        domains = domains + d
        newDomain = ""
    }

    Text("Commitment Shield", style = headingStyle(22.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        "Lock sites and apps for 14 days, no early exit once it starts. You'll get 60 seconds to change your mind first.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))

    Text("SITES", style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    domains.forEach { d ->
        RuleRow(d, "+ subdomains") { domains = domains - d }
    }
    Row(Modifier.padding(top = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newDomain, onValueChange = { newDomain = it },
            label = { Text("e.g. reddit.com") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NowFocusSpace.s2))
        SecondaryButton("Add", onClick = ::addDomain)
    }

    Spacer(Modifier.height(NowFocusSpace.s4))
    Text("APPS", style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    apps.forEach { a ->
        RuleRow(a.label, a.packageName) { apps = apps.filterNot { it.packageName == a.packageName } }
    }
    GhostButton("+ Add application…") { pickingApp = true }

    PartialRulesSection(partial) { partial = it }

    Spacer(Modifier.height(NowFocusSpace.s6))
    if (!confirming) {
        PrimaryButton("Lock for 14 days", enabled = domains.isNotEmpty() || apps.isNotEmpty() || partial.isNotEmpty()) { confirming = true }
    } else {
        // Same two-step as Windows: the 60-second undo after this is a second chance, not the first.
        Text("Are you sure?", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            "${domains.size} site${if (domains.size == 1) "" else "s"} and ${apps.size} app${if (apps.size == 1) "" else "s"} will be blocked for 14 days. You'll have 60 seconds to undo, then it's locked in.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        PrimaryButton("Yes, commit") {
            val consentIntent = if (domains.isNotEmpty()) VpnService.prepare(context) else null
            if (consentIntent != null) vpnConsent.launch(consentIntent) else onCreate(domains, apps.map { it.packageName }.toSet(), partial)
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        SecondaryButton("Back") { confirming = false }
    }

    if (pickingApp) {
        AppPickerDialog(
            exclude = apps.map { it.packageName }.toSet(),
            onPick = { apps = apps + it; pickingApp = false },
            onDismiss = { pickingApp = false },
        )
    }
}

@Composable
private fun CommitmentGrace(shield: CommitmentShield, elapsedNow: Long, onCancel: () -> Unit) {
    val secondsLeft = ((shield.createdElapsedRealtime + CommitmentShield.GRACE_MS - elapsedNow) / 1000 + 1).coerceAtLeast(0)
    Text("Locking it in…", style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        "This is the only chance to undo it. After this, it runs the full 14 days.",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text("$secondsLeft", style = headingStyle(96.sp, color = NowFocusColors.accent))
    Spacer(Modifier.height(NowFocusSpace.s4))
    SecondaryButton("Cancel", onClick = onCancel)
}

@Composable
private fun CommitmentDetail(shield: CommitmentShield, now: Long) {
    val context = LocalContext.current
    val daysLeft = ((shield.endAt - now) / 86_400_000L + 1).coerceAtLeast(0)
    Text("Always blocked · this device", style = kickerStyle())
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$daysLeft", style = headingStyle(72.sp, color = NowFocusColors.accent))
        Text(" days to go", style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), modifier = Modifier.padding(bottom = 12.dp))
    }
    if (shield.domains.isNotEmpty() && !Enforcement.isVpnPermitted(context)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        TagPill("Sites degraded — VPN permission not granted")
    }
    if (shield.packages.isNotEmpty() && !Enforcement.isAccessibilityEnabled(context)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        TagPill("Apps degraded — Accessibility not enabled")
    }
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text("LOCKED SITES", style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    shield.domains.forEach { d ->
        Text(d, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule()
    }
    if (shield.packages.isNotEmpty()) {
        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("LOCKED APPS", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        shield.packages.forEach { pkg ->
            Text(appLabelFor(context, pkg), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
            SectionRule()
        }
    }
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(
        "Turning off Accessibility, revoking the VPN permission, or uninstalling the app still stops this - " +
            "there's no way around that on a normal app. And while this is running, no other VPN app can be active " +
            "at the same time (Android only allows one).",
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
    )
}

@Composable
private fun BedtimeScreen(settings: BedtimeSettings, policies: List<BlockPolicy>, onSave: (BedtimeSettings) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val notificationPolicyOk = remember(settings) {
        context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("Bedtime Wind-Down", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            "Each night from wind-down until wake, your chosen profile is blocked as a locked session you can't end early. Tap a time to change it.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
        )
        Spacer(Modifier.height(NowFocusSpace.s4))

        ToggleRow("On every night", "Applies automatically, no need to start it", settings.enabled, { onSave(settings.copy(enabled = !settings.enabled)) })
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s3))

        Row(Modifier.fillMaxWidth()) {
            TimeBump("Wind-down", settings.windDownMinute, Modifier.weight(1f)) { onSave(settings.copy(windDownMinute = it)) }
            TimeBump("Sleep", settings.sleepMinute, Modifier.weight(1f)) { onSave(settings.copy(sleepMinute = it)) }
            TimeBump("Wake", settings.wakeMinute, Modifier.weight(1f)) { onSave(settings.copy(wakeMinute = it)) }
        }
        Spacer(Modifier.height(NowFocusSpace.s6))

        Text("BLOCK PROFILE", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        if (policies.isEmpty()) {
            Text(
                "No profiles yet — create one in Rules first, or nothing will be blocked at bedtime.",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                modifier = Modifier.padding(vertical = NowFocusSpace.s2),
            )
        } else {
            policies.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSave(settings.copy(policyId = p.id)) }.padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp).background(if (p.id == settings.policyId) NowFocusColors.accent else Color.Transparent))
                    Spacer(Modifier.width(NowFocusSpace.s3))
                    Column {
                        Text(p.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                        Text("${p.domains.size} sites · ${p.apps.size} apps", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
                    }
                }
                SectionRule()
            }
            if (settings.enabled && settings.policyId == null) {
                Text(
                    "Pick a profile above, or nothing will be blocked at bedtime.",
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.accent700),
                    modifier = Modifier.padding(top = NowFocusSpace.s2),
                )
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s6))

        Text("DURING WIND-DOWN", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        ToggleRow(
            "Quiet notifications", "Only priority notifications come through",
            settings.quietNotifications, { onSave(settings.copy(quietNotifications = !settings.quietNotifications)) },
        )
        if (!notificationPolicyOk) {
            GhostButton("Allow in Notification Access settings") {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            ToggleRow(
                "Lock phone at sleep time", "Locks once, at your sleep time",
                settings.lockAtSleep, { onSave(settings.copy(lockAtSleep = !settings.lockAtSleep)) },
            )
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun TimeBump(label: String, minutes: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var editing by remember { mutableStateOf(false) }
    val clock = formatClock(minutes, is24Hour)
    Column(modifier.clickable { editing = true }.padding(vertical = NowFocusSpace.s2)) {
        Text(label, style = kickerStyle(NowFocusColors.neutral700))
        // AM/PM rides small beside the digits so three tiles still fit one row on a narrow phone.
        Text(
            buildAnnotatedString {
                append(clock.substringBeforeLast(' '))
                if (!is24Hour) withStyle(SpanStyle(fontSize = 12.sp)) { append(" " + clock.substringAfterLast(' ')) }
            },
            style = headingStyle(24.sp), maxLines = 1,
        )
    }
    if (editing) {
        TimePickerDialog(label, minutes, is24Hour, onConfirm = { onChange(it); editing = false }, onDismiss = { editing = false })
    }
}

/** Dial by default (two taps: hour, minute); "Keyboard" swaps to typed entry. One save per edit, on Set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, minutes: Int, is24Hour: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = is24Hour)
    var typing by remember { mutableStateOf(false) }
    val colors = TimePickerDefaults.colors(
        clockDialColor = NowFocusColors.neutral200,
        clockDialSelectedContentColor = NowFocusColors.bg,
        clockDialUnselectedContentColor = NowFocusColors.text,
        selectorColor = NowFocusColors.accent,
        periodSelectorBorderColor = NowFocusColors.divider,
        periodSelectorSelectedContainerColor = NowFocusColors.accent100,
        periodSelectorUnselectedContainerColor = Color.Transparent,
        periodSelectorSelectedContentColor = NowFocusColors.accent700,
        periodSelectorUnselectedContentColor = NowFocusColors.text,
        timeSelectorSelectedContainerColor = NowFocusColors.accent100,
        timeSelectorUnselectedContainerColor = NowFocusColors.neutral200,
        timeSelectorSelectedContentColor = NowFocusColors.accent700,
        timeSelectorUnselectedContentColor = NowFocusColors.text,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (typing) TimeInput(state, colors = colors) else TimePicker(state, colors = colors)
                GhostButton(if (typing) "Dial" else "Keyboard") { typing = !typing }
            }
        },
        confirmButton = { PrimaryButton("Set") { onConfirm(state.hour * 60 + state.minute) } },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss) },
    )
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
