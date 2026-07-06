package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

actual class HealthPermissionSettingsLauncher {
    actual fun openSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url)
    }
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher = remember {
    HealthPermissionSettingsLauncher()
}
