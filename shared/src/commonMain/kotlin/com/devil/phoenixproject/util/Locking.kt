package com.devil.phoenixproject.util

/**
 * Multiplatform lock helper.
 *
 * Android/JVM synchronizes on the supplied [lock] monitor. iOS/Native ignores
 * [lock] and serializes ALL callers on a single global NSRecursiveLock (see
 * Locking.ios.kt), so distinct lock objects do NOT progress independently on
 * iOS. Do not rely on per-object lock isolation across platforms.
 */
expect inline fun <T> withPlatformLock(lock: Any, block: () -> T): T
