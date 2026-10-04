package app.getnowfocus.android

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Display names for enums that were being printed from `name`. FocusSession.kt is deliberately left untouched. */
@StringRes
fun EnforcementMode.labelRes(): Int = when (this) {
    EnforcementMode.NORMAL -> R.string.mode_normal
    EnforcementMode.STRICT -> R.string.mode_strict
    EnforcementMode.LOCKED -> R.string.mode_locked
}

/** "3 sites · 2 apps", each count in its own plural form. */
@Composable
fun sitesAppsText(sites: Int, apps: Int): String = stringResource(
    R.string.sites_apps, pluralStringResource(R.plurals.n_sites, sites, sites), pluralStringResource(R.plurals.n_apps, apps, apps),
)
