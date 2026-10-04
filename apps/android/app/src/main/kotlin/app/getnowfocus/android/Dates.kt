package app.getnowfocus.android

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The locale the screen speaks. DateUtils.formatDateTime and Locale.getDefault() follow the device, not the
 * language picked in Settings, so every date and time shown goes through here. java.time patterns print
 * Latin digits whatever the locale, which is what the Arabic app wants.
 */
fun Context.appLocale(): Locale = resources.configuration.locales[0]

private fun Context.format(millis: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, appLocale()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun Context.timePattern() = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"

/** "Mon, Oct 5" */
fun formatDay(context: Context, millis: Long): String = context.format(millis, "EEE, MMM d")

/** "3:45 PM", or "15:45" on a 24-hour phone. */
fun formatTime(context: Context, millis: Long): String = context.format(millis, context.timePattern())

/** "Oct 9, 3:45 PM": a date as well as a time, because a Commitment Shield block can be days out. */
fun formatDayTime(context: Context, millis: Long): String = context.format(millis, "MMM d, " + context.timePattern())
