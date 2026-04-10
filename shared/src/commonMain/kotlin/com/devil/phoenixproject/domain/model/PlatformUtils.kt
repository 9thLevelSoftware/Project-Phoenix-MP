package com.devil.phoenixproject.domain.model

/**
 * Platform-specific utilities
 */

/**
 * Get current timestamp in milliseconds
 */
expect fun currentTimeMillis(): Long

/**
 * Get a monotonic elapsed time in milliseconds suitable for interval timing.
 */
expect fun elapsedRealtimeMillis(): Long
