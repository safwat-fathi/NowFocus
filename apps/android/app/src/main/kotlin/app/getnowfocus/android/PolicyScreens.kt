package app.getnowfocus.android

import android.content.Intent
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/** One more tappable row in Rules > Protections, for the features added after the original five. */
data class ProtectionEntry(val title: String, val sub: String, val tag: String? = null, val onClick: () -> Unit)

@Composable
fun PolicyListScreen(
    policies: List<BlockPolicy>,
    shield: CommitmentShield?,
    bedtime: BedtimeSettings,
    now: Long,
    onOpen: (String) -> Unit,
    onAdd: (PolicyMode) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    groupsCount: Int,
    onOpenGroups: () -> Unit,
    onOpenCommitment: () -> Unit,
    onOpenBedtime: () -> Unit,
    peopleCount: Int,
    onOpenPeople: () -> Unit,
    goalsCount: Int,
    onOpenGoals: () -> Unit,
    extraRows: List<ProtectionEntry> = emptyList(),
) {
    var removing by remember { mutableStateOf<BlockPolicy?>(null) }
    var choosing by remember { mutableStateOf(false) }
    if (choosing) NewProfileDialog(onPick = { choosing = false; onAdd(it) }, onDismiss = { choosing = false })
    removing?.let { policy ->
        ConfirmDialog(
            title = stringResource(R.string.policies_remove_q, policy.name),
            // Bedtime points at a profile by id, and a missing one means it blocks nothing.
            message = stringResource(if (bedtime.policyId == policy.id) R.string.policies_remove_bedtime else R.string.policies_remove_body),
            confirmLabel = stringResource(R.string.remove),
            onConfirm = { onDelete(policy.id); removing = null },
            onDismiss = { removing = null },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rules_title), style = headingStyle(28.sp), modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.policies_new), onClick = { choosing = true })
            }
            Text(stringResource(R.string.policies_kicker), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s4, bottom = NowFocusSpace.s1))
            SectionRule()
        }
        items(policies, key = { it.id }) { policy ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(policy.id) }.padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(policy.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(profileSummary(policy), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                }
                GhostButton(stringResource(R.string.remove)) { removing = policy }
            }
            SectionRule()
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenGroups).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.groups_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        if (groupsCount == 0) stringResource(R.string.groups_row_empty) else pluralStringResource(R.plurals.n_groups, groupsCount, groupsCount),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
            }
            SectionRule()
            Text(stringResource(R.string.protections_kicker), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
            SectionRule()
            val active = shield != null && shield.endAt > now
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenCommitment).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shield_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        if (active) stringResource(R.string.shield_summary, sitesAppsText(shield!!.domains.size, shield.packages.size)) else stringResource(R.string.not_set_up),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                if (active) TagPill(stringResource(R.string.devices_active))
            }
            SectionRule()

            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenBedtime).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bedtime_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(stringResource(R.string.bedtime_every_night), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                }
                TagPill(stringResource(if (bedtime.enabled) R.string.on else R.string.off), accent = false)
            }
            SectionRule()

            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenPeople).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.people_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        if (peopleCount == 0) stringResource(R.string.not_set_up) else pluralStringResource(R.plurals.people_shown, peopleCount, peopleCount),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
            }
            SectionRule()

            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenGoals).padding(vertical = NowFocusSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.goals_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        if (goalsCount == 0) stringResource(R.string.not_set_up) else pluralStringResource(R.plurals.goals_count, goalsCount, goalsCount),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
            }
            SectionRule()

            extraRows.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(vertical = NowFocusSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                        Text(e.sub, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                    }
                    e.tag?.let { TagPill(it) }
                }
                SectionRule()
            }
        }
    }
}

/** What a profile holds, in a line: "3 sites · 2 apps" for a blocklist, "2 apps allowed" for a whitelist. */
@Composable
fun profileSummary(policy: BlockPolicy): String = when {
    policy.mode == PolicyMode.BLOCKLIST -> sitesAppsText(policy.domains.size, policy.apps.size)
    policy.apps.isEmpty() -> stringResource(R.string.policies_none_allowed)
    else -> pluralStringResource(R.plurals.policies_apps_allowed, policy.apps.size, policy.apps.size)
}

/** The mode is chosen here and never changes: the same rows mean opposite things in the two modes. */
@Composable
private fun NewProfileDialog(onPick: (PolicyMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.policies_new_title), style = headingStyle(20.sp)) },
        text = {
            Column {
                ModeChoice(R.string.policies_new_block, R.string.policies_new_block_sub) { onPick(PolicyMode.BLOCKLIST) }
                Spacer(Modifier.height(NowFocusSpace.s3))
                ModeChoice(R.string.policies_new_allow, R.string.policies_new_allow_sub) { onPick(PolicyMode.ALLOWLIST) }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

@Composable
private fun ModeChoice(@StringRes title: Int, @StringRes sub: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = NowFocusSpace.s2)) {
        Text(stringResource(title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
        Text(stringResource(sub), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
    }
}

@Composable
fun PolicyEditorScreen(
    policy: BlockPolicy,
    onSave: (BlockPolicy) -> Boolean,
    onBack: () -> Unit,
    groups: List<BlockPolicy> = emptyList(),
    onSaveAsGroup: ((BlockPolicy) -> Unit)? = null,
    isGroup: Boolean = false,
) {
    val context = LocalContext.current
    var name by remember(policy.id) { mutableStateOf(policy.name) }
    var newDomain by remember { mutableStateOf("") }
    var pickingApp by remember { mutableStateOf(false) }
    var pickingGroup by remember { mutableStateOf(false) }

    fun saveOrToast(newPolicy: BlockPolicy) {
        if (!onSave(newPolicy)) {
            val why = if (policy.mode == PolicyMode.ALLOWLIST) R.string.policy_cant_allow else R.string.policy_cant_remove
            android.widget.Toast.makeText(context, context.localized().getString(why), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun addDomain() {
        val domain = DomainValidation.normalize(newDomain) ?: return
        if (domain !in policy.domains) saveOrToast(policy.copy(domains = policy.domains + domain))
        newDomain = ""
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton(stringResource(if (isGroup) R.string.back_groups else R.string.back_rules), onClick = onBack)
            NowFocusTextField(
                value = name,
                onValueChange = { name = it; saveOrToast(policy.copy(name = it)) },
                label = stringResource(if (isGroup) R.string.group_name else R.string.policy_name),
                modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s2),
            )
        }
        // A whitelist is apps only: sites can't be filtered as "everything except", so it has no website list.
        if (policy.mode == PolicyMode.BLOCKLIST) {
            item {
                Text(stringResource(R.string.policy_websites), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
                SectionRule(thick = true)
            }
            items(policy.domains, key = { "d:$it" }) { domain ->
                RuleRow(domain, stringResource(R.string.policy_subdomains)) { saveOrToast(policy.copy(domains = policy.domains - domain)) }
            }
        }
        item {
            if (policy.mode == PolicyMode.BLOCKLIST) Row(Modifier.padding(top = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                NowFocusTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    placeholder = stringResource(R.string.policy_add_site_hint),
                    singleLine = true,
                    isError = newDomain.isNotBlank() && DomainValidation.normalize(newDomain) == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDomain() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(NowFocusSpace.s2))
                SecondaryButton(stringResource(R.string.add), onClick = ::addDomain)
            }
            Text(
                stringResource(if (policy.mode == PolicyMode.ALLOWLIST) R.string.policy_allowed_apps else R.string.policy_apps),
                style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1),
            )
            SectionRule(thick = true)
            if (policy.mode == PolicyMode.ALLOWLIST) {
                Text(
                    stringResource(R.string.policy_allow_note),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
                    modifier = Modifier.padding(top = NowFocusSpace.s2),
                )
            }
        }
        items(policy.apps, key = { "a:${it.packageName}" }) { app ->
            RuleRow(app.label, app.packageName) { saveOrToast(policy.copy(apps = policy.apps - app)) }
        }
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton(stringResource(R.string.policy_add_app)) { pickingApp = true }
            if (!isGroup && groups.isNotEmpty()) GhostButton(stringResource(R.string.group_add_to_profile)) { pickingGroup = true }
            if (!isGroup && onSaveAsGroup != null && (policy.domains.isNotEmpty() || policy.apps.isNotEmpty())) {
                GhostButton(stringResource(R.string.group_save_as)) {
                    onSaveAsGroup(policy)
                    android.widget.Toast.makeText(context, context.localized().getString(R.string.group_saved), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        item {
            if (!isGroup) PartialRulesSection(policy.partial) { saveOrToast(policy.copy(partial = it)) }
            Spacer(Modifier.height(NowFocusSpace.s4))
        }
    }

    if (pickingGroup) {
        GroupPickerDialog(
            groups = groups,
            onPick = { saveOrToast(policy.withGroup(it)); pickingGroup = false },
            onDismiss = { pickingGroup = false },
        )
    }

    if (pickingApp) {
        AppPickerDialog(
            title = stringResource(if (policy.mode == PolicyMode.ALLOWLIST) R.string.picker_title_allow else R.string.picker_title),
            exclude = policy.apps.map { it.packageName }.toSet(),
            onPick = { saveOrToast(policy.copy(apps = policy.apps + it)); pickingApp = false },
            onDismiss = { pickingApp = false },
        )
    }
}

/** In-app partial blocking switches, shared by the profile editor and the Commitment Shield setup. */
@Composable
fun PartialRulesSection(selected: Set<PartialRule>, onChange: (Set<PartialRule>) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.partial_kicker), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s6, bottom = NowFocusSpace.s1))
    SectionRule(thick = true)
    if (selected.isNotEmpty() && !Enforcement.isAccessibilityEnabled(context)) {
        Text(
            stringResource(R.string.partial_need_a11y),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
            modifier = Modifier.padding(top = NowFocusSpace.s2),
        )
    }
    PartialRule.entries.forEach { rule ->
        ToggleRow(stringResource(rule.label), stringResource(rule.detail), rule in selected, onToggle = { onChange(if (rule in selected) selected - rule else selected + rule) })
        SectionRule()
    }
}

@Composable
fun RuleRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp))
            Text(subtitle, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
        }
        GhostButton(stringResource(R.string.remove), onClick = onRemove)
    }
    SectionRule()
}

@Composable
fun AppPickerDialog(exclude: Set<String>, onPick: (AppRule) -> Unit, onDismiss: () -> Unit, title: String = stringResource(R.string.picker_title)) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppRule(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName && it.packageName !in exclude }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { filterApps(apps, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = headingStyle(20.sp)) },
        text = {
            Column {
                NowFocusTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = stringResource(R.string.picker_search), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.picker_none),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                        modifier = Modifier.padding(top = NowFocusSpace.s3),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = NowFocusSpace.s2)) {
                    items(shown, key = { it.packageName }) { app ->
                        Text(app.label, Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

/** Case-insensitive match on label or package name; a blank query keeps everything. */
internal fun filterApps(apps: List<AppRule>, query: String): List<AppRule> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
}

@Composable
private fun GroupPickerDialog(groups: List<BlockPolicy>, onPick: (BlockPolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_pick_title), style = headingStyle(20.sp)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(groups, key = { it.id }) { group ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(group) }.padding(vertical = 10.dp)) {
                        Text(group.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 16.sp))
                        Text(profileSummary(group), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

/** Saved site/app groups, added into any profile from its editor. Opening one reuses the profile editor. */
@Composable
fun GroupsScreen(groups: List<BlockPolicy>, onOpen: (String) -> Unit, onAdd: () -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    var removing by remember { mutableStateOf<BlockPolicy?>(null) }
    removing?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.groups_remove_q, group.name),
            message = stringResource(R.string.groups_remove_body),
            confirmLabel = stringResource(R.string.remove),
            onConfirm = { onDelete(group.id); removing = null },
            onDismiss = { removing = null },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
        item {
            Spacer(Modifier.height(NowFocusSpace.s2))
            GhostButton(stringResource(R.string.back_rules), onClick = onBack)
            Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.groups_title), style = headingStyle(28.sp), modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.groups_new), onClick = onAdd)
            }
            Text(
                stringResource(R.string.groups_note),
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
                modifier = Modifier.padding(bottom = NowFocusSpace.s2),
            )
            SectionRule()
        }
        items(groups, key = { it.id }) { group ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(group.id) }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(profileSummary(group), style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700))
                }
                GhostButton(stringResource(R.string.remove)) { removing = group }
            }
            SectionRule()
        }
    }
}
