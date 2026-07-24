package com.devil.phoenixproject.ui.theme

/**
 * Platform hook to apply status bar icon appearance after a theme change.
 * On Android, this sets `isAppearanceLightStatusBars` based on [isDark].
 * On iOS, this is a no-op.
 */
expect fun ApplyStatusBarAppearance(isDark: Boolean)
