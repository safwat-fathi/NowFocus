package app.getnowfocus.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getnowfocus.android.sync.DeviceInfo
import app.getnowfocus.android.sync.SyncController
import app.getnowfocus.android.sync.SyncStatus
import kotlinx.coroutines.launch

/** Optional account: sign in to keep profiles and bedtime settings in sync. Without one, nothing here is ever contacted. */
@Composable
fun AccountScreen(
    sync: SyncController,
    lockOffered: Boolean,
    lockOn: Boolean,
    onLock: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val status by sync.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Devices", onClick = onBack)
        Text("Account", style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))
        when {
            !status.loaded -> Unit
            status.signedIn -> SignedIn(sync, status, lockOffered || lockOn, lockOn, onLock)
            else -> SignedOut(sync, status)
        }
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700)

@Composable
private fun SignedOut(sync: SyncController, status: SyncStatus) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(create: Boolean) {
        credentialsProblem(email.trim(), password)?.let { error = it; return }
        busy = true; error = null
        scope.launch {
            error = sync.signIn(email, password, create)
            busy = false
            if (error == null) password = ""
        }
    }

    Text(
        "Optional. Sign in to keep your profiles and bedtime settings in sync across your devices. " +
            "People, goals, voice notes and stats stay on this phone, and NowFocus keeps blocking from this phone's own copy even when you're offline.",
        style = body,
    )
    status.problem?.let { Text(it, style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
    NowFocusTextField(
        value = email, onValueChange = { email = it }, label = "Email", singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s4),
    )
    NowFocusTextField(
        value = password, onValueChange = { password = it }, label = "Password (8+ characters)", singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s2),
    )
    error?.let { Text(it, style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
    Spacer(Modifier.height(NowFocusSpace.s4))
    PrimaryButton(if (busy) "Please wait…" else "Sign in", enabled = !busy, modifier = Modifier.fillMaxWidth()) { submit(create = false) }
    Spacer(Modifier.height(NowFocusSpace.s2))
    SecondaryButton(if (busy) "Please wait…" else "Create account", modifier = Modifier.fillMaxWidth()) { if (!busy) submit(create = true) }
}

/** Why the form can't be submitted yet, or null. Shown instead of a button that silently does nothing. */
internal fun credentialsProblem(email: String, password: String): String? = when {
    !email.contains("@") -> "Enter a valid email address."
    password.length < 8 -> "Password must be at least 8 characters."
    else -> null
}

@Composable
private fun SignedIn(sync: SyncController, status: SyncStatus, showLock: Boolean, lockOn: Boolean, onLock: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload, status.lastSyncedAt) { devices = runCatching { sync.devices() }.getOrNull() }

    Text("SIGNED IN", style = kickerStyle())
    Text(status.email.orEmpty(), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp), modifier = Modifier.padding(top = NowFocusSpace.s1))
    Text(
        when {
            status.syncing -> "Syncing…"
            status.problem != null -> status.problem
            status.lastSyncedAt != null -> "Synced ${ago(status.lastSyncedAt)}"
            else -> "Waiting to sync"
        } ?: "",
        style = body.copy(color = if (status.problem != null) NowFocusColors.accent700 else NowFocusColors.neutral700),
        modifier = Modifier.padding(top = NowFocusSpace.s1),
    )
    if (status.rejected > 0) {
        Text(
            "${status.rejected} change${if (status.rejected == 1) "" else "s"} couldn't be synced. They stay on this phone; editing them again retries.",
            style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s1),
        )
    }
    Spacer(Modifier.height(NowFocusSpace.s3))
    SecondaryButton("Sync now", modifier = Modifier.fillMaxWidth()) { sync.syncNow() }
    if (showLock) {
        Spacer(Modifier.height(NowFocusSpace.s3))
        ToggleRow(
            "Require fingerprint to open Account", "Only this screen. Focus sessions and blocking are never locked.",
            lockOn, { onLock(!lockOn) },
        )
    }

    Spacer(Modifier.height(NowFocusSpace.s6))
    Text("DEVICES ON THIS ACCOUNT", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(bottom = NowFocusSpace.s1))
    SectionRule(thick = true)
    val live = devices.orEmpty().filter { !it.revoked }
    if (devices == null) Text("Loading…", style = body, modifier = Modifier.padding(vertical = NowFocusSpace.s3))
    live.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                Text(d.platform.replaceFirstChar { it.uppercase() }, style = body)
            }
            if (d.current) TagPill("This phone", accent = false)
            else GhostButton("Remove") { scope.launch { message = sync.revokeDevice(d.id); reload++ } }
        }
        SectionRule()
    }
    message?.let { Text(it, style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }

    Spacer(Modifier.height(NowFocusSpace.s6))
    Text("Signing out keeps your profiles, bedtime and Commitment Shield on this phone. Blocking continues as before.", style = body)
    Spacer(Modifier.height(NowFocusSpace.s2))
    SecondaryButton("Sign out", modifier = Modifier.fillMaxWidth()) { scope.launch { sync.signOut() } }
    Spacer(Modifier.height(NowFocusSpace.s2))
    GhostButton("Delete account…") { confirmDelete = true }

    if (confirmDelete) DeleteAccountDialog(sync, onDone = { confirmDelete = false })
}

@Composable
private fun DeleteAccountDialog(sync: SyncController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Delete account?", style = headingStyle(20.sp)) },
        text = {
            Column {
                Text("This permanently deletes your account and everything synced to it, on every device. Nothing on this phone is deleted, and blocking is unaffected.", style = body)
                NowFocusTextField(
                    value = password, onValueChange = { password = it }, label = "Confirm with your password", singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3),
                )
                error?.let { Text(it, style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
            }
        },
        confirmButton = {
            PrimaryButton(if (busy) "Deleting…" else "Delete", enabled = !busy && password.isNotEmpty()) {
                busy = true; error = null
                scope.launch { error = sync.deleteAccount(password); busy = false; if (error == null) onDone() }
            }
        },
        dismissButton = { GhostButton("Cancel") { if (!busy) onDone() } },
    )
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
