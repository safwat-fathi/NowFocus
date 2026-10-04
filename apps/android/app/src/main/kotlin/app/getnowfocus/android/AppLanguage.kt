package app.getnowfocus.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language the user picked in Settings. Kept in SharedPreferences, not the DataStores, because
 * [localized] runs inside attachBaseContext, which needs the answer synchronously.
 *
 * No AppCompat: the app's theme is a platform Material theme, so AppCompatDelegate.setApplicationLocales
 * would mean changing the theme and every activity's base class. Each entry point wraps its context instead.
 */
enum class AppLanguage { SYSTEM, EN, AR;

    companion object {
        private const val PREFS = "nowfocus_ui"
        private const val KEY = "language"

        fun read(context: Context): AppLanguage =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: SYSTEM

        /** commit(), not apply(): the activity is recreated right after, and must see the new value. */
        @Suppress("ApplySharedPref")
        fun write(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.name).commit()
        }
    }
}

/**
 * Arabic with Latin digits (the "nu-latn" extension), as on the website: the sentences stay Arabic, but
 * %d and every number formatter print 0-9 so a countdown never mixes two digit systems.
 */
private val ARABIC: Locale = Locale.forLanguageTag("ar-u-nu-latn")

/**
 * The locale the app speaks, or null to leave the device's own locale alone. Never call Locale.setDefault
 * with an Arabic locale: other code formats data with the default locale.
 */
internal fun AppLanguage.locale(deviceLanguage: String): Locale? = when (this) {
    AppLanguage.EN -> Locale.ENGLISH
    AppLanguage.AR -> ARABIC
    AppLanguage.SYSTEM -> if (deviceLanguage == "ar") ARABIC else null
}

/**
 * This context with the app language applied. Every activity and service wraps its base context with it,
 * and non-UI code (notifiers, the overlay) calls it before looking up a string, so a notification posted
 * from a service still speaks the chosen language.
 */
fun Context.localized(): Context {
    val locale = AppLanguage.read(this).locale(resources.configuration.locales[0].language) ?: return this
    val config = Configuration(resources.configuration).apply {
        setLocale(locale)   // also sets the layout direction
    }
    return createConfigurationContext(config)
}
