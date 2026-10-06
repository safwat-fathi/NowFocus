package app.getnowfocus.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

class MainActivity : FragmentActivity() {   // FragmentActivity: BiometricPrompt needs it for the Account fingerprint lock

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_UNLOCK = "unlock"
        /** Set just before recreate() when the language changes, so the user lands back on Settings. */
        const val ROUTE_SETTINGS = "settings"
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.localized())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startOnUnlock = intent.getStringExtra(EXTRA_ROUTE) == ROUTE_UNLOCK
        val startOnSettings = intent.getStringExtra(EXTRA_ROUTE) == ROUTE_SETTINGS
        if (startOnSettings) intent.removeExtra(EXTRA_ROUTE)   // once: a later rotation should not come back here
        setContent {
            NowFocusTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = NowFocusColors.bg) {
                    App(viewModel(), startOnUnlock = startOnUnlock, startOnSettings = startOnSettings)
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
    data object Account : Screen
    data object Onboarding : Screen
    data object People : Screen
    data object Goals : Screen
    data object Schedules : Screen
    data object CheatDay : Screen
    data object Limits : Screen
    data object Friction : Screen
    data object Settings : Screen
    data object About : Screen
}

// Same presets as the macOS menu bar picker.
private val DURATIONS = listOf(
    R.string.dur_25 to 25, R.string.dur_45 to 45, R.string.dur_60 to 60, R.string.dur_90 to 90,
    R.string.dur_120 to 120, R.string.dur_180 to 180, R.string.dur_240 to 240,
)

private const val UNLOCK_WAIT_MS = 30_000L

@Composable
private fun App(viewModel: SessionViewModel, startOnUnlock: Boolean = false, startOnSettings: Boolean = false) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(if (startOnUnlock) Screen.Unlock else if (startOnSettings) Screen.Settings else Screen.Home) }
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
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val cheat by viewModel.cheatDay.collectAsStateWithLifecycle()
    val joinRemote by viewModel.joinRemote.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val siteUsage by viewModel.siteUsage.collectAsStateWithLifecycle()
    val frictionApps by viewModel.frictionApps.collectAsStateWithLifecycle()
    // A schedule suggested from Stats ("you try X most at 11 PM"), open in the editor but not saved yet.
    var schedulePrefill by remember { mutableStateOf<Schedule?>(null) }
    val syncStatus by viewModel.sync.status.collectAsStateWithLifecycle()
    // Fingerprint lock on Account only. `accountLock` is null until loaded (renders nothing, never open);
    // `accountUnlocked` lives in memory and is cleared on ON_STOP below, so leaving the app re-locks it.
    val accountLock by viewModel.accountLock.collectAsStateWithLifecycle()
    var accountUnlocked by remember { mutableStateOf(false) }
    var lockMessage by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    // Boot count doesn't change during a session, so it's read once, not ticked.
    val bootCount = remember {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
    }
    // Bumped on resume so the health row re-reads permissions granted in Settings.
    var resumeCount by remember { mutableIntStateOf(0) }
    // Re-read on resume so removing the fingerprint in Settings opens the screen (fail open) straight away.
    val lockAvailable = remember(resumeCount) { fingerprintAvailable(context) }

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
            if (event == Lifecycle.Event.ON_START) viewModel.sync.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.sync.setForeground(false)
                accountUnlocked = false
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeCount++
                viewModel.refreshNow()
                viewModel.sync.syncNow()   // a no-op (no network) unless signed in
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
        screen is Screen.Stats || screen is Screen.Devices || screen is Screen.Active || screen is Screen.Settings

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    running = running,
                    session = session,
                    shield = shield,
                    bedtime = bedtime,
                    cheat = cheat,
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
                        paused = cheat?.isActive(now) == true,
                        // Every mode confirms on the Unlock screen; Normal's is a one-tap "End session now".
                        onEndEarly = { screen = Screen.Unlock },
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
                        onAdd = { mode -> screen = Screen.EditPolicy(viewModel.addPolicy(mode)) },
                        onDelete = viewModel::deletePolicy,
                        onBack = { screen = Screen.Home },
                        onOpenCommitment = { backTo = Screen.Policies; screen = Screen.Commitment },
                        onOpenBedtime = { backTo = Screen.Policies; screen = Screen.Bedtime },
                        peopleCount = people.size,
                        onOpenPeople = { screen = Screen.People },
                        goalsCount = goals.size,
                        onOpenGoals = { screen = Screen.Goals },
                        extraRows = listOf(
                            ProtectionEntry(
                                stringResource(R.string.sched_title),
                                if (schedules.isEmpty()) stringResource(R.string.not_set_up) else stringResource(R.string.sched_on_count, schedules.count { it.enabled }),
                                onClick = { screen = Screen.Schedules },
                            ),
                            ProtectionEntry(
                                stringResource(R.string.limits_title),
                                if (limits.isEmpty()) stringResource(R.string.not_set_up) else pluralStringResource(R.plurals.n_limits, limits.size, limits.size),
                                onClick = { screen = Screen.Limits },
                            ),
                            ProtectionEntry(
                                stringResource(R.string.friction_apps_title),
                                if (frictionApps.isEmpty()) stringResource(R.string.not_set_up) else pluralStringResource(R.plurals.n_apps, frictionApps.size, frictionApps.size),
                                onClick = { screen = Screen.Friction },
                            ),
                            ProtectionEntry(
                                stringResource(R.string.cheat_title),
                                when {
                                    cheat?.isActive(now) == true -> stringResource(R.string.cheat_row_paused)
                                    cheat?.let { now < it.startAt } == true -> stringResource(R.string.cheat_row_planned)
                                    else -> stringResource(R.string.cheat_row_idle)
                                },
                                tag = if (cheat?.isActive(now) == true) stringResource(R.string.on) else null,
                                onClick = { screen = Screen.CheatDay },
                            ),
                        ),
                    )
                }
                is Screen.EditPolicy -> {
                    BackHandler { screen = Screen.Policies }
                    val policy = policies.find { it.id == s.id }
                    if (policy != null) {
                        PolicyEditorScreen(policy, onSave = viewModel::savePolicy, onBack = { screen = Screen.Policies })
                    }
                }
                Screen.Stats -> StatsScreen { app, hour -> schedulePrefill = viewModel.scheduleBlockFor(app, hour); screen = Screen.Schedules }
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
                Screen.Devices -> DevicesScreen(resumeKey = resumeCount, account = syncStatus, onOpenAccount = { screen = Screen.Account })
                Screen.Account -> when {
                    accountLock == null -> Unit
                    accountLocked(accountLock == true, lockAvailable, syncStatus.signedIn, accountUnlocked) ->
                        LockedAccount(
                            message = lockMessage,
                            onUnlock = {
                                lockMessage = null
                                (context as FragmentActivity).askFingerprint(onError = { lockMessage = it }) { accountUnlocked = true }
                            },
                            onBack = { screen = Screen.Devices },
                        )
                    else -> AccountScreen(
                        viewModel.sync, lockOffered = lockAvailable, lockOn = accountLock == true,
                        // Turning it on needs a scan first, so a lock that can't be opened is never saved.
                        onLock = { on ->
                            if (on) (context as FragmentActivity).askFingerprint { viewModel.setAccountLock(true); accountUnlocked = true }
                            else viewModel.setAccountLock(false)
                        },
                        joinRemote = joinRemote,
                        onJoinRemote = viewModel::setJoinRemote,
                        onBack = { screen = Screen.Devices },
                    )
                }
                Screen.Onboarding -> OnboardingScreen(
                    resumeKey = resumeCount,
                    goals = goals,
                    onAddGoal = viewModel::addGoal,
                    onRemoveGoal = viewModel::removeGoal,
                    people = people,
                    onAddPerson = { name, phone -> viewModel.addPerson(name, phone, lastTalkedAt = null) },
                    onRemovePerson = viewModel::removePerson,
                    onSetLastTalked = viewModel::setLastTalked,
                    // Home follows the new session to Active by itself (see the auto-follow effect above).
                    onStartFirstSession = { apps -> viewModel.startFirstSession(apps); screen = Screen.Home },
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
                Screen.Schedules -> SchedulesScreen(
                    schedules = schedules,
                    policies = policies,
                    prefill = schedulePrefill,
                    onSave = { viewModel.saveSchedule(it); schedulePrefill = null },
                    onDelete = viewModel::deleteSchedule,
                    onBack = { schedulePrefill = null; screen = Screen.Policies },
                )
                Screen.Limits -> LimitsScreen(
                    limits = limits,
                    siteUsage = siteUsage,
                    resumeKey = resumeCount,
                    onChange = viewModel::changeLimit,
                    onBack = { screen = Screen.Policies },
                )
                Screen.Friction -> FrictionAppsScreen(
                    apps = frictionApps,
                    onAdd = viewModel::addFrictionApp,
                    onRemove = viewModel::removeFrictionApp,
                    onBack = { screen = Screen.Policies },
                )
                Screen.CheatDay -> CheatDayScreen(
                    cheat = cheat,
                    now = now,
                    onSchedule = viewModel::scheduleCheatDay,
                    onCancel = viewModel::cancelCheatDay,
                    onBack = { screen = Screen.Policies },
                )
                Screen.Settings -> SettingsScreen(onOpenAbout = { screen = Screen.About })
                Screen.About -> AboutScreen(onBack = { screen = Screen.Settings })
            }
        }
        if (tabsVisible) {
            BottomTabBar(
                onFocus = { screen = if (running) Screen.Active else Screen.Home },
                onRules = { screen = Screen.Policies },
                onDevices = { screen = Screen.Devices },
                onStats = { screen = Screen.Stats },
                onSettings = { screen = Screen.Settings },
                selected = screen,
            )
        }
    }
}

@Composable
private fun BottomTabBar(onFocus: () -> Unit, onRules: () -> Unit, onDevices: () -> Unit, onStats: () -> Unit, onSettings: () -> Unit, selected: Screen) {
    val rulesSelected = selected is Screen.Policies || selected is Screen.EditPolicy
    val devicesSelected = selected is Screen.Devices || selected is Screen.Account
    val statsSelected = selected is Screen.Stats
    val settingsSelected = selected is Screen.Settings
    SectionRule(thick = true)
    Row(Modifier.fillMaxWidth().background(NowFocusColors.bg)) {
        TabItem(stringResource(R.string.tab_focus), selected = !rulesSelected && !devicesSelected && !statsSelected && !settingsSelected, modifier = Modifier.weight(1f), onClick = onFocus)
        TabItem(stringResource(R.string.rules_title), selected = rulesSelected, modifier = Modifier.weight(1f), onClick = onRules)
        TabItem(stringResource(R.string.devices_title), selected = devicesSelected, modifier = Modifier.weight(1f), onClick = onDevices)
        TabItem(stringResource(R.string.stats_title), selected = statsSelected, modifier = Modifier.weight(1f), onClick = onStats)
        TabItem(stringResource(R.string.settings_title), selected = settingsSelected, modifier = Modifier.weight(1f), onClick = onSettings)
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
    cheat: CheatDay?,
    now: Long,
    resumeKey: Int,
    onPrimaryCta: () -> Unit,
    onOpenCommitment: () -> Unit,
    onOpenBedtime: () -> Unit,
) {
    val context = LocalContext.current
    val locale = context.appLocale()
    val today = remember(locale) { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale)) }
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
            Text(stringResource(R.string.app_name), style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), modifier = Modifier.weight(1f))
            Text(today, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
        }
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s6))

        // "Cross-device sync" was M1 leftover mockup copy for a feature this
        // build doesn't have (that's Phase 6, not built) - left in by mistake.
        Text(stringResource(if (running) R.string.home_focusing else R.string.home_ready), style = kickerStyle())
        Spacer(Modifier.height(NowFocusSpace.s2))
        val heroTitle = stringResource(if (running) R.string.home_hero_running else R.string.home_hero_idle)
        Text(heroTitle, style = headingStyle(38.sp))
        Spacer(Modifier.height(NowFocusSpace.s6))

        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s3))
        key(resumeKey) { HealthRow() }
        SectionRule()
        val dayFrom = HistoryStats.startOfDayMillis(now, ZoneId.systemDefault())
        val dayTo = dayFrom + 24 * 60 * 60 * 1000L
        val focusedMinutes = (HistoryStats.totalFocusedMillis(todayRows, dayFrom, dayTo) / 60_000).toInt()
        Row(Modifier.fillMaxWidth()) {
            StatCell(stringResource(R.string.home_today), stringResource(R.string.duration_hm, focusedMinutes / 60, focusedMinutes % 60), Modifier.weight(1f))
            StatCell(stringResource(R.string.home_turned_away), "${HistoryStats.turnedAwayCount(todayEvents, dayFrom, dayTo)}", Modifier.weight(1f))
            StatCell(stringResource(R.string.home_sessions), "${HistoryStats.sessionsCount(todayRows, dayFrom, dayTo)}", Modifier.weight(1f))
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s6))

        PrimaryButton(
            text = stringResource(if (running) R.string.home_return else R.string.home_start),
            onClick = onPrimaryCta,
        )

        val showShieldRow = shield != null && shield.endAt > now
        // A cheat day in progress is the one thing that changes what the rest of this screen means.
        if (cheat?.isActive(now) == true) {
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text(stringResource(R.string.home_cheat_paused), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.accent700))
        }
        if (showShieldRow || bedtime.enabled) {
            Spacer(Modifier.height(NowFocusSpace.s6))
            Text(stringResource(R.string.home_always_on), style = kickerStyle(NowFocusColors.neutral700))
            if (showShieldRow) {
                val daysLeft = ((shield!!.endAt - now) / 86_400_000L + 1).coerceAtLeast(1).toInt()
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenCommitment).padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shield_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    TagPill(pluralStringResource(R.plurals.home_days_left, daysLeft, daysLeft))
                }
                SectionRule()
            }
            if (bedtime.enabled) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenBedtime).padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.bedtime_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    TagPill(stringResource(R.string.home_tonight, formatClock(bedtime.windDownMinute, is24Hour, locale)), accent = false)
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
            noteMessage = if (usable) null else context.localized().getString(R.string.setup_rec_failed)
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
            noteMessage = context.localized().getString(R.string.setup_rec_cant_start)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else noteMessage = context.localized().getString(R.string.setup_mic_off)
    }

    // Scrolls: the STRICT voice-note section makes this taller than a small screen.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(R.string.back), onClick = onBack)
        }
        Text(stringResource(R.string.setup_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))

        if (policies.isEmpty()) {
            Text(stringResource(R.string.setup_no_profiles), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
            Spacer(Modifier.height(NowFocusSpace.s2))
            SecondaryButton(stringResource(R.string.setup_manage), onClick = onManagePolicies)
            return@Column
        }
        val selected = policies.find { it.id == policyId } ?: policies.first()

        Text(stringResource(R.string.setup_profile), style = kickerStyle(NowFocusColors.neutral700))
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
                    Text(profileSummary(p), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
                }
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.setup_manage), onClick = onManagePolicies)

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(stringResource(R.string.setup_duration), style = kickerStyle(NowFocusColors.neutral700))
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
                        stringResource(label),
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
        Text(stringResource(R.string.sched_stop_early), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            options = EnforcementMode.entries.map { stringResource(it.labelRes()) to it },
            selected = mode,
            onSelect = { mode = it },
        )
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            stringResource(
                when (mode) {
                    EnforcementMode.NORMAL -> R.string.setup_mode_normal
                    EnforcementMode.STRICT -> R.string.setup_mode_strict
                    EnforcementMode.LOCKED -> R.string.setup_mode_locked
                },
            ),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral800),
        )

        if (mode == EnforcementMode.STRICT) {
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text(stringResource(R.string.setup_voice), style = kickerStyle(NowFocusColors.neutral700))
            Spacer(Modifier.height(NowFocusSpace.s1))
            Text(
                stringResource(R.string.setup_voice_intro),
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral800),
            )
            Spacer(Modifier.height(NowFocusSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    SecondaryButton(stringResource(R.string.setup_stop, recordedSeconds)) { recorder.stop() }
                } else {
                    SecondaryButton(stringResource(if (hasNote) R.string.setup_rerecord else R.string.setup_record)) {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (hasNote) {
                        Spacer(Modifier.width(NowFocusSpace.s2))
                        TagPill(stringResource(R.string.setup_recorded), accent = false)
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
        if (!selected.enforcesHere) {
            Text(
                stringResource(R.string.setup_allow_empty),
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.accent700),
            )
        } else PrimaryButton(stringResource(R.string.setup_start, stringResource(DURATIONS.first { it.second == minutes }.first))) {
            // Finalize an in-progress note first so startSession adopts a complete file.
            if (recording) recorder.stop()
            // A whitelist filters no sites, so there is nothing for the VPN to do and no consent to ask for.
            val consentIntent = if (selected.mode == PolicyMode.ALLOWLIST) null else VpnService.prepare(context)
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
private fun ActiveScreen(session: FocusSession, now: Long, paused: Boolean, onEndEarly: () -> Unit) {
    val remainingSeconds = (session.endAt - now).coerceAtLeast(0) / 1000
    val hours = remainingSeconds / 3600
    val minutes = remainingSeconds % 3600 / 60
    val seconds = remainingSeconds % 60
    val remaining = if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) else String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
    val progress = ((now - session.startAt).toFloat() / (session.endAt - session.startAt).toFloat()).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().background(NowFocusColors.accent).padding(NowFocusSpace.s6)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.active_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.text), modifier = Modifier.weight(1f))
            Text(
                stringResource(session.enforcementMode.labelRes()).uppercase(),
                style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NowFocusColors.text, letterSpacing = 1.sp),
            )
        }
        Spacer(Modifier.height(NowFocusSpace.s6))
        Text(stringResource(R.string.active_keep_going), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.text))
        Text(remaining, style = headingStyle(72.sp, color = NowFocusColors.bg))
        Spacer(Modifier.height(NowFocusSpace.s3))
        Box(Modifier.fillMaxWidth().height(6.dp).background(NowFocusColors.text.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(NowFocusColors.bg))
        }
        if (paused) {
            Spacer(Modifier.height(NowFocusSpace.s3))
            Text(stringResource(R.string.active_cheat), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.text))
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton(
            stringResource(if (session.enforcementMode == EnforcementMode.LOCKED) R.string.active_locked else R.string.active_end_early),
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
        GhostButton(stringResource(R.string.back_to_focus), onClick = onBack)
        Text(stringResource(R.string.unlock_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))

        when (session.enforcementMode) {
            EnforcementMode.NORMAL -> {
                Text(
                    stringResource(R.string.unlock_end_now_q, DurationFormat.remaining((session.endAt - now).coerceAtLeast(0)).text()),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 16.sp),
                )
                Spacer(Modifier.height(NowFocusSpace.s4))
                PrimaryButton(stringResource(R.string.unlock_end_now)) { if (onCancel(false, false)) onCancelled() }
                Spacer(Modifier.height(NowFocusSpace.s2))
                GhostButton(stringResource(R.string.unlock_keep_going), onClick = onBack)
            }
            EnforcementMode.LOCKED -> {
                Text(stringResource(R.string.unlock_locked_title), style = headingStyle(28.sp))
                Spacer(Modifier.height(NowFocusSpace.s2))
                Text(
                    stringResource(R.string.unlock_locked_body),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                )
                Spacer(Modifier.height(NowFocusSpace.s4))
                PrimaryButton(stringResource(R.string.blocked_back), onClick = onBack)
            }
            EnforcementMode.STRICT -> {
                var typed by remember { mutableStateOf("") }
                var waitEndAt by remember { mutableStateOf<Long?>(null) }
                val waiting = waitEndAt != null
                val waitRemainingMs = waitEndAt?.let { (it - now).coerceAtLeast(0) } ?: 0L
                val waitDone = waiting && waitRemainingMs == 0L
                val unlockSentence = stringResource(R.string.unlock_sentence)
                val matched = typed.trim() == unlockSentence

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
                        stringResource(R.string.unlock_type_it),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                    )
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    Text(stringResource(R.string.unlock_quoted, unlockSentence), style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp))
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    NowFocusTextField(value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(NowFocusSpace.s4))
                    PrimaryButton(stringResource(R.string.unlock_start_pause), enabled = matched) { waitEndAt = System.currentTimeMillis() + UNLOCK_WAIT_MS }
                } else {
                    Text(
                        stringResource(if (player == null) R.string.unlock_breath else R.string.unlock_breath_note),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
                    )
                    if (goal != null) {
                        Spacer(Modifier.height(NowFocusSpace.s3))
                        Text(stringResource(R.string.unlock_remember), style = kickerStyle(NowFocusColors.neutral700))
                        Text(goal, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    }
                    Spacer(Modifier.height(NowFocusSpace.s3))
                    Text("${waitRemainingMs / 1000 + if (waitRemainingMs % 1000 > 0) 1 else 0}", style = headingStyle(96.sp, color = NowFocusColors.accent))
                    Spacer(Modifier.height(NowFocusSpace.s4))
                    if (player != null) {
                        SecondaryButton(
                            stringResource(
                                when {
                                    playing -> R.string.unlock_playing
                                    listened -> R.string.unlock_play_again
                                    else -> R.string.unlock_play
                                },
                            ),
                        ) {
                            if (!playing) {
                                playing = true
                                player.play()
                            }
                        }
                        Spacer(Modifier.height(NowFocusSpace.s2))
                    }
                    PrimaryButton(
                        stringResource(
                            when {
                                canEnd -> R.string.unlock_end_now
                                waitDone -> R.string.unlock_hear_first
                                else -> R.string.unlock_waiting
                            },
                        ),
                        enabled = canEnd,
                    ) { if (onCancel(true, listened)) onCancelled() }
                }
                Spacer(Modifier.height(NowFocusSpace.s2))
                SecondaryButton(stringResource(R.string.unlock_stay), onClick = onBack)
            }
        }
    }
}

@Composable
private fun StatsScreen(onScheduleBlock: (AppRule, Int) -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val locale = context.appLocale()
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
    val score = HistoryStats.focusScore(allSessions, weekFrom, weekTo, zone)
    val topBlocked = HistoryStats.topBlockedPackages(weekEvents, weekFrom, weekTo)
    val maxMinutes = (weekMinutes.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val dayLabels = listOf(R.string.dayl_mon, R.string.dayl_tue, R.string.dayl_wed, R.string.dayl_thu, R.string.dayl_fri, R.string.dayl_sat, R.string.dayl_sun).map { stringResource(it) }

    // Scrolls: with the YOUR URGES section this is taller than a phone screen.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_this_week), style = headingStyle(28.sp), modifier = Modifier.weight(1f))
            Text(
                "${monday.format(DateTimeFormatter.ofPattern("MMM d", locale))} – ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("d", locale))}",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
            )
        }
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.duration_hm, (totalMinutes / 60).toInt(), (totalMinutes % 60).toInt()), style = headingStyle(56.sp))
            Spacer(Modifier.width(NowFocusSpace.s2))
            Text(stringResource(R.string.stats_focused), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral700), modifier = Modifier.padding(bottom = 8.dp))
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
            StatCell(stringResource(R.string.home_sessions), "$sessionsThisWeek", Modifier.weight(1f))
            StatCell(stringResource(R.string.stats_completed), "${(completion * 100).toInt()}%", Modifier.weight(1f))
        }
        SectionRule()
        Row(Modifier.fillMaxWidth()) {
            StatCell(stringResource(R.string.home_turned_away), "${HistoryStats.turnedAwayCount(weekEvents, weekFrom, weekTo)}", Modifier.weight(1f))
            StatCell(stringResource(R.string.stats_streak), pluralStringResource(R.plurals.since_days, streak, streak), Modifier.weight(1f))
        }
        SectionRule()
        Row(Modifier.fillMaxWidth()) {
            StatCell(stringResource(R.string.stats_score), score?.toString() ?: "-", Modifier.weight(1f))
            Box(Modifier.weight(1f).padding(vertical = NowFocusSpace.s3), contentAlignment = Alignment.CenterEnd) {
                SecondaryButton(stringResource(R.string.stats_share)) {
                    val text = HistoryStats.weekSummaryText(allSessions, weekEvents, weekFrom, weekTo, today, zone).resolve(context)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                }
            }
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s4))

        Text(stringResource(R.string.stats_most), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s1))
        if (topBlocked.isEmpty()) {
            Text(stringResource(R.string.stats_nothing), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral700))
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

        Text(stringResource(R.string.stats_urges), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s1))
        val urge = HistoryStats.peakUrge(urgeEvents, zone)
        if (urge == null) {
            Text(stringResource(R.string.stats_nothing_map), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral700))
        } else {
            // "tried to open a blocked app", not "reached for your phone": attempts are only logged while a session or shield is on.
            Text(
                pluralStringResource(
                    R.plurals.stats_urge, urge.count, urge.count, hourLabel(locale, urge.hour), hourLabel(locale, urge.hour + 1), appLabelFor(context, urge.topPackage), URGE_WINDOW_DAYS,
                ),
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
                    Text(hourLabel(locale, h), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 11.sp, color = NowFocusColors.neutral700))
                }
            }
            // Each app's own busiest hour, with a way to act on it.
            HistoryStats.appUrges(urgeEvents, zone).forEach { u ->
                val label = appLabelFor(context, u.packageName)
                Spacer(Modifier.height(NowFocusSpace.s3))
                Text(stringResource(R.string.stats_urge_app, label, hourLabel(locale, u.hour), u.hourCount, u.total), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp))
                GhostButton(stringResource(R.string.stats_block_at, label, hourLabel(locale, u.hour))) { onScheduleBlock(AppRule(u.packageName, label), u.hour) }
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(
            stringResource(R.string.stats_counted),
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
private fun hourLabel(locale: java.util.Locale, hour: Int): String = LocalTime.of(hour % 24, 0).format(DateTimeFormatter.ofPattern("h a", locale))

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
        GhostButton(stringResource(R.string.back), onClick = onBack)

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

    Text(stringResource(R.string.shield_title), style = headingStyle(22.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.shield_intro),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))

    Text(stringResource(R.string.shield_sites), style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    domains.forEach { d ->
        RuleRow(d, stringResource(R.string.policy_subdomains)) { domains = domains - d }
    }
    Row(Modifier.padding(top = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
        NowFocusTextField(
            value = newDomain, onValueChange = { newDomain = it },
            placeholder = stringResource(R.string.shield_site_hint), singleLine = true, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NowFocusSpace.s2))
        SecondaryButton(stringResource(R.string.add), onClick = ::addDomain)
    }

    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(stringResource(R.string.shield_apps), style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    apps.forEach { a ->
        RuleRow(a.label, a.packageName) { apps = apps.filterNot { it.packageName == a.packageName } }
    }
    GhostButton(stringResource(R.string.policy_add_app)) { pickingApp = true }

    PartialRulesSection(partial) { partial = it }

    Spacer(Modifier.height(NowFocusSpace.s6))
    if (!confirming) {
        PrimaryButton(stringResource(R.string.shield_lock), enabled = domains.isNotEmpty() || apps.isNotEmpty() || partial.isNotEmpty()) { confirming = true }
    } else {
        // Same two-step as Windows: the 60-second undo after this is a second chance, not the first.
        Text(stringResource(R.string.shield_sure), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            stringResource(R.string.shield_confirm, pluralStringResource(R.plurals.n_sites, domains.size, domains.size), pluralStringResource(R.plurals.n_apps, apps.size, apps.size)),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        PrimaryButton(stringResource(R.string.shield_commit)) {
            val consentIntent = if (domains.isNotEmpty()) VpnService.prepare(context) else null
            if (consentIntent != null) vpnConsent.launch(consentIntent) else onCreate(domains, apps.map { it.packageName }.toSet(), partial)
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        SecondaryButton(stringResource(R.string.back_plain)) { confirming = false }
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
    Text(stringResource(R.string.shield_locking), style = headingStyle(28.sp))
    Spacer(Modifier.height(NowFocusSpace.s2))
    Text(
        stringResource(R.string.shield_last_chance),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.neutral800),
    )
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text("$secondsLeft", style = headingStyle(96.sp, color = NowFocusColors.accent))
    Spacer(Modifier.height(NowFocusSpace.s4))
    SecondaryButton(stringResource(R.string.cancel), onClick = onCancel)
}

@Composable
private fun CommitmentDetail(shield: CommitmentShield, now: Long) {
    val context = LocalContext.current
    val daysLeft = ((shield.endAt - now) / 86_400_000L + 1).coerceAtLeast(0).toInt()
    Text(stringResource(R.string.shield_detail_kicker), style = kickerStyle())
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$daysLeft", style = headingStyle(72.sp, color = NowFocusColors.accent))
        Text(" " + pluralStringResource(R.plurals.shield_days_to_go, daysLeft), style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp), modifier = Modifier.padding(bottom = 12.dp))
    }
    if (shield.domains.isNotEmpty() && !Enforcement.isVpnPermitted(context)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        TagPill(stringResource(R.string.shield_sites_degraded))
    }
    if (shield.packages.isNotEmpty() && !Enforcement.isAccessibilityEnabled(context)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        TagPill(stringResource(R.string.shield_apps_degraded))
    }
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(stringResource(R.string.shield_locked_sites), style = kickerStyle(NowFocusColors.neutral700))
    SectionRule()
    shield.domains.forEach { d ->
        Text(d, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule()
    }
    if (shield.packages.isNotEmpty()) {
        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(stringResource(R.string.shield_locked_apps), style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        shield.packages.forEach { pkg ->
            Text(appLabelFor(context, pkg), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
            SectionRule()
        }
    }
    Spacer(Modifier.height(NowFocusSpace.s4))
    Text(
        stringResource(R.string.shield_caveat),
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
    val onDark = NowFocusColors.bg
    val muted = NowFocusColors.neutral400
    val rule = NowFocusColors.neutral700

    // Dark surface, like the design: the evening screen should not be the brightest thing in the room.
    Column(Modifier.fillMaxSize().background(NowFocusColors.text).verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = context.getString(R.string.back) },
                contentAlignment = Alignment.Center,
            ) { PathIcon(listOf("m12 19-7-7 7-7", "M19 12H5"), 22.dp, onDark, mirrorInRtl = true) }
            Text(stringResource(R.string.bedtime_title), style = headingStyle(20.sp, onDark), modifier = Modifier.weight(1f))
            PathIcon(listOf("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"), 20.dp, onDark)
        }
        SectionRule(thick = true, color = NowFocusColors.neutral600)
        Spacer(Modifier.height(NowFocusSpace.s6))
        Text(stringResource(R.string.bedtime_hero), style = headingStyle(32.sp, onDark).copy(lineHeight = 34.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            stringResource(R.string.bedtime_intro),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral300),
        )
        Spacer(Modifier.height(NowFocusSpace.s4))

        ToggleRow(stringResource(R.string.bedtime_on), stringResource(R.string.bedtime_on_sub), settings.enabled, { onSave(settings.copy(enabled = !settings.enabled)) }, dark = true)
        SectionRule(color = rule)
        Spacer(Modifier.height(NowFocusSpace.s3))

        SectionRule(thick = true, color = onDark)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            TimeBump(stringResource(R.string.bedtime_winddown), settings.windDownMinute, Modifier.weight(1f), dark = true) { onSave(settings.copy(windDownMinute = it)) }
            VerticalRule(NowFocusColors.neutral600)
            TimeBump(stringResource(R.string.bedtime_sleep), settings.sleepMinute, Modifier.weight(1f), dark = true, accent = true) { onSave(settings.copy(sleepMinute = it)) }
            VerticalRule(NowFocusColors.neutral600)
            TimeBump(stringResource(R.string.bedtime_wake), settings.wakeMinute, Modifier.weight(1f), dark = true) { onSave(settings.copy(wakeMinute = it)) }
        }
        SectionRule(thick = true, color = onDark)
        Spacer(Modifier.height(NowFocusSpace.s6))

        Text(stringResource(R.string.sched_block_profile), style = kickerStyle(muted))
        SectionRule(color = rule)
        if (policies.isEmpty()) {
            Text(
                stringResource(R.string.bedtime_no_profiles),
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = muted),
                modifier = Modifier.padding(vertical = NowFocusSpace.s2),
            )
        } else {
            policies.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSave(settings.copy(policyId = p.id)) }.padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(18.dp).border(2.dp, if (p.id == settings.policyId) NowFocusColors.accent else NowFocusColors.neutral500)
                            .background(if (p.id == settings.policyId) NowFocusColors.accent else Color.Transparent),
                    )
                    Spacer(Modifier.width(NowFocusSpace.s3))
                    Column {
                        Text(p.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = onDark))
                        Text(profileSummary(p), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = muted))
                    }
                }
                SectionRule(color = rule)
            }
            if (settings.enabled && settings.policyId == null) {
                Text(
                    stringResource(R.string.bedtime_pick),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.accent400),
                    modifier = Modifier.padding(top = NowFocusSpace.s2),
                )
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s6))

        Text(stringResource(R.string.bedtime_during), style = kickerStyle(muted))
        SectionRule(color = rule)
        if (canWriteSecureSettings(context)) {
            ToggleRow(
                stringResource(R.string.bedtime_grey), stringResource(R.string.bedtime_grey_sub),
                settings.greyscale, { onSave(settings.copy(greyscale = !settings.greyscale)) }, dark = true,
            )
        } else {
            // Android won't let an ordinary app switch the screen to greyscale; its own Bedtime mode does it on a schedule.
            Column(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3)) {
                Text(stringResource(R.string.bedtime_grey), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = onDark))
                Text(stringResource(R.string.bedtime_grey_system), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = muted))
                GhostButton(stringResource(R.string.bedtime_grey_open)) {
                    val pm = context.packageManager
                    val intent = pm.getLaunchIntentForPackage("com.google.android.apps.wellbeing")
                        ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        ToggleRow(
            stringResource(R.string.bedtime_quiet), stringResource(R.string.bedtime_quiet_sub),
            settings.quietNotifications, { onSave(settings.copy(quietNotifications = !settings.quietNotifications)) }, dark = true,
        )
        if (!notificationPolicyOk) {
            GhostButton(stringResource(R.string.bedtime_allow_access)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            ToggleRow(
                stringResource(R.string.bedtime_lock), stringResource(R.string.bedtime_lock_sub),
                settings.lockAtSleep, { onSave(settings.copy(lockAtSleep = !settings.lockAtSleep)) }, dark = true,
            )
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun VerticalRule(color: Color) {
    Box(Modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Draws 24x24 SVG path data (the design's icons) as a 2-unit stroke. Back arrows mirror in RTL. */
@Composable
private fun PathIcon(paths: List<String>, size: androidx.compose.ui.unit.Dp, color: Color, mirrorInRtl: Boolean = false) {
    val parsed = remember(paths) { paths.map { androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath() } }
    val flip = mirrorInRtl && androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    androidx.compose.foundation.Canvas(Modifier.size(size).graphicsLayer { scaleX = if (flip) -1f else 1f }) {
        val k = this.size.width / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            parsed.forEach { drawPath(it, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)) }
        }
    }
}

@Composable
internal fun TimeBump(label: String, minutes: Int, modifier: Modifier = Modifier, dark: Boolean = false, accent: Boolean = false, onChange: (Int) -> Unit) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var editing by remember { mutableStateOf(false) }
    val clock = formatClock(minutes, is24Hour, LocalContext.current.appLocale())
    // On the dark strip the tiles are padded in from each rule, like the design's grid cells.
    Column(modifier.clickable { editing = true }.padding(vertical = if (dark) NowFocusSpace.s3 else NowFocusSpace.s2, horizontal = if (dark) NowFocusSpace.s2 else 0.dp)) {
        Text(label, style = kickerStyle(if (dark) NowFocusColors.neutral400 else NowFocusColors.neutral700))
        // AM/PM rides small beside the digits so three tiles still fit one row on a narrow phone.
        Text(
            buildAnnotatedString {
                append(clock.substringBeforeLast(' '))
                if (!is24Hour) withStyle(SpanStyle(fontSize = 12.sp)) { append(" " + clock.substringAfterLast(' ')) }
            },
            style = headingStyle(if (dark) 26.sp else 24.sp, if (!dark) NowFocusColors.text else if (accent) NowFocusColors.accent400 else NowFocusColors.bg), maxLines = 1,
        )
    }
    if (editing) {
        TimePickerDialog(label, minutes, is24Hour, onConfirm = { onChange(it); editing = false }, onDismiss = { editing = false })
    }
}

/** Typed hour/minute in the design-system fields (no Material clock dial). One save per edit, on Set. */
@Composable
private fun TimePickerDialog(title: String, minutes: Int, is24Hour: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var hour by remember { mutableStateOf((if (is24Hour) minutes / 60 else (minutes / 60 + 11) % 12 + 1).toString()) }
    var minute by remember { mutableStateOf(String.format(java.util.Locale.ROOT, "%02d", minutes % 60)) }
    var pm by remember { mutableStateOf(minutes >= 12 * 60) }
    val result = parseClock(hour.toIntOrNull() ?: -1, minute.toIntOrNull() ?: -1, pm, is24Hour)
    val digits = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
    val strictDns = Enforcement.strictPrivateDns(context) != null
        onDismissRequest = onDismiss,
        title = { Text(title, style = headingStyle(20.sp)) },
        text = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NowFocusSpace.s2)) {
                NowFocusTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = stringResource(R.string.time_hour), singleLine = true, keyboardOptions = digits)
                Text(":", style = headingStyle(24.sp), modifier = Modifier.padding(bottom = NowFocusSpace.s3))
                NowFocusTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = stringResource(R.string.time_minute), singleLine = true, keyboardOptions = digits)
                if (!is24Hour) SegmentedControl(listOf(stringResource(R.string.time_am) to false, stringResource(R.string.time_pm) to true), pm, { pm = it }, Modifier.weight(1.2f))
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.time_set), enabled = result != null) { result?.let(onConfirm) } },
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

/** Android counterpart of the macOS health dot + "needs approval" hint. */
@Composable
private fun HealthRow() {
    val context = LocalContext.current
    val vpnOk = Enforcement.isVpnPermitted(context)
    val appsOk = Enforcement.isAccessibilityEnabled(context)

    Text(
        stringResource(when {
            strictDns -> R.string.health_web_private_dns
            vpnOk -> R.string.health_web_ok
            else -> R.string.health_web_ask
        }),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = if (vpnOk && !strictDns) NowFocusColors.text else NowFocusColors.accent700),
    )
    Text(
        stringResource(if (appsOk) R.string.health_apps_ok else R.string.health_apps_off),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = if (appsOk) NowFocusColors.text else NowFocusColors.accent700),
    )
    if (!appsOk) {
        Spacer(Modifier.height(NowFocusSpace.s1))
        GhostButton(stringResource(R.string.health_enable)) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Text(
            stringResource(R.string.restricted_settings_hint),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
        )
    }
}
