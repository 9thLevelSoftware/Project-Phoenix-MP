package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

private val log = Logger.withTag("AppSettingsOpener")

actual fun openAppSettings() {
    val url = NSURL(string = UIApplicationOpenSettingsURLString)
    // Deprecated single-argument openURL(_:) is rejected on newer iOS.
    UIApplication.sharedApplication.openURL(
        url = url,
        options = emptyMap<Any?, Any>(),
        completionHandler = { success ->
            if (!success) {
                log.w { "Failed to open app settings" }
            }
        },
    )
}
