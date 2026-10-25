package app.getnowfocus.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * Free vs Pro (docs/pro-launch.md). The server owns the plan; the app keeps the last answer sync got.
 * [BuildConfig.PRO_ENFORCED] is false until Pro launches, and then every feature stays open, signed in or not.
 */
object Pro {
    /** [stored] is that last answer (true Pro, false Free), null when signed out or not asked yet: both count as Free once enforced. */
    fun active(stored: Boolean?, enforced: Boolean = BuildConfig.PRO_ENFORCED): Boolean = !enforced || stored == true
}

/** An allowlist is Pro: a Free account keeps the profile (and it syncs), but cannot start it. */
fun BlockPolicy.canStart(pro: Boolean): Boolean = enforcesHere && (mode == PolicyMode.BLOCKLIST || pro)

/** Says a feature is part of Pro and why it does nothing on this account. Shown only while [Pro.active] is false. */
@Composable
fun ProNote(@StringRes text: Int, modifier: Modifier = Modifier, color: Color = NowFocusColors.neutral700) {
    Text(
        stringResource(text),
        style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = color),
        modifier = modifier.padding(vertical = NowFocusSpace.s2),
    )
}
