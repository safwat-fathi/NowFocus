package app.getnowfocus.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

/**
 * Add and remove goals, each High / Med / Low. Stateless; used by the onboarding step and [GoalsScreen].
 * Mirrors macOS's Goals list, without the edit-in-place (remove and re-add).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalsEditor(
    goals: List<Goal>,
    onAdd: (text: String, priority: GoalPriority) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(GoalPriority.HIGH) }

    fun add() {
        if (text.isBlank()) return
        onAdd(text, priority)
        text = ""
    }

    goals.forEach { g ->
        Row(Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.text, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
            }
            TagPill(stringResource(g.priority.label), accent = g.priority == GoalPriority.HIGH)
            Spacer(Modifier.width(NowFocusSpace.s2))
            GhostButton(stringResource(R.string.remove)) { onRemove(g.id) }
        }
        Spacer(Modifier.height(NowFocusSpace.s2))
        SectionRule()
    }

    Row(Modifier.padding(top = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
        NowFocusTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.goals_hint),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NowFocusSpace.s2))
        SecondaryButton(stringResource(R.string.add), onClick = ::add)
    }
    Text(stringResource(R.string.goals_priority), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(top = NowFocusSpace.s3, bottom = NowFocusSpace.s1))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NowFocusSpace.s1)) {
        GoalPriority.entries.forEach { p -> Chip(stringResource(p.label), selected = p == priority) { priority = p } }
    }
}

@Composable
fun GoalsScreen(
    goals: List<Goal>,
    onAdd: (text: String, priority: GoalPriority) -> Unit,
    onRemove: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back_rules), onClick = onBack)
        Text(stringResource(R.string.goals_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            stringResource(R.string.goals_intro),
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule(thick = true)
        GoalsEditor(goals, onAdd, onRemove)
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
