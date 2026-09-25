package app.getnowfocus.android

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Real, but phone-only: one health card built from the exact checks
 * HealthRow already does, reformatted into the mockup's card style. No
 * Mac/PC cards or pairing - cross-device sync is Phase 6 in
 * native_tech_stack_spec.md, not built yet.
 */
@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    // Bumped on resume so this re-reads permission state changed in Settings.
    var resumeKey by remember { mutableIntStateOf(0) }
    key(resumeKey) {
        val appBlockingOk = Enforcement.isAccessibilityEnabled(context)
        val websiteFilterOk = Enforcement.isVpnPermitted(context)
        val notifOk = context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false
        val allOk = appBlockingOk && websiteFilterOk && notifOk

        Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
            Spacer(Modifier.height(NowFocusSpace.s2))
            Text("Devices", style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
            SectionRule(thick = true)
            Spacer(Modifier.height(NowFocusSpace.s4))
            Text(
                "One device today. Cross-device sync is planned but not built yet - see native_tech_stack_spec.md.",
                style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
            )
            Spacer(Modifier.height(NowFocusSpace.s6))

            SectionRule(thick = true)
            Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                DotIndicator(allOk)
                Spacer(Modifier.width(NowFocusSpace.s2))
                Text("This phone", style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp), modifier = Modifier.weight(1f))
                TagPill(if (allOk) "Active" else "Degraded", accent = !allOk)
            }
            Row(Modifier.fillMaxWidth().border(1.dp, NowFocusColors.divider)) {
                LayerCell("App blocking", appBlockingOk, Modifier.weight(1f))
                LayerCell("Website filter", websiteFilterOk, Modifier.weight(1f))
                LayerCell("Notifications", notifOk, Modifier.weight(1f))
            }
            if (!allOk) {
                Spacer(Modifier.height(NowFocusSpace.s2))
                GhostButton("Open Settings") { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            SectionRule()
        }
    }
}

@Composable
private fun DotIndicator(ok: Boolean) {
    Box(Modifier.width(10.dp).height(10.dp).background(if (ok) NowFocusColors.text else NowFocusColors.accent))
}

@Composable
private fun LayerCell(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.border(1.dp, NowFocusColors.divider).padding(NowFocusSpace.s2)) {
        Text(
            if (ok) "On" else "Off",
            style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (ok) NowFocusColors.text else NowFocusColors.accent700),
        )
        Text(label, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
    }
}
