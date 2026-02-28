package com.devil.phoenixproject.domain.model

/**
 * User preferences data class
 */
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.LB,
    // Issue #167: autoplayEnabled removed - now derived from summaryCountdownSeconds
    // summaryCountdownSeconds == 0 (Unlimited) = autoplay OFF, != 0 = autoplay ON
    val stopAtTop: Boolean = false,  // false = stop at bottom (extended), true = stop at top (contracted)
    val enableVideoPlayback: Boolean = true,  // true = show videos, false = hide videos to avoid slow loading
    val beepsEnabled: Boolean = true,  // true = play audio cues during workouts, false = haptic only
    val colorScheme: Int = 0,
    val stallDetectionEnabled: Boolean = true,  // Stall detection auto-stop toggle
    val discoModeUnlocked: Boolean = false,  // Easter egg - unlocked by tapping LED header 7 times
    val audioRepCountEnabled: Boolean = false,  // Audio rep count announcements during workout
    val ledFeedbackEnabled: Boolean = false,  // LED biofeedback during workouts (Phoenix tier)
    val colorBlindModeEnabled: Boolean = false,  // Deuteranopia-safe palette (off by default)
    // Countdown settings
    val summaryCountdownSeconds: Int = 10,  // -1 = Off (skip summary), 0 = Unlimited (no auto-advance), 5-30 = auto-advance
    val autoStartCountdownSeconds: Int = 5  // 2-10 in 1s intervals, default 5
)
