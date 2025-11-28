package com.devil.phoenixproject

/**
 * Platform interface for platform-specific implementations.
 * Each platform (Android, iOS, Desktop) will provide its own implementation.
 */
interface Platform {
    val name: String
}

/**
 * Returns the current platform implementation.
 */
expect fun getPlatform(): Platform
