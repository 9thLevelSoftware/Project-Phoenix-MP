package com.devil.phoenixproject.domain.usecase

/**
 * Issue #229: Calculate volume for bodyweight exercises.
 *
 * Different bodyweight exercises use different percentages of body weight.
 * This calculator provides estimated volume based on exercise type and body weight.
 */
object BodyweightVolumeCalculator {

    /**
     * Known bodyweight exercise percentage factors.
     * Source: Research-based estimates of body weight moved during common exercises.
     *
     * Key = lowercase exercise name keywords to match against
     * Value = percentage of body weight (0.0 to 1.0)
     */
    private val EXERCISE_PERCENTAGES: List<Pair<List<String>, Float>> = listOf(
        // Push-up variations — height-specific decline BEFORE generic decline
        // Issue #229: Decline heights from research (4.5"/11"/16"/24")
        listOf("decline 24", "decline push 24") to 0.75f,
        listOf("decline 16", "decline push 16") to 0.72f,
        listOf("decline 11", "decline push 11") to 0.70f,
        listOf("decline 4.5", "decline push 4.5", "decline 4½") to 0.66f,
        // Generic decline (fallback for unspecified height)
        listOf("decline push", "decline pushup") to 0.70f,
        listOf("incline push", "incline pushup") to 0.40f,
        listOf("pike push", "pike pushup") to 0.70f,
        listOf("diamond push", "diamond pushup") to 0.64f,
        listOf("handstand push", "handstand pushup", "handstand press") to 1.00f,
        // Generic push-up (must be AFTER specific variations)
        listOf("push up", "pushup", "push-up") to 0.64f,

        // Pull-ups and variations
        listOf("wide grip pull", "wide-grip pull") to 0.90f,
        listOf("pull up", "pullup", "pull-up") to 0.95f,
        listOf("chin up", "chinup", "chin-up") to 0.95f,

        // Dips
        listOf("dip", "dips") to 0.95f,

        // Squats and lunges (bodyweight)
        listOf("bodyweight squat", "air squat") to 0.67f,
        listOf("lunge", "lunges") to 0.50f, // Per leg
        listOf("pistol squat", "single leg squat") to 0.67f,

        // Core
        listOf("sit up", "situp", "sit-up") to 0.40f,
        listOf("crunch", "crunches") to 0.30f,
        listOf("plank") to 0.65f,
        listOf("nordic curl", "nordic ham") to 0.60f,

        // Rows
        listOf("inverted row", "body row", "bodyweight row") to 0.60f,

        // General/default
        listOf("burpee") to 0.80f,
        listOf("mountain climber") to 0.60f,
    )

    /** Default percentage when exercise type is unknown */
    const val DEFAULT_PERCENTAGE = 0.64f

    /**
     * Bodyweight exercise variant options for the transient variant picker.
     * Each entry maps a base exercise keyword to a list of (displayLabel, percentage) pairs.
     * Used by the SetReady screen to let users select the specific variant they are performing.
     */
    val VARIANT_OPTIONS: Map<String, List<Pair<String, Float>>> = mapOf(
        "push up" to listOf(
            "Standard Push-Up" to 0.64f,
            "Incline (hands elevated)" to 0.40f,
            "Decline 4.5\"" to 0.66f,
            "Decline 11\"" to 0.70f,
            "Decline 16\"" to 0.72f,
            "Decline 24\"" to 0.75f,
            "Diamond Push-Up" to 0.64f,
            "Pike Push-Up" to 0.70f,
            "Handstand Push-Up" to 1.00f,
        ),
        "pull up" to listOf(
            "Standard Pull-Up" to 0.95f,
            "Chin-Up" to 0.95f,
            "Wide-Grip Pull-Up" to 0.90f,
        ),
    )

    /**
     * Find applicable variant options for an exercise name.
     * Returns null if no variants are available for this exercise.
     */
    fun getVariantsForExercise(exerciseName: String): List<Pair<String, Float>>? {
        val nameLower = exerciseName.lowercase()
        return VARIANT_OPTIONS.entries.firstOrNull { (key, _) ->
            nameLower.contains(key)
        }?.value
    }

    /**
     * Get the estimated body weight percentage for an exercise.
     *
     * @param exerciseName The exercise name to look up
     * @return Percentage of body weight moved (0.0 to 1.0)
     */
    fun getPercentageForExercise(exerciseName: String): Float {
        val nameLower = exerciseName.lowercase()
        for ((keywords, percentage) in EXERCISE_PERCENTAGES) {
            if (keywords.any { nameLower.contains(it) }) {
                return percentage
            }
        }
        return DEFAULT_PERCENTAGE
    }

    /**
     * Calculate volume for a bodyweight exercise set.
     *
     * @param exerciseName The exercise name (for percentage lookup)
     * @param bodyWeightKg User's body weight in kg
     * @param reps Number of reps completed
     * @return Estimated volume in kg (bodyWeight * percentage * reps)
     */
    fun calculateVolume(exerciseName: String, bodyWeightKg: Float, reps: Int): Float {
        if (bodyWeightKg <= 0f || reps <= 0) return 0f
        val percentage = getPercentageForExercise(exerciseName)
        return bodyWeightKg * percentage * reps
    }

    /**
     * Calculate the effective "weight per rep" for display purposes.
     *
     * @param exerciseName The exercise name
     * @param bodyWeightKg User's body weight in kg
     * @return Effective weight per rep in kg
     */
    fun effectiveWeight(exerciseName: String, bodyWeightKg: Float): Float {
        if (bodyWeightKg <= 0f) return 0f
        return bodyWeightKg * getPercentageForExercise(exerciseName)
    }
}
