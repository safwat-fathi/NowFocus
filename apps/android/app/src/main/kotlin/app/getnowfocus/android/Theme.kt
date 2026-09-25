package app.getnowfocus.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

fun kickerStyle(color: Color = NowFocusColors.accent700) = TextStyle(
    fontFamily = ArchivoSemiBold,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = 1.1.sp,
    color = color,
)

fun headingStyle(size: androidx.compose.ui.unit.TextUnit, color: Color = NowFocusColors.text) = TextStyle(
    fontFamily = ArchivoBlack,
    fontWeight = FontWeight.ExtraBold,
    fontSize = size,
    letterSpacing = (-0.02).sp * (size.value / 16f),
    color = color,
)

/** Wraps Material3 (still used for text fields/dialogs) with Modernist colors, no elevation, zero corners. */
@Composable
fun NowFocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = NowFocusColors.bg,
            surface = NowFocusColors.surface,
            onBackground = NowFocusColors.text,
            onSurface = NowFocusColors.text,
            primary = NowFocusColors.accent,
            onPrimary = NowFocusColors.bg,
            outline = NowFocusColors.divider,
        ),
        content = content,
    )
}

@Composable
fun SectionRule(modifier: Modifier = Modifier, thick: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (thick) 2.dp else 1.dp)
            .background(NowFocusColors.divider),
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
                letterSpacing = 0.2.sp,
                color = if (accent) NowFocusColors.accent800 else NowFocusColors.neutral800,
            ),
        )
    }
}

/** Rectangular on/off track — the design has no pill switches. */
@Composable
fun ToggleRow(label: String, sub: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = NowFocusSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                Text(label, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NowFocusColors.text))
                Text(sub, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
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
                    .background(if (on) NowFocusColors.bg else NowFocusColors.neutral600),
            )
        }
    }
}

@Composable
fun <T> SegmentedControl(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().border(1.dp, NowFocusColors.divider), horizontalArrangement = Arrangement.SpaceEvenly) {
        options.forEachIndexed { i, (label, value) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
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
