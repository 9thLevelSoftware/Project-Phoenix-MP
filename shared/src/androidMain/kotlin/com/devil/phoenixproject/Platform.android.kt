package com.devil.phoenixproject

import android.os.Build

/**
 * Android platform implementation.
 */
private class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual val isIosPlatform: Boolean = false
