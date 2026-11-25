package com.gravity.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gravity.ide.data.settings.ThemeMode

/** The warm palette in its dark and light versions. */
private object Dark {
    val Accent = Color(0xFFD97757)
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

/** Day mode: warm off-white like paper, dark text, accents dark enough to read on white. */
private object Light {
    val Accent = Color(0xFFC15F3C)
    val Blue = Color(0xFF1F5FD1)
    val Purple = Color(0xFF6B4FB8)
    val Green = Color(0xFF2E7D32)
    val Amber = Color(0xFF9A6200)
    val Red = Color(0xFFC0392B)
    val Bg = Color(0xFFFAF9F5)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF0EEE6)
    val Outline = Color(0xFFDEDBD1)
    val Text = Color(0xFF1F1E1D)
    val Muted = Color(0xFF6B6962)
}

/**
 * Colours used across the app. Each follows the current day/night mode (set by [GravityTheme]),
 * so a screen that uses them reads well in both. "Cyan" is the accent everywhere (a warm
 * terracotta); the old names are kept so every screen picks up the look.
 */
object GravityColors {
    /** Set by [GravityTheme]. A state, so screens redraw when the mode changes. */
    internal var isDark by mutableStateOf(true)

    val Accent: Color get() = if (isDark) Dark.Accent else Light.Accent
    val Cyan: Color get() = Accent
    val Blue: Color get() = if (isDark) Dark.Blue else Light.Blue
    val Purple: Color get() = if (isDark) Dark.Purple else Light.Purple
    val Green: Color get() = if (isDark) Dark.Green else Light.Green
    val Amber: Color get() = if (isDark) Dark.Amber else Light.Amber
    val Red: Color get() = if (isDark) Dark.Red else Light.Red
    val Bg: Color get() = if (isDark) Dark.Bg else Light.Bg
    val Surface: Color get() = if (isDark) Dark.Surface else Light.Surface
    val SurfaceHigh: Color get() = if (isDark) Dark.SurfaceHigh else Light.SurfaceHigh
    val Outline: Color get() = if (isDark) Dark.Outline else Light.Outline
    val Text: Color get() = if (isDark) Dark.Text else Light.Text
    val Muted: Color get() = if (isDark) Dark.Muted else Light.Muted
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
    primary = Dark.Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A2E24),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Dark.Purple,
    onSecondary = Color(0xFF2B2140),
    secondaryContainer = Color(0xFF3A3346),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Dark.Blue,
    background = Dark.Bg,
    onBackground = Dark.Text,
    surface = Dark.Surface,
    onSurface = Dark.Text,
    surfaceVariant = Dark.SurfaceHigh,
    onSurfaceVariant = Dark.Muted,
    surfaceContainer = Dark.Surface,
    surfaceContainerHigh = Dark.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF383835),
    surfaceContainerLow = Color(0xFF232221),
    surfaceContainerLowest = Color(0xFF1A1918),
    outline = Dark.Outline,
    outlineVariant = Color(0xFF33332F),
    error = Dark.Red,
)

private val LightScheme = lightColorScheme(
    primary = Light.Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8E1D7),
    onPrimaryContainer = Color(0xFF4A1F10),
    secondary = Light.Purple,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6F8),
    onSecondaryContainer = Color(0xFF2B2140),
    tertiary = Light.Blue,
    onTertiary = Color(0xFFFFFFFF),
    background = Light.Bg,
    onBackground = Light.Text,
    surface = Light.Bg,
    onSurface = Light.Text,
    surfaceVariant = Light.SurfaceHigh,
    onSurfaceVariant = Light.Muted,
    surfaceTint = Color.Transparent,
    surfaceContainer = Color(0xFFF5F3EC),
    surfaceContainerHigh = Light.SurfaceHigh,
    surfaceContainerHighest = Color(0xFFE9E6DC),
    surfaceContainerLow = Color(0xFFF7F5EF),
    surfaceContainerLowest = Light.Surface,
    outline = Light.Outline,
    outlineVariant = Color(0xFFE8E5DC),
    error = Light.Red,
    onError = Color(0xFFFFFFFF),
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
    // Before any screen reads a colour, so the first frame already uses the right mode.
    if (GravityColors.isDark != dark) GravityColors.isDark = dark
    // Status and navigation bar icons follow Gravity's own mode (dark icons in day mode), not only the system's.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCodeColors provides if (dark) DarkCode else LightCode) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = GravityTypography, content = content)
    }
}
