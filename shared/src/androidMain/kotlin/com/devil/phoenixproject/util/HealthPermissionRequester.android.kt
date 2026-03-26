package com.devil.phoenixproject.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.requiredHealthPermissions

private val log = Logger.withTag("HealthPermissionRequester")

actual class HealthPermissionRequester {

    @Composable
    actual fun LaunchPermissionRequest(
        onPermissionsResult: (Boolean) -> Unit
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract()
        ) { grantedPermissions ->
            val allGranted = grantedPermissions.containsAll(requiredHealthPermissions)
            log.d { "Health Connect permission contract returned: granted=$allGranted, received=${grantedPermissions.size} permissions: $grantedPermissions" }
            log.d { "Required permissions: $requiredHealthPermissions" }
            onPermissionsResult(allGranted)
        }

        LaunchedEffect(Unit) {
            log.d { "Launching Health Connect permission request for: $requiredHealthPermissions" }
            launcher.launch(requiredHealthPermissions)
        }
    }
}

@Composable
actual fun rememberHealthPermissionRequester(): HealthPermissionRequester {
    return remember { HealthPermissionRequester() }
}
