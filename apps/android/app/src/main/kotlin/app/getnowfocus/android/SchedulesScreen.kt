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
import androidx.compose.ui.res.stringResource

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
    val locale = LocalContext.current.appLocale()
    val defaultName = stringResource(R.string.sched_default_name)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.sched_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.sched_intro), style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        schedules.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                    Text(
                        stringResource(
                            R.string.sched_row, Schedules.daysLabel(s.days).text(), formatClock(s.startMinute, is24Hour, locale), formatClock(s.endMinute, is24Hour, locale),
                            policies.find { it.id == s.policyId }?.name ?: stringResource(R.string.sched_no_profile), stringResource(s.mode.labelRes()),
                        ),
                        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                    )
                }
                TagPill(stringResource(if (s.enabled) R.string.on else R.string.off), accent = false)
            }
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        if (policies.isEmpty()) {
            Text(stringResource(R.string.sched_need_profile), style = body)
        } else {
            GhostButton(stringResource(R.string.sched_new)) {
                editing = Schedule(
                    name = defaultName, days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
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
        GhostButton(stringResource(R.string.back), onClick = onCancel)
        Text(stringResource(if (isNew) R.string.sched_new_title else R.string.sched_edit_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s4))
        NowFocusTextField(value = s.name, onValueChange = { s = s.copy(name = it.take(40)) }, label = stringResource(R.string.name), singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(stringResource(R.string.sched_days), style = kickerStyle(NowFocusColors.neutral700))
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { d ->
                Chip(d.shortName().text(), d in s.days) { s = s.copy(days = if (d in s.days) s.days - d else s.days + d) }
            }
        }

        Spacer(Modifier.height(NowFocusSpace.s4))
        Row(Modifier.fillMaxWidth()) {
            TimeBump(stringResource(R.string.sched_starts), s.startMinute, Modifier.weight(1f)) { s = s.copy(startMinute = it) }
            TimeBump(stringResource(R.string.sched_ends), s.endMinute, Modifier.weight(1f)) { s = s.copy(endMinute = it) }
        }
        if (s.endMinute <= s.startMinute) Text(stringResource(R.string.sched_next_day), style = body)

        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(stringResource(R.string.sched_block_profile), style = kickerStyle(NowFocusColors.neutral700))
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
        Text(stringResource(R.string.sched_stop_early), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            EnforcementMode.entries.map { stringResource(it.labelRes()) to it },
            s.mode, { s = s.copy(mode = it) },
        )
        if (s.mode == EnforcementMode.LOCKED) {
            Text(stringResource(R.string.sched_locked_note), style = body, modifier = Modifier.padding(top = NowFocusSpace.s2))
        }

        Spacer(Modifier.height(NowFocusSpace.s2))
        ToggleRow(stringResource(R.string.on), stringResource(R.string.sched_on_sub), s.enabled, { s = s.copy(enabled = !s.enabled) })
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s4))
        PrimaryButton(stringResource(R.string.save), enabled = valid) { onSave(s) }
        if (!isNew) GhostButton(stringResource(R.string.sched_delete)) { confirmDelete = true }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.sched_delete_q, s.name), stringResource(R.string.sched_delete_body), stringResource(R.string.delete), { onDelete() }, { confirmDelete = false })
    }
}
