package com.devil.phoenixproject.domain.model

/**
 * Platform-specific utilities
 */

/**
 * Get current timestamp in milliseconds
 */
expect fun currentTimeMillis(): Long

/**
 * Milliseconds since boot on a monotonic clock, including time spent in sleep.
 *
 * Matches Android `SystemClock.elapsedRealtime()`. Rest deadlines are armed on
 * this clock, so both platforms must keep counting while the device is asleep.
 */
expect fun elapsedRealtimeMillis(): Long
