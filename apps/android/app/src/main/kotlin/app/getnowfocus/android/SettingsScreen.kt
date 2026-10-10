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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.getnowfocus.android.sync.SyncController
import kotlinx.coroutines.launch

/** App-wide settings: the language, a way into About, and reporting an issue. */
@Composable
fun SettingsScreen(sync: SyncController, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
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
        Row(
            Modifier.fillMaxWidth().clickable { reporting = true }.padding(vertical = NowFocusSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.report_title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                Text(
                    stringResource(if (reported) R.string.report_sent else R.string.report_row),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                )
            }
        }
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
    if (reporting) ReportIssueDialog(sync, onDone = { sent -> reporting = false; if (sent) reported = true })
}

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700)


@Composable
private fun ReportIssueDialog(sync: SyncController, onDone: (sent: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        title = { Text(stringResource(R.string.report_title), style = headingStyle(20.sp)) },
        text = {
            Column {
                Text(stringResource(R.string.report_body), style = body)
                NowFocusTextField(
                    value = message, onValueChange = { message = it.take(4000) }, label = stringResource(R.string.report_message),
                    modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3),
                )
                NowFocusTextField(
                    value = contact, onValueChange = { contact = it.take(254) }, label = stringResource(R.string.report_contact), singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3),
                )
                error?.let { Text(it.text(), style = body.copy(color = NowFocusColors.accent700), modifier = Modifier.padding(top = NowFocusSpace.s2)) }
            }
        },
        confirmButton = {
            PrimaryButton(stringResource(if (busy) R.string.report_sending else R.string.report_send), enabled = !busy && message.isNotBlank()) {
                busy = true; error = null
                scope.launch { error = sync.reportIssue(message, contact); busy = false; if (error == null) onDone(true) }
            }
        },
        dismissButton = { GhostButton(stringResource(R.string.cancel)) { if (!busy) onDone(false) } },
    )
}
