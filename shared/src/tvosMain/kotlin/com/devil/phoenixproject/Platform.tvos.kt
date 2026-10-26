package com.devil.phoenixproject

private object AppleTvPlatform : Platform {
    override val name: String = "Apple TV"
}

actual fun getPlatform(): Platform = AppleTvPlatform

// tvOS is not iOS: do not expose iOS-only API gates to the TV app.
actual val isIosPlatform: Boolean = false
