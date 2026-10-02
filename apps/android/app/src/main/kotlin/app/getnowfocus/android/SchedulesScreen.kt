package app.getnowfocus.android

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

/** The list of recurring sessions, and an editor for one. [prefill] opens the editor on an unsaved schedule (from Stats). */
@Composable
fun SchedulesScreen(
    schedules: List<Schedule>,
    policies: List<BlockPolicy>,
    prefill: Schedule?,
    onSave: (Schedule) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf(prefill) }
    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        ScheduleEditor(
            initial = current,
            isNew = schedules.none { it.id == current.id },
            policies = policies,
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(current.id); editing = null },
            onCancel = { editing = null },
        )
        return
    }
    BackHandler(onBack = onBack)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("Schedules", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("Sessions that start by themselves, like Mon-Fri 9-12. They won't start on a cheat day, or while another session is running.", style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        schedules.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        "${Schedules.daysLabel(s.days)} · ${formatClock(s.startMinute, is24Hour)}-${formatClock(s.endMinute, is24Hour)} · ${policies.find { it.id == s.policyId }?.name ?: "No profile"} · ${s.mode.name.lowercase().replaceFirstChar(Char::uppercase)}",
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                TagPill(if (s.enabled) "On" else "Off", accent = false)
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        if (policies.isEmpty()) {
            Text("Create a profile in Rules first: a schedule needs one to block.", style = body)
        } else {
            GhostButton("+ New schedule") {
                editing = Schedule(
                    name = "Work", days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                    startMinute = 9 * 60, endMinute = 12 * 60, policyId = policies.first().id,
                )
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
}

@Composable
private fun ScheduleEditor(
    initial: Schedule,
    isNew: Boolean,
    policies: List<BlockPolicy>,
    onSave: (Schedule) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = s.name.isNotBlank() && s.days.isNotEmpty() && policies.any { it.id == s.policyId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onCancel)
        Text(if (isNew) "New schedule" else "Edit schedule", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))
        NowFocusTextField(value = s.name, onValueChange = { s = s.copy(name = it.take(40)) }, label = "Name", singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("DAYS", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { d ->
                Chip(d.name.take(3).lowercase().replaceFirstChar(Char::uppercase), d in s.days) { s = s.copy(days = if (d in s.days) s.days - d else s.days + d) }
            }
        }

        Spacer(Modifier.height(NowFocusSpace.s4))
        Row(Modifier.fillMaxWidth()) {
            TimeBump("Starts", s.startMinute, Modifier.weight(1f)) { s = s.copy(startMinute = it) }
            TimeBump("Ends", s.endMinute, Modifier.weight(1f)) { s = s.copy(endMinute = it) }
        }
        if (s.endMinute <= s.startMinute) Text("Ends the next day.", style = body)

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("BLOCK PROFILE", style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        policies.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { s = s.copy(policyId = p.id) }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(if (p.id == s.policyId) NowFocusColors.accent else Color.Transparent))
                Spacer(Modifier.width(NowFocusSpace.s3))
                Text(p.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
            }
            SectionRule()
        }

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("IF I WANT TO STOP EARLY", style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            listOf("Normal" to EnforcementMode.NORMAL, "Strict" to EnforcementMode.STRICT, "Locked" to EnforcementMode.LOCKED),
            s.mode, { s = s.copy(mode = it) },
        )
        if (s.mode == EnforcementMode.LOCKED) {
            Text("Locked has no early exit, and this one starts by itself.", style = body, modifier = Modifier.padding(top = NowFocusSpace.s2))
        }

        Spacer(Modifier.height(NowFocusSpace.s2))
        ToggleRow("On", "Starts by itself at the times above", s.enabled, { s = s.copy(enabled = !s.enabled) })
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s4))
        PrimaryButton("Save", enabled = valid) { onSave(s) }
        if (!isNew) GhostButton("Delete schedule") { confirmDelete = true }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
    if (confirmDelete) {
        ConfirmDialog("Delete ${s.name}?", "It won't start again. A session it already started keeps running.", "Delete", { onDelete() }, { confirmDelete = false })
    }
}
