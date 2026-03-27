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
        // Push-up variations (specific patterns BEFORE generic "push up")
        listOf("decline push", "decline pushup") to 0.70f,
        listOf("incline push", "incline pushup") to 0.55f,
        listOf("pike push", "pike pushup") to 0.70f,
        listOf("diamond push", "diamond pushup") to 0.64f,
        // Generic push-up (must be AFTER specific variations)
        listOf("push up", "pushup", "push-up") to 0.64f,

        // Pull-ups and variations
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

        // Rows
        listOf("inverted row", "body row", "bodyweight row") to 0.60f,

        // General/default
        listOf("burpee") to 0.80f,
        listOf("mountain climber") to 0.60f,
    )

    /** Default percentage when exercise type is unknown */
    const val DEFAULT_PERCENTAGE = 0.64f

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
