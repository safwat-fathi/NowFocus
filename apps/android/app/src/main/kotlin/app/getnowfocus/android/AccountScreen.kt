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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/** Optional account: sign in to keep profiles and bedtime settings in sync. Without one, nothing here is ever contacted. */
@Composable
fun AccountScreen(
    sync: SyncController,
    lockOffered: Boolean,
    lockOn: Boolean,
    onLock: (Boolean) -> Unit,
    joinRemote: Boolean,
    onJoinRemote: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val status by sync.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back_devices), onClick = onBack)
        Text(stringResource(R.string.account_title), style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))
        when {
            !status.loaded -> Unit
            status.signedIn -> SignedIn(sync, status, lockOffered || lockOn, lockOn, onLock, joinRemote, onJoinRemote)
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
    var error by remember { mutableStateOf<UiText?>(null) }

    fun submit(create: Boolean) {
        credentialsProblem(email.trim(), password)?.let { error = it; return }
        busy = true; error = null
        scope.launch {
            error = sync.signIn(email, password, create)
            busy = false
            if (error == null) password = ""
        }
    }

    Text(stringResource(R.string.account_intro), style = body)
    status.problem?.let { Text(it.text(), style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
    NowFocusTextField(
        value = email, onValueChange = { email = it }, label = stringResource(R.string.account_email), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s4),
    )
    NowFocusTextField(
        value = password, onValueChange = { password = it }, label = stringResource(R.string.account_password), singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s2),
    )
    error?.let { Text(it.text(), style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
    Spacer(Modifier.height(NowFocusSpace.s4))
    PrimaryButton(stringResource(if (busy) R.string.please_wait else R.string.account_sign_in), enabled = !busy, modifier = Modifier.fillMaxWidth()) { submit(create = false) }
    Spacer(Modifier.height(NowFocusSpace.s2))
    SecondaryButton(stringResource(if (busy) R.string.please_wait else R.string.account_create), modifier = Modifier.fillMaxWidth()) { if (!busy) submit(create = true) }
}

/** Why the form can't be submitted yet, or null. Shown instead of a button that silently does nothing. */
internal fun credentialsProblem(email: String, password: String): UiText? = when {
    !email.contains("@") -> uiText(R.string.cred_bad_email)
    password.length < 8 -> uiText(R.string.cred_short_password)
    else -> null
}

@Composable
private fun SignedIn(
    sync: SyncController, status: SyncStatus, showLock: Boolean, lockOn: Boolean, onLock: (Boolean) -> Unit,
    joinRemote: Boolean, onJoinRemote: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var message by remember { mutableStateOf<UiText?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload, status.lastSyncedAt) { devices = runCatching { sync.devices() }.getOrNull() }

    Text(stringResource(R.string.account_signed_in), style = kickerStyle())
    Text(status.email.orEmpty(), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp), modifier = Modifier.padding(top = NowFocusSpace.s1))
    Text(
        when {
            status.syncing -> stringResource(R.string.account_syncing)
            status.problem != null -> status.problem.text()
            status.lastSyncedAt != null -> stringResource(R.string.account_synced, ago(status.lastSyncedAt).text())
            else -> stringResource(R.string.account_waiting)
        },
        style = body.copy(color = if (status.problem != null) NowFocusColors.accent700 else NowFocusColors.neutral700),
        modifier = Modifier.padding(top = NowFocusSpace.s1),
    )
    if (status.rejected > 0) {
        Text(
            pluralStringResource(R.plurals.account_rejected, status.rejected, status.rejected),
            style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s1),
        )
    }
    Spacer(Modifier.height(NowFocusSpace.s3))
    SecondaryButton(stringResource(R.string.account_sync_now), modifier = Modifier.fillMaxWidth()) { sync.syncNow() }
    Spacer(Modifier.height(NowFocusSpace.s3))
    ToggleRow(
        stringResource(R.string.account_join_title),
        stringResource(R.string.account_join_sub),
        joinRemote, { onJoinRemote(!joinRemote) },
    )
    if (showLock) {
        Spacer(Modifier.height(NowFocusSpace.s3))
        ToggleRow(
            stringResource(R.string.account_lock_title), stringResource(R.string.account_lock_sub),
            lockOn, { onLock(!lockOn) },
        )
    }

    Spacer(Modifier.height(NowFocusSpace.s6))
    Text(stringResource(R.string.account_devices_kicker), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(bottom = NowFocusSpace.s1))
    SectionRule(thick = true)
    val live = devices.orEmpty().filter { !it.revoked }
    if (devices == null) Text(stringResource(R.string.loading), style = body, modifier = Modifier.padding(vertical = NowFocusSpace.s3))
    live.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                Text(d.platform.replaceFirstChar { it.uppercase() }, style = body)
            }
            if (d.current) TagPill(stringResource(R.string.account_this_phone), accent = false)
            else GhostButton(stringResource(R.string.remove)) { scope.launch { message = sync.revokeDevice(d.id); reload++ } }
        }
        SectionRule()
    }
    message?.let { Text(it.text(), style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }

    Spacer(Modifier.height(NowFocusSpace.s6))
    Text(stringResource(R.string.account_signout_note), style = body)
    Spacer(Modifier.height(NowFocusSpace.s2))
    SecondaryButton(stringResource(R.string.account_sign_out), modifier = Modifier.fillMaxWidth()) { scope.launch { sync.signOut() } }
    Spacer(Modifier.height(NowFocusSpace.s2))
    GhostButton(stringResource(R.string.account_delete)) { confirmDelete = true }

    if (confirmDelete) DeleteAccountDialog(sync, onDone = { confirmDelete = false })
}

@Composable
private fun DeleteAccountDialog(sync: SyncController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.account_delete_title), style = headingStyle(20.sp)) },
        text = {
            Column {
                Text(stringResource(R.string.account_delete_body), style = body)
                NowFocusTextField(
                    value = password, onValueChange = { password = it }, label = stringResource(R.string.account_confirm_password), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3),
                )
                error?.let { Text(it.text(), style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
            }
        },
        confirmButton = {
            PrimaryButton(stringResource(if (busy) R.string.deleting else R.string.delete), enabled = !busy && password.isNotEmpty()) {
                busy = true; error = null
                scope.launch { error = sync.deleteAccount(password); busy = false; if (error == null) onDone() }
            }
        },
        dismissButton = { GhostButton(stringResource(R.string.cancel)) { if (!busy) onDone() } },
    )
}

private fun ago(at: Long): UiText {
    val s = ((System.currentTimeMillis() - at) / 1000).toInt()
    return when {
        s < 10 -> uiText(R.string.ago_now)
        s < 60 -> uiText(R.string.ago_seconds, s)
        s < 3600 -> uiText(R.string.ago_minutes, s / 60)
        else -> uiText(R.string.ago_hours, s / 3600)
    }
}
