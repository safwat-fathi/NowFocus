package app.getnowfocus.android

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import androidx.compose.ui.res.stringResource

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

/** Plan a day off from blocking, ahead of time. See [CheatDays] for the rules and why they exist. */
@Composable
fun CheatDayScreen(cheat: CheatDay?, now: Long, onSchedule: (Long) -> Boolean, onCancel: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    fun day(millis: Long) = formatDay(context, millis)
    fun time(millis: Long) = formatTime(context, millis)
    var confirming by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.cheat_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.cheat_intro), style = body)
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.cheat_rule), style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s3))

        when {
            cheat != null && cheat.isActive(now) -> {
                Text(stringResource(R.string.cheat_on_now), style = headingStyle(28.sp))
                Text(stringResource(R.string.cheat_paused_until, time(cheat.endAt)), style = body)
                Spacer(Modifier.height(NowFocusSpace.s4))
                SecondaryButton(stringResource(R.string.cheat_end_early), onClick = onCancel)
            }
            cheat != null && now < cheat.startAt -> {
                Text(stringResource(R.string.cheat_set_for, day(cheat.startAt)), style = headingStyle(28.sp))
                Text(stringResource(R.string.cheat_starts_at, time(cheat.startAt)), style = body)
                Spacer(Modifier.height(NowFocusSpace.s4))
                SecondaryButton(stringResource(R.string.cheat_cancel), onClick = onCancel)
            }
            else -> {
                Text(stringResource(R.string.cheat_pick), style = kickerStyle(NowFocusColors.neutral700))
                SectionRule()
                CheatDays.options(now, ZoneId.systemDefault(), cheat).forEach { start ->
                    Text(
                        day(start),
                        style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                        modifier = Modifier.fillMaxWidth().clickable { confirming = start }.padding(vertical = NowFocusSpace.s3),
                    )
                    SectionRule()
                }
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
    }
    confirming?.let { start ->
        ConfirmDialog(
            stringResource(R.string.cheat_confirm_title, day(start)),
            stringResource(R.string.cheat_confirm_body),
            stringResource(R.string.cheat_confirm_yes),
            { onSchedule(start); confirming = null },
            { confirming = null },
        )
    }
}
