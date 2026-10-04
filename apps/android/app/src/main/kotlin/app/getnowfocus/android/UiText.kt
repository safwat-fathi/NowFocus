package app.getnowfocus.android

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Words that depend on the app language. Pure functions (BlockCopy, DurationFormat, ...) can't call a
 * Context - their tests are plain JVM - so they return which string and which numbers, and the screen
 * or notifier that shows it calls [resolve]. Tests then assert the id and args, not English.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** [parts] in order, between [separator] (a string resource, because Arabic joins with "،"). */
    data class Joined(val parts: List<UiText>, @StringRes val separator: Int) : UiText

    /** Text that is already final: a user's own name, or a server message we can't translate. */
    data class Raw(val text: String) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/**
 * [context] must already speak the app language: an activity (wrapped in attachBaseContext) or `context.localized()`.
 * An arg that is itself a [UiText] is resolved first, so "End this session now? %1$s left." can take a duration.
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *resolveArgs(args, context))
    is UiText.Plural -> context.resources.getQuantityString(id, count, *resolveArgs(args, context))
    is UiText.Joined -> parts.joinToString(context.getString(separator)) { it.resolve(context) }
    is UiText.Raw -> text
}

private fun resolveArgs(args: List<Any>, context: Context): Array<Any> =
    args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

@Composable
fun UiText.text(): String = resolve(LocalContext.current)
