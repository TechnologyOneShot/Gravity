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

/** One set of app colours. */
private class Palette(
    val dark: Boolean,
    val accent: Color, val onAccent: Color,
    val blue: Color, val purple: Color, val green: Color, val amber: Color, val red: Color,
    val bg: Color, val surface: Color, val surfaceHigh: Color, val outline: Color, val text: Color, val muted: Color,
)

/** Night: warm charcoal with a terracotta accent. */
private val Dark = Palette(
    dark = true, accent = Color(0xFFD97757), onAccent = Color.White,
    blue = Color(0xFF8AB4F8), purple = Color(0xFFC4A7E7), green = Color(0xFF8BC48A), amber = Color(0xFFE5B567), red = Color(0xFFE5736A),
    bg = Color(0xFF1F1E1D), surface = Color(0xFF262624), surfaceHigh = Color(0xFF30302E), outline = Color(0xFF3D3D3A),
    text = Color(0xFFEDEBE4), muted = Color(0xFFA3A097),
)

/** Day: warm off-white like paper, dark text, accents dark enough to read on white. */
private val Light = Palette(
    dark = false, accent = Color(0xFFC15F3C), onAccent = Color.White,
    blue = Color(0xFF1F5FD1), purple = Color(0xFF6B4FB8), green = Color(0xFF2E7D32), amber = Color(0xFF9A6200), red = Color(0xFFC0392B),
    bg = Color(0xFFFAF9F5), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF0EEE6), outline = Color(0xFFDEDBD1),
    text = Color(0xFF1F1E1D), muted = Color(0xFF6B6962),
)

/** Neon black: true black with glowing neon cyan (black text on cyan buttons). */
private val Neon = Palette(
    dark = true, accent = Color(0xFF00E5FF), onAccent = Color.Black,
    blue = Color(0xFF4DA3FF), purple = Color(0xFFC77DFF), green = Color(0xFF39FF88), amber = Color(0xFFFFD60A), red = Color(0xFFFF4D6D),
    bg = Color(0xFF000000), surface = Color(0xFF0B0B10), surfaceHigh = Color(0xFF16161F), outline = Color(0xFF2A2A3A),
    text = Color(0xFFF4F4FA), muted = Color(0xFF9C9CB4),
)

/**
 * Colours used across the app. Each follows the current theme (set by [GravityTheme]), so a
 * screen that uses them reads well in all of them. "Cyan" is the accent everywhere; the old
 * names are kept so every screen picks up the look.
 */
object GravityColors {
    /** Set by [GravityTheme]. A state, so screens redraw when the theme changes. */
    private var palette by mutableStateOf(Neon)

    internal fun use(mode: ThemeMode, systemDark: Boolean) {
        val next = when (mode) {
            ThemeMode.DARK -> Dark
            ThemeMode.LIGHT -> Light
            ThemeMode.NEON -> Neon
            ThemeMode.SYSTEM -> if (systemDark) Neon else Light
        }
        if (palette !== next) palette = next
    }

    val isDark: Boolean get() = palette.dark
    val Accent: Color get() = palette.accent
    /** Text and icons drawn on an [Accent] background. */
    val OnAccent: Color get() = palette.onAccent
    val Cyan: Color get() = Accent
    val Blue: Color get() = palette.blue
    val Purple: Color get() = palette.purple
    val Green: Color get() = palette.green
    val Amber: Color get() = palette.amber
    val Red: Color get() = palette.red
    val Bg: Color get() = palette.bg
    val Surface: Color get() = palette.surface
    val SurfaceHigh: Color get() = palette.surfaceHigh
    val Outline: Color get() = palette.outline
    val Text: Color get() = palette.text
    val Muted: Color get() = palette.muted
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
    primary = Dark.accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A2E24),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Dark.purple,
    onSecondary = Color(0xFF2B2140),
    secondaryContainer = Color(0xFF3A3346),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Dark.blue,
    background = Dark.bg,
    onBackground = Dark.text,
    surface = Dark.surface,
    onSurface = Dark.text,
    surfaceVariant = Dark.surfaceHigh,
    onSurfaceVariant = Dark.muted,
    surfaceContainer = Dark.surface,
    surfaceContainerHigh = Dark.surfaceHigh,
    surfaceContainerHighest = Color(0xFF383835),
    surfaceContainerLow = Color(0xFF232221),
    surfaceContainerLowest = Color(0xFF1A1918),
    outline = Dark.outline,
    outlineVariant = Color(0xFF33332F),
    error = Dark.red,
)

private val LightScheme = lightColorScheme(
    primary = Light.accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8E1D7),
    onPrimaryContainer = Color(0xFF4A1F10),
    secondary = Light.purple,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6F8),
    onSecondaryContainer = Color(0xFF2B2140),
    tertiary = Light.blue,
    onTertiary = Color(0xFFFFFFFF),
    background = Light.bg,
    onBackground = Light.text,
    surface = Light.bg,
    onSurface = Light.text,
    surfaceVariant = Light.surfaceHigh,
    onSurfaceVariant = Light.muted,
    surfaceTint = Color.Transparent,
    surfaceContainer = Color(0xFFF5F3EC),
    surfaceContainerHigh = Light.surfaceHigh,
    surfaceContainerHighest = Color(0xFFE9E6DC),
    surfaceContainerLow = Color(0xFFF7F5EF),
    surfaceContainerLowest = Light.surface,
    outline = Light.outline,
    outlineVariant = Color(0xFFE8E5DC),
    error = Light.red,
    onError = Color(0xFFFFFFFF),
)

private val NeonScheme = darkColorScheme(
    primary = Neon.accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = Color(0xFF9EF4FF),
    secondary = Neon.purple,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A1840),
    onSecondaryContainer = Color(0xFFEBD5FF),
    tertiary = Neon.green,
    onTertiary = Color.Black,
    background = Neon.bg,
    onBackground = Neon.text,
    surface = Neon.bg,
    onSurface = Neon.text,
    surfaceVariant = Neon.surfaceHigh,
    onSurfaceVariant = Neon.muted,
    surfaceTint = Color.Transparent,
    surfaceContainer = Neon.surface,
    surfaceContainerHigh = Neon.surfaceHigh,
    surfaceContainerHighest = Color(0xFF1E1E2A),
    surfaceContainerLow = Color(0xFF07070A),
    surfaceContainerLowest = Neon.bg,
    outline = Neon.outline,
    outlineVariant = Color(0xFF1F1F2B),
    error = Neon.red,
    onError = Color.Black,
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
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.DARK, ThemeMode.NEON -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val neon = mode == ThemeMode.NEON || (mode == ThemeMode.SYSTEM && systemDark)
    // Before any screen reads a colour, so the first frame already uses the right theme.
    GravityColors.use(mode, systemDark)
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
        MaterialTheme(colorScheme = if (neon) NeonScheme else if (dark) DarkScheme else LightScheme, typography = GravityTypography, content = content)
    }
}
