package com.devil.phoenixproject

import platform.UIKit.UIDevice

actual fun getDeviceName(): String = UIDevice.currentDevice.name

/**
 * iOS platform implementation.
 */
class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()
