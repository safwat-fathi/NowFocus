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

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

/** Plan a day off from blocking, ahead of time. See [CheatDays] for the rules and why they exist. */
@Composable
fun CheatDayScreen(cheat: CheatDay?, now: Long, onSchedule: (Long) -> Boolean, onCancel: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    fun day(millis: Long) = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    fun time(millis: Long) = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
    var confirming by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("Cheat day", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            "A whole day with blocking paused: sessions, Bedtime, schedules and limits. Your Commitment Shield stays on, always.",
            style = body,
        )
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("You set it at least 24 hours ahead, and you get one a week, so it's a plan and not an impulse.", style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s3))

        when {
            cheat != null && cheat.isActive(now) -> {
                Text("On now", style = headingStyle(28.sp))
                Text("Blocking is paused until ${time(cheat.endAt)}.", style = body)
                Spacer(Modifier.height(NowFocusSpace.s4))
                SecondaryButton("End it early", onClick = onCancel)
            }
            cheat != null && now < cheat.startAt -> {
                Text("Set for ${day(cheat.startAt)}", style = headingStyle(28.sp))
                Text("It starts at ${time(cheat.startAt)}. Cancelling frees your week.", style = body)
                Spacer(Modifier.height(NowFocusSpace.s4))
                SecondaryButton("Cancel it", onClick = onCancel)
            }
            else -> {
                Text("Pick a day", style = kickerStyle(NowFocusColors.neutral700))
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
            "Make ${day(start)} your cheat day?",
            "Blocking pauses from midnight until the next midnight. You can cancel it before then.",
            "Yes, plan it",
            { onSchedule(start); confirming = null },
            { confirming = null },
        )
    }
}
