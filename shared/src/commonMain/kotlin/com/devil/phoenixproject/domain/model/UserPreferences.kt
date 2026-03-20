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
    val repCountTiming: RepCountTiming = RepCountTiming.TOP,  // When to count working reps (TOP=concentric, BOTTOM=eccentric)
    // Countdown settings
    val summaryCountdownSeconds: Int = 10,  // -1 = Off (skip summary), 0 = Unlimited (no auto-advance), 5-30 = auto-advance
    val autoStartCountdownSeconds: Int = 5,  // 2-10 in 1s intervals, default 5
    val hudPreset: String = HudPreset.FULL.key,  // HUD page preset: "essential", "biomechanics", or "full"
    val gamificationEnabled: Boolean = true,  // Show PR celebrations, award badges, play celebration sounds
    val simulatorModeUnlocked: Boolean = false,  // Easter egg - unlocked via settings tap
    val simulatorModeEnabled: Boolean = false,  // Active simulator mode toggle
    // Issue #266: Configurable weight increment (in user's selected unit)
    val weightIncrement: Float = -1f,  // -1 = use default for unit (0.5kg / 1.0lb)
    // Issue #190: Skip RoutineOverviewScreen and jump straight to SetReady for exercise 0
    val autoStartRoutine: Boolean = false,
    // Issue #229: Body weight for volume calculations on bodyweight exercises
    val bodyWeightKg: Float = 0f,  // 0 = not set
    // Issue #100: Per-sound toggles
    val countdownBeepsEnabled: Boolean = true,  // Beeps during last 10s of rest timer
    val repSoundEnabled: Boolean = true,  // Sound on rep completion
    // Issue #237: Motion-triggered set start (opt-in alternative to 5-second countdown)
    val motionStartEnabled: Boolean = false,  // Start sets by holding cables instead of countdown
    // Issue #293: Per-session auto-backup to device filesystem
    val autoBackupEnabled: Boolean = false,  // Automatically save each workout to a local backup file
    // Issue #238: Language/locale preference for i18n
    val language: String = "en",  // Language code: "en", "nl", "de", "es", "fr"
    // Issue #141: Voice-activated emergency stop
    val voiceStopEnabled: Boolean = false,  // Whether safe word detection is active during workouts
    val safeWord: String? = null  // The configured safe word (null = not yet calibrated)
) {
    /**
     * Get the effective weight increment in the user's display unit.
     * Returns the configured increment, or the default for the current unit system.
     */
    val effectiveWeightIncrement: Float
        get() = if (weightIncrement > 0f) weightIncrement
                else if (weightUnit == WeightUnit.KG) 0.5f else 1.0f

    /**
     * Get the effective weight increment converted to kg (for internal calculations).
     */
    val effectiveWeightIncrementKg: Float
        get() {
            val displayIncrement = effectiveWeightIncrement
            return if (weightUnit == WeightUnit.LB) {
                com.devil.phoenixproject.util.UnitConverter.lbToKg(displayIncrement)
            } else {
                displayIncrement
            }
        }
}
