package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

/**
 * Platform-specific launcher for health permission requests.
 *
 * Android uses Health Connect's Activity Result contract.
 * iOS uses HealthKit (HKHealthStore.requestAuthorization) via HealthIntegration;
 * the result is delivered asynchronously through onPermissionsResult.
 */
expect class HealthPermissionRequester {
    @Composable
    fun LaunchPermissionRequest(onPermissionsResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberHealthPermissionRequester(): HealthPermissionRequester
