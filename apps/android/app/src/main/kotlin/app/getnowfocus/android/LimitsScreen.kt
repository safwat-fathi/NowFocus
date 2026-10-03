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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("Daily limits", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("\"30 minutes of Instagram a day.\" Past it, the app or website is blocked until midnight. Website time is counted from your browser's address bar. Lowering or removing a limit counts at once, unless it is already used up; raising one only counts from midnight.", style = body)
        if (!granted) {
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text("Limits need usage access to see how long an app was open. Until you allow it, they aren't enforced.", style = body.copy(color = NowFocusColors.accent700))
            Spacer(Modifier.height(NowFocusSpace.s2))
            SecondaryButton("Allow usage access") { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        limits.forEach { l ->
            val minutes = l.minutesAt(now)
            val usedMs = if (SiteLimits.isSite(l)) siteUsage.usedMs(l.packageName, SiteLimits.dayOf(now, java.time.ZoneId.systemDefault())) else used[l.packageName] ?: 0L
            Row(Modifier.fillMaxWidth().clickable { editing = l }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    // Under a minute reads "45 s", not a misleading "0": counting was working, the display just rounded it away.
                    val usedText = if (usedMs in 1 until 60_000) "${usedMs / 1000} s" else "${usedMs / 60_000}"
                    val pending = l.pendingMinutes?.takeIf { l.pendingFrom > now }
                    Text(
                        buildString {
                            append(if (minutes == 0) "No limit" else "$usedText of $minutes min today")
                            if (pending != null) append(if (pending == 0) " · removed at midnight" else " · $pending min from midnight")
                        },
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                if (minutes > 0 && usedMs >= minutes * 60_000L) TagPill("Used up")
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("+ Add a limit…") { picking = true }
        GhostButton("+ Add a website limit…") { pickingSite = true }
        if (!siteWatching && limits.any { SiteLimits.isSite(it) }) {
            Text("Website limits need NowFocus's accessibility service switched on. Until then they aren't counted.", style = body.copy(color = NowFocusColors.accent700))
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
        title = { Text("Website", style = headingStyle(20.sp)) },
        text = {
            NowFocusTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "e.g. youtube.com",
                singleLine = true,
                isError = text.isNotBlank() && domain == null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { domain?.let(onPick) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { if (domain != null) SecondaryButton("Next") { onPick(domain) } },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun MinutesDialog(title: String, isNew: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = headingStyle(20.sp)) },
        text = {
            Column {
                Text("Minutes per day", style = kickerStyle(NowFocusColors.neutral700))
                Spacer(Modifier.height(NowFocusSpace.s2))
                AppLimit.MINUTE_CHOICES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        row.forEach { m -> Chip("$m", false) { onPick(m) } }
                    }
                }
                if (!isNew) GhostButton("Remove limit") { onPick(0) }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton("Cancel", onClick = onDismiss) },
    )
}

/** The apps that ask for a pause before they open. See [FrictionGate]. */
@Composable
fun FrictionAppsScreen(apps: List<AppRule>, onAdd: (AppRule) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("Opening friction", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("Open one of these apps and NowFocus first asks you to breathe for ten seconds and say what you're there for. Then it opens, and stays open for ten minutes. Works any time, in or out of a session.", style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        apps.forEach { RuleRow(it.label, it.packageName) { onRemove(it.packageName) } }
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("+ Add an app…") { picking = true }
    }
    if (picking) {
        AppPickerDialog(exclude = apps.map { it.packageName }.toSet(), onPick = { onAdd(it); picking = false }, onDismiss = { picking = false })
    }
}
