package com.devil.phoenixproject.util

/**
 * Multiplatform lock helper.
 * Android/JVM uses a real monitor; Native falls back to direct execution.
 */
expect inline fun <T> withPlatformLock(lock: Any, block: () -> T): T
