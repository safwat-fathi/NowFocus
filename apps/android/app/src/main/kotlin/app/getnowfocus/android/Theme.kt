package app.getnowfocus.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Modernist design tokens, ported 1:1 from the Claude Design system's
 * styles.css (_ds/modernist-.../styles.css). Sharp corners (radius 0
 * everywhere), rule-line layout instead of cards/shadows, one loud accent.
 */
object NowFocusColors {
    val bg = Color(0xFFF3F2F2)
    val surface = Color(0xFFEAE9E9)
    val text = Color(0xFF201E1D)
    val accent = Color(0xFFEC3013)
    val divider = text.copy(alpha = 0.4f)

    val neutral100 = Color(0xFFF8F4F4)
    val neutral200 = Color(0xFFEAE7E7)
    val neutral300 = Color(0xFFD7D3D3)
    val neutral400 = Color(0xFFBAB6B6)
    val neutral500 = Color(0xFF9B9797)
    val neutral600 = Color(0xFF7D7979)
    val neutral700 = Color(0xFF605D5D)
    val neutral800 = Color(0xFF444141)
    val neutral900 = Color(0xFF2D2B2B)

    val accent100 = Color(0xFFFFF2EF)
    val accent400 = Color(0xFFFF9783)
    val accent600 = Color(0xFFDD2B0F)
    val accent700 = Color(0xFFAE1800)
    val accent800 = Color(0xFF7C1405)
}

object NowFocusSpace {
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s6: Dp = 24.dp
    val s8: Dp = 32.dp
}

// Archivo ships from Google Fonts only as a variable font (weight+width axes);
// pick fixed weights out of it with FontVariation rather than bundling three
// separate static files.
@OptIn(ExperimentalTextApi::class)
private fun archivoWeight(weight: FontWeight, value: Int) = Font(
    resId = R.font.archivo_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(value)),
)

val ArchivoRegular = FontFamily(archivoWeight(FontWeight.Normal, 400))
val ArchivoSemiBold = FontFamily(archivoWeight(FontWeight.SemiBold, 600))
val ArchivoBlack = FontFamily(archivoWeight(FontWeight.ExtraBold, 800))

/** Arabic letters join, so any letter-spacing pulls the word apart: it is dropped when the app speaks Arabic. */
@Composable
private fun spacing(sp: TextUnit): TextUnit = if (LocalContext.current.appLocale().language == "ar") 0.sp else sp

@Composable
fun kickerStyle(color: Color = NowFocusColors.accent700) = TextStyle(
    fontFamily = ArchivoSemiBold,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = spacing(1.1.sp),
    color = color,
)

@Composable
fun headingStyle(size: TextUnit, color: Color = NowFocusColors.text) = TextStyle(
    fontFamily = ArchivoBlack,
    fontWeight = FontWeight.ExtraBold,
    fontSize = size,
    letterSpacing = spacing((-0.02).sp * (size.value / 16f)),
    color = color,
)

/** Wraps Material3 (only AlertDialog is left) with Modernist colors, no elevation, zero corners. */
@Composable
fun NowFocusTheme(content: @Composable () -> Unit) {
    val square = RoundedCornerShape(0.dp)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = NowFocusColors.bg,
            surface = NowFocusColors.surface,
            surfaceContainerHigh = NowFocusColors.bg,
            onBackground = NowFocusColors.text,
            onSurface = NowFocusColors.text,
            primary = NowFocusColors.accent,
            onPrimary = NowFocusColors.bg,
            error = NowFocusColors.accent700,
            outline = NowFocusColors.divider,
        ),
        shapes = Shapes(square, square, square, square, square),
    ) {
        // The window's own direction comes from the system configuration (the device language), not from our
        // wrapped context, so the app language sets it here. Dialogs read the same local.
        val rtl = LocalContext.current.appLocale().language == "ar"
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
    }
}

/**
 * Design-system input (windows `.input`, iOS `nfField`): square, 1px rule border that turns accent on focus
 * and accent700 on error. The label rides above the box instead of floating.
 */
@Composable
fun NowFocusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = TextStyle(fontFamily = ArchivoRegular, fontSize = 15.sp, color = NowFocusColors.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = style,
        cursorBrush = SolidColor(NowFocusColors.accent),
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        // Inside decorationBox so the label merges into the field's semantics (TalkBack names the field).
        decorationBox = { inner ->
            Column {
                if (label != null) {
                    Text(label.uppercase(), style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(bottom = NowFocusSpace.s1))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(NowFocusColors.surface)
                        .border(1.dp, if (isError) NowFocusColors.accent700 else if (focused) NowFocusColors.accent else NowFocusColors.divider)
                        .padding(NowFocusSpace.s3),
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = NowFocusColors.neutral600))
                    inner()
                }
            }
        },
    )
}

@Composable
fun SectionRule(modifier: Modifier = Modifier, thick: Boolean = false, color: Color = NowFocusColors.divider) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (thick) 2.dp else 1.dp)
            .background(color),
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(if (enabled) NowFocusColors.accent else NowFocusColors.neutral400)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NowFocusSpace.s4, vertical = NowFocusSpace.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = NowFocusColors.bg))
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .border(1.dp, NowFocusColors.divider)
            .clickable(onClick = onClick)
            .padding(horizontal = NowFocusSpace.s3, vertical = NowFocusSpace.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = ArchivoBlack, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = NowFocusColors.text))
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick).padding(vertical = NowFocusSpace.s2)) {
        Text(text, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NowFocusColors.accent))
    }
}

/** A yes/no question before something that can't be undone. Same shape as the Delete account dialog. */
@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = headingStyle(20.sp)) },
        text = { Text(message, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800)) },
        confirmButton = { PrimaryButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { GhostButton(stringResource(R.string.cancel), onClick = onDismiss) },
    )
}

@Composable
fun TagPill(text: String, modifier: Modifier = Modifier, accent: Boolean = true) {
    Box(
        modifier
            .background(if (accent) NowFocusColors.accent100 else NowFocusColors.neutral100)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = TextStyle(
                fontFamily = ArchivoRegular,
                fontSize = 11.sp,
                letterSpacing = spacing(0.2.sp),
                color = if (accent) NowFocusColors.accent800 else NowFocusColors.neutral800,
            ),
        )
    }
}

/** Rectangular on/off track — the design has no pill switches. [dark] is the variant for the dark Bedtime surface. */
@Composable
fun ToggleRow(label: String, sub: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .padding(vertical = NowFocusSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Column {
                Text(label, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (dark) NowFocusColors.bg else NowFocusColors.text))
                Text(sub, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = if (dark) NowFocusColors.neutral400 else NowFocusColors.neutral700))
            }
        }
        Box(
            Modifier
                .size(width = 46.dp, height = 26.dp)
                .border(2.dp, if (on) NowFocusColors.accent else NowFocusColors.neutral500)
                .background(if (on) NowFocusColors.accent else Color.Transparent),
        ) {
            Box(
                Modifier
                    .padding(start = if (on) 23.dp else 3.dp, top = 3.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (on) NowFocusColors.bg else if (dark) NowFocusColors.neutral400 else NowFocusColors.neutral600),
            )
        }
    }
}

@Composable
fun <T> SegmentedControl(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    // Min intrinsic height + fillMaxHeight: every segment is as tall as the tallest label (Arabic glyphs are taller).
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, NowFocusColors.divider), horizontalArrangement = Arrangement.SpaceEvenly) {
        options.forEachIndexed { i, (label, value) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (i > 0) Modifier.border(androidx.compose.foundation.BorderStroke(1.dp, NowFocusColors.divider)) else Modifier)
                    .background(if (isSelected) NowFocusColors.accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = NowFocusSpace.s2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = TextStyle(
                        fontFamily = ArchivoSemiBold,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = if (isSelected) NowFocusColors.bg else NowFocusColors.text,
                    ),
                )
            }
        }
    }
}
