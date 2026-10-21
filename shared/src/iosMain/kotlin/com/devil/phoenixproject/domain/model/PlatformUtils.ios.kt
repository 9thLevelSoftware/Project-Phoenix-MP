package com.devil.phoenixproject.domain.model

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * Milliseconds from Darwin `CLOCK_MONOTONIC_RAW`, which follows `mach_continuous_time`
 * and keeps counting through sleep. `NSProcessInfo.systemUptime` stops while the
 * device is asleep, so rest armed on that clock under-counted on a locked phone.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual fun elapsedRealtimeMillis(): Long =
    (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert()) / 1_000_000uL).toLong()
