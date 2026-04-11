package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.devil.phoenixproject.data.integration.HealthIntegration
import org.koin.compose.koinInject

/**
 * iOS implementation of HealthPermissionRequester.
 *
 * Unlike Android (which requires an Activity Result contract via Health Connect),
 * HealthKit authorization can be triggered from any context using
 * HKHealthStore.requestAuthorization. This makes the iOS implementation
 * straightforward: launch a coroutine in LaunchedEffect and call
 * requestPermissions() directly.
 */
actual class HealthPermissionRequester {

    @Composable
    actual fun LaunchPermissionRequest(onPermissionsResult: (Boolean) -> Unit) {
        val healthIntegration = koinInject<HealthIntegration>()

        LaunchedEffect(Unit) {
            val granted = healthIntegration.requestPermissions()
            onPermissionsResult(granted)
        }
    }
}

@Composable
actual fun rememberHealthPermissionRequester(): HealthPermissionRequester = remember {
    HealthPermissionRequester()
}
