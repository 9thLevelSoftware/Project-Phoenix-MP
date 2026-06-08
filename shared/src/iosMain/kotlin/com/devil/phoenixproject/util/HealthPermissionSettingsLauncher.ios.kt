package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class HealthPermissionSettingsLauncher {
    actual fun openSettings() {
        // iOS HealthKit permissions are handled by HealthKit authorization UI.
    }
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher = remember {
    HealthPermissionSettingsLauncher()
}
