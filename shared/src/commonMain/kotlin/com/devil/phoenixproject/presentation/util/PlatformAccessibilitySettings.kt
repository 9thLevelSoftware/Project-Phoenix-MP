package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

data class PlatformAccessibilitySettings(
    val boldTextEnabled: Boolean = false,
)

val LocalPlatformAccessibilitySettings = compositionLocalOf { PlatformAccessibilitySettings() }

@Composable
expect fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings
