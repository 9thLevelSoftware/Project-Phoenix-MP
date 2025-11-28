package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

    background = BackgroundBlack,
    onBackground = TextPrimary,

    surface = SurfaceDarkGrey,
    onSurface = TextPrimary,
    surfaceVariant = CardBackground,
    onSurfaceVariant = TextSecondary,

    // Material 3 Expressive Surface Container Roles (Dark)
    surfaceContainer = SurfaceDarkGrey,        // Base surface
    surfaceContainerHigh = CardBackground,     // Cards
    surfaceContainerHighest = Color(0xFF353535), // Modals/High emphasis

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

    background = ColorLightBackground,
    onBackground = ColorOnLightBackground,

    surface = ColorLightSurface,
    onSurface = ColorOnLightSurface,
    surfaceVariant = ColorLightSurfaceVariant,
    onSurfaceVariant = ColorOnLightSurfaceVariant,

    // Material 3 Expressive Surface Container Roles (Light)
    surfaceContainer = ColorLightSurface,         // Base surface
    surfaceContainerHigh = ColorLightSurfaceVariant, // Cards
    surfaceContainerHighest = Color(0xFFE2E8F0), // Slate-200 for modals

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

// Alias for backward compatibility
@Composable
fun VitruvianProjectPhoenixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) = VitruvianTheme(themeMode, content)