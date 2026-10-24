package com.mutqan.hadith.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = EmeraldMist,
    onPrimaryContainer = EmeraldDeep,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = GoldSoft,
    onSecondaryContainer = GoldDeep,
    tertiary = InkSoft,
    background = Parchment,
    onBackground = Ink,
    surface = Parchment,
    onSurface = Ink,
    surfaceVariant = ParchmentDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = ParchmentRaised,
    surfaceContainerLow = ParchmentRaised,
    surfaceContainer = ParchmentRaised,
    surfaceContainerHigh = ParchmentDim,
    surfaceContainerHighest = ParchmentDim,
    outline = Sage,
    outlineVariant = Color(0xFFD8D3C4),
    error = WrongLight,
    errorContainer = WrongContainerLight,
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = EmeraldLightDeep,
    primaryContainer = Color(0xFF0E4A3A),
    onPrimaryContainer = EmeraldMist,
    secondary = GoldLight,
    onSecondary = GoldDeep,
    secondaryContainer = Color(0xFF4A3A0E),
    onSecondaryContainer = GoldSoft,
    tertiary = NightInkSoft,
    background = NightSurface,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightDim,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = NightSurface,
    surfaceContainerLow = NightRaised,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightDim,
    surfaceContainerHighest = NightDim,
    outline = Color(0xFF6F7A73),
    outlineVariant = Color(0xFF34423B),
    error = WrongDark,
    errorContainer = WrongContainerDark,
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Colors the Material scheme has no slot for: drill feedback and the mastery scale. */
@Immutable
data class MutqanColors(
    val correct: Color,
    val correctContainer: Color,
    val wrong: Color,
    val wrongContainer: Color,
    val masteryLow: Color,
    val masteryMid: Color,
    val masteryHigh: Color,
    val heatStrong: Color,
    val heatFair: Color,
    val heatFragile: Color,
)

private val LightExtras = MutqanColors(
    correct = CorrectLight,
    correctContainer = CorrectContainerLight,
    wrong = WrongLight,
    wrongContainer = WrongContainerLight,
    masteryLow = Color(0xFFB3571E),
    masteryMid = Gold,
    masteryHigh = Emerald,
    heatStrong = HeatStrongLight,
    heatFair = HeatFairLight,
    heatFragile = HeatFragileLight,
)

private val DarkExtras = MutqanColors(
    correct = CorrectDark,
    correctContainer = CorrectContainerDark,
    wrong = WrongDark,
    wrongContainer = WrongContainerDark,
    masteryLow = Color(0xFFFFA06B),
    masteryMid = GoldLight,
    masteryHigh = EmeraldLight,
    heatStrong = HeatStrongDark,
    heatFair = HeatFairDark,
    heatFragile = HeatFragileDark,
)

private val LocalMutqanColors = staticCompositionLocalOf { LightExtras }

object MutqanTheme {
    val colors: MutqanColors
        @Composable @ReadOnlyComposable get() = LocalMutqanColors.current
}

/**
 * The app is Arabic-only, so layout is forced right-to-left whatever the device locale is.
 * Brand colors are fixed (no dynamic color) to keep the emerald/gold identity.
 */
@Composable
fun MutqanHadithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalMutqanColors provides if (darkTheme) DarkExtras else LightExtras,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
