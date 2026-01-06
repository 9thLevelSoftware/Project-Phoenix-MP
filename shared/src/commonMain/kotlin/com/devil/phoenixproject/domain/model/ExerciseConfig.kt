package com.devil.phoenixproject.domain.model

/**
 * Configuration for a single exercise in a template.
 * Captures mode selection and mode-specific settings.
 * Excludes reps/sets/rest as those are template-defined.
 */
data class ExerciseConfig(
    val exerciseName: String,
    // Changed from ProgramMode to WorkoutType to support Echo mode
    val workoutType: WorkoutType = WorkoutType.Program(ProgramMode.OldSchool),

    // Weight (used by all cable modes except Echo)
    val weightPerCableKg: Float = 0f,

    // OldSchool-specific
    val autoProgression: Boolean = true,

    // Eccentric-specific (Echo also uses this, but stores in WorkoutType.Echo.eccentricLoad)
    val eccentricLoadPercent: Int = 100 // 100-150%, maps to EccentricLoad enum values
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
            val workoutType = WorkoutType.Program(suggestedMode ?: ProgramMode.OldSchool)
            // Default weight is 70% of 1RM if available
            val weight = oneRepMaxKg?.let { (it * 0.70f * 2).toInt() / 2f } ?: 0f

            return ExerciseConfig(
                exerciseName = exerciseName,
                workoutType = workoutType,
                weightPerCableKg = weight
            )
        }
    }

    /**
     * Helper to get display name for the selected mode.
     */
    val modeDisplayName: String get() = workoutType.displayName

    /**
     * Check if this config is for Echo mode.
     */
    val isEchoMode: Boolean get() = workoutType is WorkoutType.Echo

    /**
     * Get the EchoLevel if in Echo mode, null otherwise.
     */
    val echoLevel: EchoLevel? get() = (workoutType as? WorkoutType.Echo)?.level
}
