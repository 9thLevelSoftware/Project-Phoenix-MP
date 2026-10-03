package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class HealthPermissionSettingsLauncher {
    actual fun openSettings() {
        openAppSettings()
    }
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher = remember {
    HealthPermissionSettingsLauncher()
}
