package com.devil.phoenixproject.util

actual inline fun <T> withPlatformLock(lock: Any, block: () -> T): T =
    kotlin.synchronized(lock, block)
