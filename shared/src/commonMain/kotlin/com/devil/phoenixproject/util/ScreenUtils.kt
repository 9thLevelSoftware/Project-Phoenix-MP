package com.devil.phoenixproject.util

/**
 * Platform-specific screen utilities.
 */

/**
 * Prevents the screen from timing out and going to sleep.
 * Call with true when entering a workout, false when leaving.
 *
 * On Android: Sets FLAG_KEEP_SCREEN_ON on the activity window
 * On iOS: Sets UIApplication.isIdleTimerDisabled
 */
expect fun setKeepScreenOn(enabled: Boolean)
