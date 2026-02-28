package com.devil.phoenixproject.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.devil.phoenixproject.ui.theme.ThemeMode as SharedThemeMode
import com.devil.phoenixproject.ui.theme.VitruvianTheme as SharedVitruvianTheme

/**
 * Android-specific theme wrapper.
 * Delegates to shared theme and configures status bar appearance.
 * Note: enableEdgeToEdge() in MainActivity handles status bar coloring.
 */
@Suppress("UNUSED_PARAMETER") // dynamicColor kept for API compatibility, intentionally disabled
@Composable
fun VitruvianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled - breaks brand identity
    colorBlindMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val themeMode = if (darkTheme) SharedThemeMode.DARK else SharedThemeMode.LIGHT

    SharedVitruvianTheme(themeMode = themeMode, colorBlindMode = colorBlindMode) {
        val view = LocalView.current

        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                // Set status bar icon colors (light icons for dark theme, dark icons for light theme)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }

        content()
    }
}
