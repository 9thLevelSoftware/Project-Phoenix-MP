package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings {
    return remember { PlatformAccessibilitySettings(boldTextEnabled = false) }
}
