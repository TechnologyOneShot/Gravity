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

object GravityColors {
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF60A5FA)
    val Purple = Color(0xFFA78BFA)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Bg = Color(0xFF0B0F17)
    val Surface = Color(0xFF111827)
    val SurfaceHigh = Color(0xFF1A2232)
    val Outline = Color(0xFF263042)
    val Text = Color(0xFFE5E7EB)
    val Muted = Color(0xFF94A3B8)
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
    punctuation = Color(0xFF89DDFF), heading = Color(0xFF22D3EE), plain = Color(0xFFE5E7EB), gutter = Color(0xFF4B5563),
    background = Color(0xFF0D1320), bracketMatch = Color(0x5522D3EE), search = Color(0x66FBBF24),
)

val LightCode = CodeColors(
    keyword = Color(0xFF7C3AED), type = Color(0xFF1D4ED8), string = Color(0xFF15803D), number = Color(0xFFC2410C),
    comment = Color(0xFF6B7280), annotation = Color(0xFFB45309), tag = Color(0xFF0369A1), attribute = Color(0xFFB45309),
    punctuation = Color(0xFF334155), heading = Color(0xFF0E7490), plain = Color(0xFF111827), gutter = Color(0xFF9CA3AF),
    background = Color(0xFFF8FAFC), bracketMatch = Color(0x4422D3EE), search = Color(0x66FBBF24),
)

val LocalCodeColors = staticCompositionLocalOf { DarkCode }

private val DarkScheme = darkColorScheme(
    primary = GravityColors.Cyan,
    onPrimary = Color(0xFF00313A),
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Color(0xFFBDF4FF),
    secondary = GravityColors.Purple,
    onSecondary = Color(0xFF2A1659),
    secondaryContainer = Color(0xFF2E2552),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = GravityColors.Blue,
    background = GravityColors.Bg,
    onBackground = GravityColors.Text,
    surface = GravityColors.Surface,
    onSurface = GravityColors.Text,
    surfaceVariant = GravityColors.SurfaceHigh,
    onSurfaceVariant = GravityColors.Muted,
    surfaceContainer = GravityColors.Surface,
    surfaceContainerHigh = GravityColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF212B3D),
    surfaceContainerLow = Color(0xFF0F1521),
    outline = GravityColors.Outline,
    outlineVariant = Color(0xFF1E2636),
    error = GravityColors.Red,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0891B2),
    secondary = Color(0xFF7C3AED),
    tertiary = Color(0xFF2563EB),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFF3F8),
)

val MonoFamily = FontFamily.Monospace

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
