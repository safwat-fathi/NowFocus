package app.getnowfocus.android

import android.content.Context

// ponytail: identity until the language switch lands (step 1c); every non-Compose string lookup already goes through it.
fun Context.localized(): Context = this
