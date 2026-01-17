package com.devil.phoenixproject

import android.os.Build

actual fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

/**
 * Android platform implementation.
 */
class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
