package app.getnowfocus.android

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** App-wide settings: the language, and a way into About. */
@Composable
fun SettingsScreen(onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.read(context)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.settings_title), style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s4))

        Text(stringResource(R.string.settings_language), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            options = listOf(
                stringResource(R.string.lang_system) to AppLanguage.SYSTEM,
                stringResource(R.string.lang_english) to AppLanguage.EN,
                stringResource(R.string.lang_arabic) to AppLanguage.AR,
            ),
            selected = language,
            onSelect = { picked ->
                if (picked != language) {
                    language = picked
                    AppLanguage.write(context, picked)
                    // The activity re-reads its language when it is recreated; the extra brings it back to this screen.
                    (context as? Activity)?.let { it.intent.putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_SETTINGS); it.recreate() }
                }
            },
        )

        Spacer(Modifier.height(NowFocusSpace.s6))
        SectionRule()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenAbout).padding(vertical = NowFocusSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                Text(
                    stringResource(R.string.about_row, BuildConfig.VERSION_NAME),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                )
            }
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
