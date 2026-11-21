package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIUserInterfaceStyle

/**
 * iOS actual: synchronizes root UIViewController interface style and triggers a
 * status bar appearance update to match the active Phoenix theme.
 */
@Composable
actual fun ApplyStatusBarAppearance(isDark: Boolean) {
    SideEffect {
        val style = when (currentIosThemeMode) {
            ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.DARK, ThemeMode.LIGHT -> {
                if (isDark) {
                    UIUserInterfaceStyle.UIUserInterfaceStyleDark
                } else {
                    UIUserInterfaceStyle.UIUserInterfaceStyleLight
                }
            }
        }
        applyRootUserInterfaceStyle(style)
    }
    LaunchedEffect(isDark) {
        refreshRootStatusBarAppearance()
    }
}
