package com.devil.phoenixproject.domain.model

/**
 * User preferences data class
 */
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.KG,
    val autoplayEnabled: Boolean = true,
    val stopAtTop: Boolean = false,  // false = stop at bottom (extended), true = stop at top (contracted)
    val enableVideoPlayback: Boolean = true,  // true = show videos, false = hide videos to avoid slow loading
    val beepsEnabled: Boolean = true,  // true = play audio cues during workouts, false = haptic only
    val audioRepCountEnabled: Boolean = false  // Audio rep count announcements during workout
)
