package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable

/**
 * Returns the platform-owned system dark-mode state, refreshed on lifecycle resume.
 *
 * On Android this reads `Configuration.uiMode` (the OS-owned source of truth) and
 * is refreshed on `ON_RESUME` so lock/unlock and other configuration changes are
 * captured without relying on the transient Compose `isSystemInDarkTheme()` signal.
 *
 * On iOS this delegates to `isSystemInDarkTheme()` which is already lifecycle-stable.
 *
 * Use this instead of `isSystemInDarkTheme()` when resolving `ThemeMode.SYSTEM`.
 */
@Composable
expect fun rememberPlatformSystemDark(): Boolean
