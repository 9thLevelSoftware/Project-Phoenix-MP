package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * iOS: delegate to the platform `isSystemInDarkTheme()` signal.
 * iOS does not have the same transient-signal issue as Android's Compose bridge;
 * the SwiftUI/UIViewController lifecycle keeps the appearance stable.
 */
@Composable
actual fun rememberPlatformSystemDark(): Boolean = isSystemInDarkTheme()
