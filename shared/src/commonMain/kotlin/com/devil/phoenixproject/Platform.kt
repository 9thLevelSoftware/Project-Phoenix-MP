package com.devil.phoenixproject

/**
 * Platform interface for platform-specific implementations.
 * Each platform (Android, iOS) provides its own implementation.
 */
interface Platform {
    val name: String
}

/**
 * Returns the current platform implementation.
 */
expect fun getDeviceName(): String

expect fun getPlatform(): Platform
