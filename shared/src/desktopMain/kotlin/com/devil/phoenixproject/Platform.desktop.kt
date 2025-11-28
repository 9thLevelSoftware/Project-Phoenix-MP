package com.devil.phoenixproject

/**
 * Desktop (JVM) platform implementation.
 */
class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name")} ${System.getProperty("os.version")}"
}

actual fun getPlatform(): Platform = DesktopPlatform()
