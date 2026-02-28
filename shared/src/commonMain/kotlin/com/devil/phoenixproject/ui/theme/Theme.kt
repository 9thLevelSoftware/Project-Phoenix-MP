package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkColorScheme = darkColorScheme(
    // Primary (Orange)
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,

    // Secondary (Gold)
    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,

    // Tertiary (Teal - cool accent)
    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = AshBlueLight,
    onTertiaryContainer = Color.White,

    // Backgrounds & Surfaces (Slate scale)
    background = SurfaceContainerDark,
    onBackground = OnSurfaceDark,

    surface = SurfaceContainerDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerHighDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    // Surface container roles
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceContainerHighestDark,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,

    // Status
    error = SignalError,
    onError = Color.White,

    outline = Slate400,
    outlineVariant = Slate700
)

private val LightColorScheme = lightColorScheme(
    // Primary (Orange)
    primary = PhoenixOrangeLight,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,

    // Secondary (Gold)
    secondary = EmberYellowLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE06F).copy(alpha = 0.3f),
    onSecondaryContainer = Secondary20,

    // Tertiary (Teal)
    tertiary = AshBlueLight,
    onTertiary = Color.White,
    tertiaryContainer = AshBlueDark.copy(alpha = 0.2f),
    onTertiaryContainer = AshBlueLight,

    // Backgrounds & Surfaces
    background = SurfaceContainerLight,
    onBackground = Slate900,

    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = SurfaceContainerHighLight,
    onSurfaceVariant = Slate700,

    // Surface container roles
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,

    // Status
    error = SignalError,
    onError = Color.White,

    outline = Slate400,
    outlineVariant = Slate200
)

@Composable
fun VitruvianTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorBlindMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accessibilityColors = if (colorBlindMode) ColorBlindPalette else StandardPalette

    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = ExpressiveShapes, // Material 3 Expressive: More rounded shapes
    ) {
        CompositionLocalProvider(
            LocalColorBlindMode provides colorBlindMode,
            LocalAccessibilityColors provides accessibilityColors,
            content = content
        )
    }
}