package app.getnowfocus.android

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

private val body = TextStyle(fontFamily = ArchivoRegular, fontSize = 13.sp, color = NowFocusColors.neutral700)

private val providers = listOf(
    Triple(DnsProvider.SYSTEM, R.string.dns_system, R.string.dns_system_sub),
    Triple(DnsProvider.ADGUARD_FAMILY, R.string.dns_adguard, R.string.dns_adguard_sub),
    Triple(DnsProvider.CLOUDFLARE_FAMILY, R.string.dns_cloudflare, R.string.dns_cloudflare_sub),
    Triple(DnsProvider.CLEANBROWSING_FAMILY, R.string.dns_cleanbrowsing, R.string.dns_cleanbrowsing_sub),
    Triple(DnsProvider.QUAD9, R.string.dns_quad9, R.string.dns_quad9_sub),
    Triple(DnsProvider.CUSTOM, R.string.dns_custom, R.string.dns_custom_sub),
)

fun dnsProviderName(provider: DnsProvider): Int = providers.first { it.first == provider }.second

/** The Rules tab's Protections row for this screen: what is chosen, and an "on" pill only while it runs all the time. */
@Composable
fun dnsEntry(context: android.content.Context, onClick: () -> Unit): ProtectionEntry {
    val choice = DnsSetting.read(context)
    val active = choice.effective != DnsProvider.SYSTEM
    return ProtectionEntry(
        stringResource(R.string.dns_title),
        if (!active) stringResource(R.string.not_set_up)
        else stringResource(if (choice.alwaysOn) R.string.dns_row_always else R.string.dns_row_session, stringResource(dnsProviderName(choice.effective))),
        tag = if (active && choice.alwaysOn) stringResource(R.string.on) else null,
        onClick = onClick,
    )
}

/**
 * The DNS NowFocus asks while it runs, and whether it also runs outside focus sessions. Top to bottom: the answer to
 * "is it working?" (a status and at most one fix button), then which server, then when. All decisions are
 * [DnsPolicy]'s; this only draws them.
 */
@Composable
fun DnsScreen(sessionLive: Boolean, resumeKey: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var choice by remember { mutableStateOf(DnsSetting.read(context)) }
    var host by remember { mutableStateOf(choice.customHost) }
    // The tunnel and the upstream answer on their own schedule: look again every couple of seconds while visible.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val strictHost = remember(tick, resumeKey) { Enforcement.strictPrivateDns(context) }
    val status = remember(tick, resumeKey, choice, sessionLive) {
        DnsPolicy.status(
            choice, sessionLive, Enforcement.isVpnPermitted(context), strictHost,
            FocusVpnService.tunnelUp, DnsUpstream.health(), System.currentTimeMillis(),
        )
    }

    fun save(next: DnsChoice) {
        choice = next
        DnsSetting.write(context, next)
        Enforcement.syncDns(context)
    }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Enforcement.syncDns(context)
        tick++
    }
    fun askConsentThen(next: DnsChoice) {
        save(next)
        VpnService.prepare(context)?.let { vpnConsent.launch(it) }
    }

    val name = stringResource(dnsProviderName(choice.effective))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton(stringResource(R.string.back), onClick = onBack)
        Text(stringResource(R.string.dns_title), style = headingStyle(28.sp), modifier = Modifier.padding(vertical = NowFocusSpace.s2))
        Text(stringResource(R.string.dns_lede), style = body)
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule(thick = true)
        Spacer(Modifier.height(NowFocusSpace.s3))

        // Status
        val (label, sentence) = when (status) {
            DnsStatus.Off -> R.string.dns_status_off to stringResource(R.string.dns_sent_off)
            DnsStatus.WaitingForSession -> R.string.dns_status_waiting to stringResource(R.string.dns_sent_waiting, name)
            DnsStatus.Active -> R.string.dns_status_active to stringResource(R.string.dns_sent_active, name)
            DnsStatus.NeedsPermission -> R.string.dns_status_needs to stringResource(R.string.dns_sent_needs)
            is DnsStatus.StrictConflict -> R.string.dns_status_conflict to stringResource(R.string.dns_sent_conflict, status.host)
            DnsStatus.Starting -> R.string.dns_status_starting to stringResource(R.string.dns_sent_starting)
            DnsStatus.Unreachable -> R.string.dns_status_unreachable to stringResource(R.string.dns_sent_unreachable, name)
        }
        val ok = status == DnsStatus.Active || status == DnsStatus.Off || status == DnsStatus.WaitingForSession
        TagPill(stringResource(label), accent = !ok)
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(sentence, style = body.copy(color = NowFocusColors.text, fontSize = 15.sp))
        when (status) {
            DnsStatus.NeedsPermission -> {
                Spacer(Modifier.height(NowFocusSpace.s2))
                GhostButton(stringResource(R.string.dns_fix_permission)) { VpnService.prepare(context)?.let { vpnConsent.launch(it) } }
            }
            is DnsStatus.StrictConflict -> {
                Spacer(Modifier.height(NowFocusSpace.s2))
                PrimaryButton(stringResource(R.string.dns_keep, status.host)) {
                    host = status.host
                    save(DnsChoice(DnsProvider.CUSTOM, DnsSetting.hostOrNull(status.host) ?: "", choice.alwaysOn))
                }
                GhostButton(stringResource(R.string.dns_open_network)) {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> {}
        }
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule()

        // Server
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(stringResource(R.string.dns_server), style = kickerStyle(NowFocusColors.neutral700))
        providers.forEach { (provider, title, sub) ->
            Row(
                Modifier.fillMaxWidth().clickable { save(choice.copy(provider = provider)) }.padding(vertical = NowFocusSpace.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (choice.provider == provider) "●" else "○", style = body.copy(color = NowFocusColors.text), modifier = Modifier.padding(end = NowFocusSpace.s3))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.text))
                    Text(stringResource(sub), style = body)
                }
            }
        }
        if (choice.provider == DnsProvider.CUSTOM) {
            NowFocusTextField(
                value = host,
                onValueChange = { raw ->
                    host = raw.take(253)
                    save(choice.copy(customHost = DnsSetting.hostOrNull(raw) ?: ""))
                },
                label = stringResource(R.string.dns_custom_host),
                isError = host.isNotBlank() && choice.customHost.isEmpty(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = NowFocusSpace.s2),
            )
        }
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule()

        // When
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(stringResource(R.string.dns_when), style = kickerStyle(NowFocusColors.neutral700))
        Spacer(Modifier.height(NowFocusSpace.s2))
        SegmentedControl(
            options = listOf(stringResource(R.string.dns_when_session) to false, stringResource(R.string.dns_when_always) to true),
            selected = choice.alwaysOn,
            onSelect = { always ->
                // Always-on needs the VPN consent now, not at the next session.
                if (always && choice.effective != DnsProvider.SYSTEM) askConsentThen(choice.copy(alwaysOn = true)) else save(choice.copy(alwaysOn = always))
            },
        )
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(stringResource(if (choice.alwaysOn) R.string.dns_when_note_always else R.string.dns_when_note_session), style = body)
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule()
        Spacer(Modifier.height(NowFocusSpace.s3))
        Text(stringResource(R.string.dns_limits), style = body)
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
