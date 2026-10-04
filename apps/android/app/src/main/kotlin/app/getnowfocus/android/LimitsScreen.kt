package app.getnowfocus.android

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId
import androidx.compose.ui.res.stringResource

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

/**
 * Daily per-app budgets. Needs usage access to read how long an app was in front today; without it the
 * limits are saved but not enforced, and this screen says so. [resumeKey] re-reads that after Settings.
 */
@Composable
fun LimitsScreen(
    limits: List<AppLimit>,
    siteUsage: SiteUsage,
    resumeKey: Int,
    onChange: (AppLimit, Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val granted = remember(resumeKey) { UsageAccess.isGranted(context) }
    var used by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    // Re-read every 30s so the screen doesn't keep saying "Used up" after midnight when left open.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(limits, resumeKey) {
        while (true) {
            now = System.currentTimeMillis()
            used = withContext(Dispatchers.Default) { UsageReader.foregroundToday(context, limits.filterNot { SiteLimits.isSite(it) }.map { it.packageName }.toSet(), now) }
            delay(30_000)
        }
    }
    var picking by remember { mutableStateOf(false) }
    var pickingSite by remember { mutableStateOf(false) }
    val siteWatching = remember(resumeKey) { FocusAccessibilityService.instance != null }
    var editing by remember { mutableStateOf<AppLimit?>(null) }
    fun usedMsOf(l: AppLimit): Long =
        if (SiteLimits.isSite(l)) siteUsage.usedMs(l.packageName, SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())) else used[l.packageName] ?: 0L

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.limits_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.limits_intro), style = body)
        if (!granted) {
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text(stringResource(R.string.limits_need_usage), style = body.copy(color = NowFocusColors.accent700))
            Spacer(Modifier.height(NowFocusSpace.s2))
            SecondaryButton(stringResource(R.string.limits_allow_usage)) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        limits.forEach { l ->
            val minutes = l.minutesAt(now)
            val usedMs = usedMsOf(l)
            Row(Modifier.fillMaxWidth().clickable { editing = l }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    // Under a minute reads "45 s", not a misleading "0": counting was working, the display just rounded it away.
                    val usedText = if (usedMs in 1 until 60_000) stringResource(R.string.limits_used_secs, usedMs / 1000) else "${usedMs / 60_000}"
                    val pending = l.pendingMinutes?.takeIf { l.pendingFrom > now }
                    Text(
                        (if (minutes == 0) stringResource(R.string.limits_none) else stringResource(R.string.limits_used_of, usedText, minutes)) +
                            when (pending) {
                                null -> ""
                                0 -> stringResource(R.string.limits_removed_midnight)
                                else -> stringResource(R.string.limits_from_midnight, pending)
                            },
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                if (minutes > 0 && usedMs >= minutes * 60_000L) TagPill(stringResource(R.string.limits_used_up))
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.limits_add)) { picking = true }
        GhostButton(stringResource(R.string.limits_add_site)) { pickingSite = true }
        if (!siteWatching && limits.any { SiteLimits.isSite(it) }) {
            Text(stringResource(R.string.limits_site_need_a11y), style = body.copy(color = NowFocusColors.accent700))
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }

    if (picking) {
        AppPickerDialog(
            exclude = limits.map { it.packageName }.toSet(),
            onPick = { app -> picking = false; editing = AppLimit(app.packageName, app.label, minutesPerDay = 0) },
            onDismiss = { picking = false },
        )
    }
    if (pickingSite) {
        DomainDialog(
            onPick = { domain ->
                pickingSite = false
                val key = SiteLimits.key(domain)
                editing = limits.find { it.packageName == key } ?: AppLimit(key, domain, minutesPerDay = 0)
            },
            onDismiss = { pickingSite = false },
        )
    }
    editing?.let { l ->
        MinutesDialog(
            title = l.label,
            isNew = l.minutesPerDay == 0 && l.pendingMinutes == null,
            usedUp = l.minutesAt(now) > 0 && usedMsOf(l) >= l.minutesAt(now) * 60_000L,
            onPick = { minutes -> onChange(l, minutes); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun DomainDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val domain = DomainValidation.normalize(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limits_website), style = headingStyle(20.sp)) },
        text = {
            NowFocusTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.limits_site_hint),
                singleLine = true,
                isError = text.isNotBlank() && domain == null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { domain?.let(onPick) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { if (domain != null) SecondaryButton(stringResource(R.string.next)) { onPick(domain) } },
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

@Composable
private fun MinutesDialog(title: String, isNew: Boolean, usedUp: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = headingStyle(20.sp)) },
        text = {
            Column {
                Text(stringResource(R.string.limits_minutes_per_day), style = kickerStyle(NowFocusColors.neutral700))
                Spacer(Modifier.height(NowFocusSpace.s2))
                AppLimit.MINUTE_CHOICES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        row.forEach { m -> Chip("$m", false) { onPick(m) } }
                    }
                }
                if (!isNew) {
                    // Raising or removing a used-up limit would just undo the block it caused, so it waits for midnight.
                    if (usedUp) Text(stringResource(R.string.limits_used_up_note), style = body.copy(fontSize = 13.sp))
                    GhostButton(stringResource(R.string.limits_remove)) { onPick(0) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

/** The apps that ask for a pause before they open. See [FrictionGate]. */
@Composable
fun FrictionAppsScreen(apps: List<AppRule>, onAdd: (AppRule) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.friction_apps_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.friction_apps_intro), style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        apps.forEach { RuleRow(it.label, it.packageName) { onRemove(it.packageName) } }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.friction_apps_add)) { picking = true }
    }
    if (picking) {
        AppPickerDialog(exclude = apps.map { it.packageName }.toSet(), onPick = { onAdd(it); picking = false }, onDismiss = { picking = false })
    }
}
