package com.devil.phoenixproject.domain.model

/**
 * User preferences data class
 */
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.LB,
    val autoplayEnabled: Boolean = true,
    val stopAtTop: Boolean = false,  // false = stop at bottom (extended), true = stop at top (contracted)
    val enableVideoPlayback: Boolean = true,  // true = show videos, false = hide videos to avoid slow loading
    val beepsEnabled: Boolean = true,  // true = play audio cues during workouts, false = haptic only
    val colorScheme: Int = 0,
    val stallDetectionEnabled: Boolean = true,  // Stall detection auto-stop toggle
    val discoModeUnlocked: Boolean = false,  // Easter egg - unlocked by tapping LED header 7 times
    val audioRepCountEnabled: Boolean = false,  // Audio rep count announcements during workout
    // Countdown settings
    val summaryCountdownSeconds: Int = 10,  // 0-30 in 5s intervals, default 10. Value 0 = Off (no auto-advance)
    val autoStartCountdownSeconds: Int = 5  // 2-10 in 1s intervals, default 5
)
