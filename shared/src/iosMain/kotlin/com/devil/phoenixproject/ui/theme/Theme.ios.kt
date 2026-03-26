package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.devil.phoenixproject.ui.theme.ThemeMode as SharedThemeMode
import com.devil.phoenixproject.ui.theme.VitruvianTheme as SharedVitruvianTheme

/**
 * iOS-specific theme wrapper.
 * Delegates to shared theme and can be extended with iOS-specific styling
 * such as status bar appearance and safe area handling.
 * 
 * Note: Compose Multiplatform on iOS handles safe areas automatically,
 * and status bar styling is typically handled at the SwiftUI level.
 * This wrapper exists for parity with Android and future iOS-specific theming needs.
 */
@Composable
fun VitruvianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val themeMode = if (darkTheme) SharedThemeMode.DARK else SharedThemeMode.LIGHT

    SharedVitruvianTheme(themeMode = themeMode) {
        // iOS-specific styling can be added here if needed
        // For example: status bar appearance, safe area insets, etc.
        // Currently, Compose Multiplatform handles these automatically
        content()
    }
}

