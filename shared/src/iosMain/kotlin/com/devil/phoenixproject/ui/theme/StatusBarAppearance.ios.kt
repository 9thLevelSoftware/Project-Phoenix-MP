package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable

/**
 * iOS actual: no-op. iOS manages status bar appearance per-ViewController.
 */
@Composable
actual fun ApplyStatusBarAppearance(isDark: Boolean) {
    // No-op on iOS
}
