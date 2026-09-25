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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(viewModel())
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Session : Screen
    data object Policies : Screen
    data class EditPolicy(val id: String) : Screen
}

@Composable
private fun App(viewModel: SessionViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Session) }
    val policies by viewModel.policies.collectAsStateWithLifecycle()

    when (val s = screen) {
        Screen.Session -> FocusScreen(viewModel, policies, onManagePolicies = { screen = Screen.Policies })
        Screen.Policies -> {
            BackHandler { screen = Screen.Session }
            PolicyListScreen(
                policies = policies,
                onOpen = { screen = Screen.EditPolicy(it) },
                onAdd = { screen = Screen.EditPolicy(viewModel.addPolicy()) },
                onDelete = viewModel::deletePolicy,
                onBack = { screen = Screen.Session },
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

// Same presets as the macOS menu bar picker.
private val DURATIONS = listOf(
    "25 min" to 25, "45 min" to 45, "1 hour" to 60, "1.5 hours" to 90,
    "2 hours" to 120, "3 hours" to 180, "4 hours" to 240,
)

@Composable
private fun FocusScreen(viewModel: SessionViewModel, policies: List<BlockPolicy>, onManagePolicies: () -> Unit) {
    val context = LocalContext.current
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

    var pendingStart by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // Session starts either way; without VPN consent only app blocking runs.
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingStart?.let { (policyId, minutes) -> viewModel.startSession(policyId, minutes) }
        pendingStart = null
        resumeCount++
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("NowFocus", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        val current = session
        if (current != null && SessionEngine.isActive(current, now)) {
            ActiveSessionView(current, now, onStop = { viewModel.cancelSession() })
        } else if (policies.isEmpty()) {
            Text("No policies configured")
        } else {
            StartSessionForm(policies) { policyId, minutes ->
                val consentIntent = VpnService.prepare(context)
                if (consentIntent == null) {
                    viewModel.startSession(policyId, minutes)
                } else {
                    pendingStart = policyId to minutes
                    vpnConsent.launch(consentIntent)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        key(resumeCount) { HealthRow() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onManagePolicies) { Text("Manage Policies") }
    }
}

@Composable
private fun StartSessionForm(policies: List<BlockPolicy>, onStart: (String, Int) -> Unit) {
    var policyId by remember { mutableStateOf(policies.first().id) }
    var minutes by remember { mutableIntStateOf(60) }
    val selected = policies.find { it.id == policyId } ?: policies.first()

    MenuPicker("Policy", selected.name, policies.map { it.name to it.id }) { policyId = it }
    MenuPicker("Duration", DURATIONS.first { it.second == minutes }.first, DURATIONS) { minutes = it }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onStart(selected.id, minutes) }) { Text("Start Focus Session") }
}

@Composable
private fun <T> MenuPicker(label: String, current: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $current") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun ActiveSessionView(session: FocusSession, now: Long, onStop: () -> Unit) {
    val remainingSeconds = (session.endAt - now).coerceAtLeast(0) / 1000
    val hours = remainingSeconds / 3600
    val minutes = remainingSeconds % 3600 / 60
    val seconds = remainingSeconds % 60

    Text("Session Active", color = Color(0xFF2E7D32))
    Text(
        if (hours > 0) String.format("%d:%02d:%02d remaining", hours, minutes, seconds)
        else String.format("%02d:%02d remaining", minutes, seconds)
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onStop) { Text("Stop Session") }
}

/** Android counterpart of the macOS health dot + "needs approval" hint. */
@Composable
private fun HealthRow() {
    val context = LocalContext.current
    val vpnOk = Enforcement.isVpnPermitted(context)
    val appsOk = Enforcement.isAccessibilityEnabled(context)

    Text(
        if (vpnOk) "Website blocking: ready" else "Website blocking: VPN permission asked on start",
        style = MaterialTheme.typography.bodySmall,
        color = if (vpnOk) Color(0xFF2E7D32) else Color(0xFFE65100),
    )
    Text(
        if (appsOk) "App blocking: ready" else "App blocking: off",
        style = MaterialTheme.typography.bodySmall,
        color = if (appsOk) Color(0xFF2E7D32) else Color(0xFFE65100),
    )
    if (!appsOk) {
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("Enable in Accessibility Settings")
        }
        Text(
            "Toggle greyed out? Open App info → ⋮ → Allow restricted settings first.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
