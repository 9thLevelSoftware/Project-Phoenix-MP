package com.devil.phoenixproject.util

import platform.UIKit.UIApplication

actual fun setKeepScreenOn(enabled: Boolean) {
    UIApplication.sharedApplication.setIdleTimerDisabled(enabled)
}
