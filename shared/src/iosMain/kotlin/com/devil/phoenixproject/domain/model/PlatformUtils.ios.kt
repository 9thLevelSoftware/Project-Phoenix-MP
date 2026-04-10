package com.devil.phoenixproject.domain.model

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun elapsedRealtimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
