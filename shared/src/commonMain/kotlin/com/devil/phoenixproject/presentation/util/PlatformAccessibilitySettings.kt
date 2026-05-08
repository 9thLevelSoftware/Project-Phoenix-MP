package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable

data class PlatformAccessibilitySettings(
    val boldTextEnabled: Boolean = false,
)

@Composable
expect fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings
