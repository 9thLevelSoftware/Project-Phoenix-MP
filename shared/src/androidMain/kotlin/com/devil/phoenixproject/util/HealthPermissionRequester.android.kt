package com.devil.phoenixproject.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import com.devil.phoenixproject.data.integration.requiredHealthPermissions

actual class HealthPermissionRequester {

    @Composable
    actual fun LaunchPermissionRequest(
        onPermissionsResult: (Boolean) -> Unit
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract()
        ) { grantedPermissions ->
            onPermissionsResult(grantedPermissions.containsAll(requiredHealthPermissions))
        }

        LaunchedEffect(Unit) {
            launcher.launch(requiredHealthPermissions)
        }
    }
}

@Composable
actual fun rememberHealthPermissionRequester(): HealthPermissionRequester {
    return remember { HealthPermissionRequester() }
}
