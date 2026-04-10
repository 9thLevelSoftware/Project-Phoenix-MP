package com.devil.phoenixproject.domain.model

import android.os.SystemClock

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
