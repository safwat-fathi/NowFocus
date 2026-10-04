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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)

private val links = listOf(
    "Website" to "https://nowfocus.online/",
    "Privacy policy" to "https://nowfocus.online/privacy/",
    "Terms" to "https://nowfocus.online/terms/",
    "Source code" to "https://github.com/safwat-fathi/NowFocus",
    "Contact support" to "mailto:safwat.rashwan@gmail.com",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uri = LocalUriHandler.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Back", onClick = onBack)
        Text("About", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("NowFocus", style = headingStyle(28.sp))
        Text("Version ${BuildConfig.VERSION_NAME}", style = body)
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text("Blocks the apps that pull you away, and keeps them blocked until you're done.", style = body)
        Spacer(Modifier.height(NowFocusSpace.s4))
        SectionRule(thick = true)
        links.forEach { (label, url) ->
            Text(
                label,
                style = body,
                modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(url) } }.padding(vertical = 14.dp),
            )
            SectionRule()
        }
        Spacer(Modifier.height(NowFocusSpace.s4))
        Text("Source available under the FSL-1.1-ALv2 license.", style = body)
        Text("© 2026 Safwat Fathi", style = body)
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
