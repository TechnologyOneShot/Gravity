package com.gravity.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gravity.ide.data.settings.ThemeMode

/**
 * Warm, calm palette (dark). "Cyan" is the accent everywhere in the app (a warm terracotta);
 * the old names are kept so every screen picks up the new look.
 */
object GravityColors {
    val Accent = Color(0xFFD97757)
    val Cyan = Accent
    val Blue = Color(0xFF8AB4F8)
    val Purple = Color(0xFFC4A7E7)
    val Green = Color(0xFF8BC48A)
    val Amber = Color(0xFFE5B567)
    val Red = Color(0xFFE5736A)
    val Bg = Color(0xFF1F1E1D)
    val Surface = Color(0xFF262624)
    val SurfaceHigh = Color(0xFF30302E)
    val Outline = Color(0xFF3D3D3A)
    val Text = Color(0xFFEDEBE4)
    val Muted = Color(0xFFA3A097)
}

/** Syntax colours used by the editor (separate from Material roles). */
@Immutable
data class CodeColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val punctuation: Color,
    val heading: Color,
    val plain: Color,
    val gutter: Color,
    val background: Color,
    val bracketMatch: Color,
    val search: Color,
)

val DarkCode = CodeColors(
    keyword = Color(0xFFC792EA), type = Color(0xFF82AAFF), string = Color(0xFFC3E88D), number = Color(0xFFF78C6C),
    comment = Color(0xFF64748B), annotation = Color(0xFFFFCB6B), tag = Color(0xFF89DDFF), attribute = Color(0xFFFFCB6B),
    punctuation = Color(0xFF89DDFF), heading = Color(0xFFD97757), plain = Color(0xFFEDEBE4), gutter = Color(0xFF5A5852),
    background = Color(0xFF1A1918), bracketMatch = Color(0x55D97757), search = Color(0x66E5B567),
)

val LightCode = CodeColors(
    keyword = Color(0xFF7C3AED), type = Color(0xFF1D4ED8), string = Color(0xFF15803D), number = Color(0xFFC2410C),
    comment = Color(0xFF6B7280), annotation = Color(0xFFB45309), tag = Color(0xFF0369A1), attribute = Color(0xFFB45309),
    punctuation = Color(0xFF334155), heading = Color(0xFF0E7490), plain = Color(0xFF111827), gutter = Color(0xFF9CA3AF),
    background = Color(0xFFF8FAFC), bracketMatch = Color(0x4422D3EE), search = Color(0x66FBBF24),
)

val LocalCodeColors = staticCompositionLocalOf { DarkCode }

private val DarkScheme = darkColorScheme(
    primary = GravityColors.Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A2E24),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = GravityColors.Purple,
    onSecondary = Color(0xFF2B2140),
    secondaryContainer = Color(0xFF3A3346),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = GravityColors.Blue,
    background = GravityColors.Bg,
    onBackground = GravityColors.Text,
    surface = GravityColors.Surface,
    onSurface = GravityColors.Text,
    surfaceVariant = GravityColors.SurfaceHigh,
    onSurfaceVariant = GravityColors.Muted,
    surfaceContainer = GravityColors.Surface,
    surfaceContainerHigh = GravityColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF383835),
    surfaceContainerLow = Color(0xFF232221),
    outline = GravityColors.Outline,
    outlineVariant = Color(0xFF33332F),
    error = GravityColors.Red,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC96442),
    secondary = Color(0xFF7C5CC4),
    tertiary = Color(0xFF2563EB),
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EEE6),
)

val MonoFamily = FontFamily.Monospace

/** Serif face for large, calm headings (greetings, empty states). */
val DisplayFamily = FontFamily.Serif

private val GravityTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.3.sp),
    )
}

val CodeTextStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun GravityTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCodeColors provides if (dark) DarkCode else LightCode) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = GravityTypography, content = content)
    }
}
