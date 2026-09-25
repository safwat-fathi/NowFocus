package app.getnowfocus.android

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun PolicyListScreen(
    policies: List<BlockPolicy>,
    shield: CommitmentShield?,
    now: Long,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onOpenCommitment: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                Text("Rules", style = headingStyle(28.sp), modifier = Modifier.weight(1f))
                SecondaryButton("+ New profile", onClick = onAdd)
            }
            Text("FOCUS PROFILES", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s4, bottom = NowFocusSpace.s1))
            SectionRule()
        }
        items(policies, key = { it.id }) { policy ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(policy.id) }.padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(policy.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text("${policy.domains.size} sites · ${policy.apps.size} apps", style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                }
                GhostButton("Remove") { onDelete(policy.id) }
            }
            SectionRule()
        }
        item {
            Text("PROTECTIONS", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
            SectionRule()
            val active = shield != null && shield.endAt > now
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenCommitment).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Commitment Shield", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        if (active) "${shield!!.domains.size} sites · ${shield.packages.size} apps · locked 14 days" else "Not set up",
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                if (active) TagPill("Active")
            }
            SectionRule()
        }
    }
}

@Composable
fun PolicyEditorScreen(policy: BlockPolicy, onSave: (BlockPolicy) -> Unit, onBack: () -> Unit) {
    var name by remember(policy.id) { mutableStateOf(policy.name) }
    var newDomain by remember { mutableStateOf("") }
    var pickingApp by remember { mutableStateOf(false) }

    fun addDomain() {
        val domain = DomainValidation.normalize(newDomain) ?: return
        if (domain !in policy.domains) onSave(policy.copy(domains = policy.domains + domain))
        newDomain = ""
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton("‹ Rules", onClick = onBack)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onSave(policy.copy(name = it)) },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s2),
            )
            Text("BLOCKED WEBSITES", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
            SectionRule(thick = true)
        }
        items(policy.domains, key = { "d:$it" }) { domain ->
            RuleRow(domain, "+ subdomains") { onSave(policy.copy(domains = policy.domains - domain)) }
        }
        item {
            Row(Modifier.padding(top = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    label = { Text("Add a site, e.g. youtube.com") },
                    singleLine = true,
                    isError = newDomain.isNotBlank() && DomainValidation.normalize(newDomain) == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDomain() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(NowFocusSpace.s2))
                SecondaryButton("Add", onClick = ::addDomain)
            }
            Text("BLOCKED APPLICATIONS", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
            SectionRule(thick = true)
        }
        items(policy.apps, key = { "a:${it.packageName}" }) { app ->
            RuleRow(app.label, app.packageName) { onSave(policy.copy(apps = policy.apps - app)) }
        }
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton("+ Add application…") { pickingApp = true }
            Spacer(Modifier.height(NowFocusSpace.s4))
        }
    }

    if (pickingApp) {
        AppPickerDialog(
            exclude = policy.apps.map { it.packageName }.toSet(),
            onPick = { onSave(policy.copy(apps = policy.apps + it)); pickingApp = false },
            onDismiss = { pickingApp = false },
        )
    }
}

@Composable
fun RuleRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
            Text(subtitle, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
        }
        GhostButton("Remove", onClick = onRemove)
    }
    SectionRule()
}

@Composable
fun AppPickerDialog(exclude: Set<String>, onPick: (AppRule) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppRule(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName && it.packageName !in exclude }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select an app to block") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Text(app.label, Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton("Cancel", onClick = onDismiss) },
    )
}
