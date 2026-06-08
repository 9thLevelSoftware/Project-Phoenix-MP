package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

/**
 * Platform bridge for opening the user's health-permissions settings for Phoenix.
 *
 * Android Health Connect may suppress the normal permission request UI after a
 * user revokes access or disables the integration. In that recovery state the
 * app must send the user to the Health Connect app-permissions screen.
 */
expect class HealthPermissionSettingsLauncher {
    fun openSettings()
}

@Composable
expect fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher
