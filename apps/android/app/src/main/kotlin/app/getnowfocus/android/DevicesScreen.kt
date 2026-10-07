package app.getnowfocus.android

import android.content.Intent
import android.net.VpnService
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.getnowfocus.android.sync.SyncStatus
import androidx.compose.ui.res.stringResource

/**
 * This phone's health card (the exact checks HealthRow does, in the mockup's
 * card style) plus the optional account: signing in syncs profiles and
 * bedtime settings (see sync/ and services/api/WIRE_FORMAT.md).
 */
@Composable
fun DevicesScreen(resumeKey: Int, account: SyncStatus, onOpenAccount: () -> Unit) {
    val context = LocalContext.current
    val openAccessibility = accessibilityOpener()
    // resumeKey comes from App() (bumped on ON_RESUME) - a local one here
    // would never change, since permissions are granted in Settings, away
    // from this screen entirely.
    key(resumeKey) {
        val appBlockingOk = Enforcement.isAccessibilityEnabled(context)
        val websiteFilterOk = Enforcement.isVpnPermitted(context)
        val notifOk = context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: false
        val allOk = appBlockingOk && websiteFilterOk && notifOk

        Column(Modifier.fillMaxSize().padding(horizontal = NowFocusSpace.s4)) {
            Spacer(Modifier.height(NowFocusSpace.s2))
            Text(stringResource(R.string.devices_title), style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
            SectionRule(thick = true)
            Spacer(Modifier.height(NowFocusSpace.s4))
            if (account.signedIn) {
                Text(
                    stringResource(R.string.devices_signed_in, account.email.orEmpty()),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                )
                Spacer(Modifier.height(NowFocusSpace.s2))
                SecondaryButton(stringResource(R.string.devices_account), onClick = onOpenAccount)
            } else {
                Text(
                    stringResource(R.string.devices_alone),
                    style = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700),
                )
                Spacer(Modifier.height(NowFocusSpace.s2))
                SecondaryButton(stringResource(R.string.devices_sign_in), onClick = onOpenAccount)
            }
            Spacer(Modifier.height(NowFocusSpace.s6))

            SectionRule(thick = true)
            Row(Modifier.fillMaxWidth().padding(vertical = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
                DotIndicator(allOk)
                Spacer(Modifier.width(NowFocusSpace.s2))
                Text(stringResource(R.string.account_this_phone), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp), modifier = Modifier.weight(1f))
                TagPill(stringResource(if (allOk) R.string.devices_active else R.string.devices_degraded), accent = !allOk)
            }
            Row(Modifier.fillMaxWidth().border(1.dp, NowFocusColors.divider)) {
                LayerCell(stringResource(R.string.devices_app_blocking), appBlockingOk, Modifier.weight(1f))
                LayerCell(stringResource(R.string.devices_website_filter), websiteFilterOk, Modifier.weight(1f))
                LayerCell(stringResource(R.string.devices_dnd), notifOk, Modifier.weight(1f))
            }
            // One button per missing layer, each going straight to that permission.
            if (!appBlockingOk) {
                Spacer(Modifier.height(NowFocusSpace.s2))
                GhostButton(stringResource(R.string.devices_enable_blocking), onClick = openAccessibility)
            }
            if (!websiteFilterOk) {
                Spacer(Modifier.height(NowFocusSpace.s2))
                // prepare() returns the system VPN consent dialog's intent while consent is missing.
                GhostButton(stringResource(R.string.devices_enable_filter)) { VpnService.prepare(context)?.let(context::startActivity) }
            }
            if (!notifOk) {
                Spacer(Modifier.height(NowFocusSpace.s2))
                GhostButton(stringResource(R.string.devices_enable_dnd)) { Enforcement.openDndAccess(context) }
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
            stringResource(if (ok) R.string.on else R.string.off),
            style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (ok) NowFocusColors.text else NowFocusColors.accent700),
        )
        Text(label, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
    }
}
