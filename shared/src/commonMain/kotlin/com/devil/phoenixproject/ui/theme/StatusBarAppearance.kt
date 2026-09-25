package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable

/**
 * Platform hook to apply status bar icon appearance after a theme change.
 * On Android, this sets `isAppearanceLightStatusBars` based on [isDark].
 * On iOS, this is a no-op.
 */
@Composable
expect fun ApplyStatusBarAppearance(isDark: Boolean)
