package com.biskyt.mytvplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.ui.AccentPalette

/** Neutral greys so any accent colour works. */
object Palette {
    val background = Color(0xFF0F1114)
    val surface = Color(0xFF181B20)
    val surfaceHigh = Color(0xFF22262D)
    val surfaceLow = Color(0xFF14171B)
    val track = Color(0xFF2A2F37)
    val text = Color(0xFFF1F3F6)
    val textMuted = Color(0xFFA9B0BB)
    val textDim = Color(0xFF7E8692)
    val live = Color(0xFFE25555)
    val warning = Color(0xFFF2C46B)
    val danger = Color(0xFFE58080)
}

@Immutable
data class Accent(val color: Color, val tint: Color, val soft: Color, val onAccent: Color)

val LocalAccent = staticCompositionLocalOf { Accent(Color(AccentPalette.DEFAULT), Color.Transparent, Color.White, Color.Black) }

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) =
    Font(R.font.nunito, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Nunito = FontFamily(nunito(500), nunito(600), nunito(700), nunito(800))

private fun style(size: Int, weight: Int = 700) =
    TextStyle(fontFamily = Nunito, fontSize = size.sp, fontWeight = FontWeight(weight), color = Palette.text)

/** Text sizes for the 10-foot UI; [large] bumps everything up a step for easier reading from the sofa. */
private fun typography(large: Boolean): Typography {
    val b = if (large) 2 else 0
    return Typography(
        displaySmall = style(36 + b, 800),
        headlineLarge = style(30 + b, 800),
        headlineMedium = style(26 + b, 800),
        headlineSmall = style(22 + b, 800),
        titleLarge = style(20 + b, 800),
        titleMedium = style(18 + b, 800),
        titleSmall = style(16 + b, 700),
        bodyLarge = style(17 + b, 600),
        bodyMedium = style(15 + b, 600),
        bodySmall = style(13 + b, 600),
        labelLarge = style(16 + b, 800),
        labelMedium = style(14 + b, 700),
        labelSmall = style(12 + b, 700)
    )
}

private const val DESIGN_WIDTH = 1280f

@Composable
fun MyTvTheme(accentArgb: Int = AccentPalette.DEFAULT, largeText: Boolean = false, content: @Composable () -> Unit) {
    // Derived once per accent change; nothing is recomputed while drawing.
    val accent = remember(accentArgb) {
        AccentPalette.from(accentArgb).let { Accent(Color(it.accent), Color(it.tint), Color(it.soft), Color(it.onAccent)) }
    }
    val colors = remember(accent) {
        darkColorScheme(
            primary = accent.color,
            onPrimary = accent.onAccent,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.textMuted,
            border = accent.color
        )
    }
    val type = remember(largeText) { typography(largeText) }
    // Layouts are designed on a 1280-unit-wide canvas (like the mockups). TVs report ~960 dp (1080p at xhdpi, 720p at
    // tvdpi), so scale density to make 1280 units span the screen: identical look on every TV, no per-size layouts.
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    val scaled = remember(density, widthPx) {
        if (widthPx > 0) Density(widthPx / DESIGN_WIDTH, density.fontScale) else density
    }
    CompositionLocalProvider(LocalAccent provides accent, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}
