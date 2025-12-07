package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurpleDark,               // Desaturated purple reduces eye strain
    onPrimary = TextPrimary,                   // White text on purple
    primaryContainer = PurpleAccentDark,       // Desaturated purple container
    onPrimaryContainer = TextPrimary,

    secondary = SecondaryPurpleDark,           // Desaturated deeper purple
    onSecondary = TextPrimary,
    secondaryContainer = SecondaryPurpleDark,
    onSecondaryContainer = TextPrimary,

    tertiary = TertiaryPurpleDark,             // Soft purple for highlights
    onTertiary = TextPrimary,
    tertiaryContainer = TertiaryPurpleDark,
    onTertiaryContainer = TextPrimary,

    background = SurfaceContainerDark,         // 2025: Use container as background
    onBackground = TextPrimary,

    surface = SurfaceContainerDark,            // 2025: Tonal surface
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainerHighDark,
    onSurfaceVariant = TextSecondary,

    // 2025 Material Design Expressive Surface Container Roles (Dark)
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,           // Main screens
    surfaceContainerHigh = SurfaceContainerHighDark,   // Cards
    surfaceContainerHighest = SurfaceContainerHighestDark, // Modals/Dialogs

    error = ErrorRed,
    onError = TextPrimary,

    outline = TextTertiary,
    outlineVariant = TextDisabled
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlueLight,              // Teal/cyan for light mode buttons
    onPrimary = ColorLightSurface,           // White text on teal buttons
    primaryContainer = TertiaryBlueLight.copy(alpha = 0.2f),  // Light teal container
    onPrimaryContainer = ColorOnLightBackground,  // Dark text on light container

    secondary = SecondaryBlueLight,          // Deeper teal for secondary elements
    onSecondary = ColorLightSurface,         // White text
    secondaryContainer = TertiaryBlueLight.copy(alpha = 0.15f),
    onSecondaryContainer = ColorOnLightBackground,  // Dark text

    tertiary = TertiaryBlueLight,            // Bright cyan for highlights
    onTertiary = ColorLightSurface,          // White text
    tertiaryContainer = TertiaryBlueLight.copy(alpha = 0.1f),
    onTertiaryContainer = ColorOnLightBackground,  // Dark text

    background = SurfaceContainerLight,      // 2025: Use container as background
    onBackground = ColorOnLightBackground,

    surface = SurfaceContainerLight,         // 2025: Tonal surface
    onSurface = ColorOnLightSurface,
    surfaceVariant = SurfaceContainerHighLight,
    onSurfaceVariant = ColorOnLightSurfaceVariant,

    // 2025 Material Design Expressive Surface Container Roles (Light)
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,          // Main screens
    surfaceContainerHigh = SurfaceContainerHighLight,  // Cards
    surfaceContainerHighest = SurfaceContainerHighestLight, // Modals/Dialogs

    error = ErrorRed,
    onError = ColorLightSurface,            // White text on red error

    outline = ColorOnLightSurfaceVariant.copy(alpha = 0.6f),
    outlineVariant = ColorOnLightSurfaceVariant.copy(alpha = 0.4f)
)

@Composable
fun VitruvianTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = ExpressiveShapes, // Material 3 Expressive: More rounded shapes
        content = content
    )
}