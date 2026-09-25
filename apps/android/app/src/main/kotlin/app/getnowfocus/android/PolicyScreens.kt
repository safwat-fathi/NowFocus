package app.getnowfocus.android

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun PolicyListScreen(
    policies: List<BlockPolicy>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Policies", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("+ Add") }
            }
        }
        items(policies, key = { it.id }) { policy ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(policy.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(policy.name, style = MaterialTheme.typography.titleMedium)
                    Text("${policy.domains.size} sites · ${policy.apps.size} apps", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onDelete(policy.id) }) { Text("Delete", color = Color.Red) }
            }
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

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("‹ Policies") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onSave(policy.copy(name = it)) },
                label = { Text("Policy Name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(8.dp))
            Text("Blocked Websites", style = MaterialTheme.typography.titleMedium)
        }
        items(policy.domains, key = { "d:$it" }) { domain ->
            RuleRow(domain, "+ subdomains") { onSave(policy.copy(domains = policy.domains - domain)) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    label = { Text("Add domain (e.g. youtube.com)") },
                    singleLine = true,
                    isError = newDomain.isNotBlank() && DomainValidation.normalize(newDomain) == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDomain() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addDomain() }, enabled = DomainValidation.normalize(newDomain) != null) { Text("Add") }
            }
            Spacer(Modifier.padding(8.dp))
            Text("Blocked Applications", style = MaterialTheme.typography.titleMedium)
        }
        items(policy.apps, key = { "a:${it.packageName}" }) { app ->
            RuleRow(app.label, app.packageName) { onSave(policy.copy(apps = policy.apps - app)) }
        }
        item {
            TextButton(onClick = { pickingApp = true }) { Text("Add Application…") }
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
private fun RuleRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) { Text("Remove", color = Color.Red) }
    }
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onPick: (AppRule) -> Unit, onDismiss: () -> Unit) {
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
