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

/**
 * Whether the current platform is iOS.
 *
 * Used for platform-conditional feature availability:
 * - BOARD-06: iOS upgrade prompts must not mention Form Check (CV is Android-only in v1)
 * - Phase 19 (CV-10): Form Check toggle will show "Coming soon" on iOS instead of enabling camera
 */
expect val isIosPlatform: Boolean
