package com.devil.phoenixproject.ui.theme

/** Applies LIGHT/DARK/SYSTEM to the Android application night mode. No-op on iOS. */
expect fun applyPlatformNightMode(themeMode: ThemeMode)
