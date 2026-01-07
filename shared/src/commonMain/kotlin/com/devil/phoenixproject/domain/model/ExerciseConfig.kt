package com.devil.phoenixproject.domain.model

/**
 * Configuration for a single exercise in a template.
 * Captures mode selection and mode-specific settings.
 * Excludes reps/sets/rest as those are template-defined.
 */
data class ExerciseConfig(
    val exerciseName: String,
    val mode: ProgramMode = ProgramMode.OldSchool,

    // Weight (used by all cable modes except Echo)
    val weightPerCableKg: Float = 0f,

    // OldSchool-specific
    val autoProgression: Boolean = true,

    // Eccentric-specific (EccentricOnly and Echo modes)
    val eccentricLoadPercent: Int = 100, // 100-150%

    // Echo-specific
    val echoLevel: EchoLevel = EchoLevel.HARD
) {
    companion object {
        /**
         * Create default config for an exercise based on template suggestion.
         */
        fun fromTemplate(
            exerciseName: String,
            suggestedMode: ProgramMode?,
            oneRepMaxKg: Float? = null
        ): ExerciseConfig {
            val mode = suggestedMode ?: ProgramMode.OldSchool
            // Default weight is 70% of 1RM if available
            val weight = oneRepMaxKg?.let { (it * 0.70f * 2).toInt() / 2f } ?: 0f

            return ExerciseConfig(
                exerciseName = exerciseName,
                mode = mode,
                weightPerCableKg = weight
            )
        }
    }

    /**
     * Helper to get display name for the selected mode.
     */
    val modeDisplayName: String get() = mode.displayName

    /**
     * Check if this config is for Echo mode.
     */
    val isEchoMode: Boolean get() = mode == ProgramMode.Echo
}
