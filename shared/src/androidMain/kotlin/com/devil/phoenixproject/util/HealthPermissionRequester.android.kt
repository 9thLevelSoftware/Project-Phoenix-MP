package com.devil.phoenixproject.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.optionalHealthPermissions
import com.devil.phoenixproject.data.integration.requestedHealthPermissions
import com.devil.phoenixproject.data.integration.requiredHealthPermissions

private val log = Logger.withTag("HealthPermissionRequester")

actual class HealthPermissionRequester {

    @Composable
    actual fun LaunchPermissionRequest(onPermissionsResult: (Boolean) -> Unit) {
        val launcher = rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            val requiredGranted = grantedPermissions.containsAll(requiredHealthPermissions)
            val optionalGranted = grantedPermissions.containsAll(optionalHealthPermissions)
            log.d {
                "Health Connect permission contract returned: requiredGranted=$requiredGranted, " +
                    "optionalCaloriesGranted=$optionalGranted, received=${grantedPermissions.size} permissions: $grantedPermissions"
            }
            log.d { "Required permissions: $requiredHealthPermissions" }
            log.d { "Optional permissions: $optionalHealthPermissions" }
            onPermissionsResult(requiredGranted)
        }

        LaunchedEffect(Unit) {
            log.d { "Launching Health Connect permission request for: $requestedHealthPermissions" }
            launcher.launch(requestedHealthPermissions)
        }
    }
}

@Composable
actual fun rememberHealthPermissionRequester(): HealthPermissionRequester = remember {
    HealthPermissionRequester()
}
