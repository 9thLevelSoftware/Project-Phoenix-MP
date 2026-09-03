package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable

/**
 * Returns the platform-owned system dark-mode state, refreshed on lifecycle resume.
 *
 * On Android this resolves application night, Activity night, and Compose night through
 * `resolveSystemDark`, refreshing on `ON_RESUME` so lock/unlock and other configuration
 * changes are captured without relying on a single transient signal.
 *
 * On iOS this still delegates to `isSystemInDarkTheme()`.
 *
 * Use this instead of `isSystemInDarkTheme()` when resolving `ThemeMode.SYSTEM`.
 */
@Composable
expect fun rememberPlatformSystemDark(): Boolean
