package app.getnowfocus.android

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

private val links = listOf(
    R.string.about_website to "https://nowfocus.online/",
    R.string.about_privacy to "https://nowfocus.online/privacy/",
    R.string.about_terms to "https://nowfocus.online/terms/",
    R.string.about_source to "https://github.com/safwat-fathi/NowFocus",
    R.string.about_support to "mailto:hello@nowfocus.online",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uri = LocalUriHandler.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.about_title), style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.app_name), style = headingStyle(28.sp))
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = body)
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(R.string.about_tagline), style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        links.forEach { (label, url) ->
            Text(
                stringResource(label),
                style = body,
                modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(url) } }.padding(vertical = 14.dp),
            )
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        Text(stringResource(R.string.about_license), style = body)
        Text(stringResource(R.string.about_copyright), style = body)
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
