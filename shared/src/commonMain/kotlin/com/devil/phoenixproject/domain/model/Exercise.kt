package com.devil.phoenixproject.domain.model

/**
 * Exercise model - represents any exercise that can be performed on the Vitruvian Trainer
 *
 * MIGRATION NOTE: This was converted from an enum to a data class to support the exercise library
 * with 100+ exercises instead of being limited to hardcoded values.
 *
 * NOTES:
 * - Vitruvian cables only pull UPWARD from floor platform
 * - Compatible: Rows, presses, curls, squats, deadlifts, raises
 * - NOT compatible: Pulldowns, pushdowns (require overhead anchor)
 * - Machine tracks each cable independently (loadA, loadB, posA, posB)
 * - Weight is always specified as "per cable" in the BLE protocol
 */
data class Exercise(
    val name: String,
    val muscleGroup: String,
    val muscleGroups: String = muscleGroup, // Comma-separated list of primary muscle groups (defaults to muscleGroup for backward compatibility)
    val equipment: String = "",
    val id: String? = null,  // Optional exercise library ID for loading videos/thumbnails
    val isFavorite: Boolean = false, // Whether exercise is marked as favorite
    val isCustom: Boolean = false, // Whether exercise was created by user
    val timesPerformed: Int = 0, // Number of times this exercise has been performed
    val oneRepMaxKg: Float? = null // User's 1RM for percentage-based programming
) {
    /**
     * Display name for UI (same as name for now)
     */
    val displayName: String
        get() = name

    /**
     * Whether this exercise uses any cable accessory (handles, bar, rope, etc.).
     * Exercises with only non-cable equipment (e.g., bench) or no equipment are bodyweight.
     */
    val hasCableAccessory: Boolean
        get() = equipment.split(",").any { it.trim().uppercase() in CABLE_ACCESSORIES }

    companion object {
        /** Equipment that physically attaches to the machine's cables */
        private val CABLE_ACCESSORIES = setOf("HANDLES", "BAR", "ROPE", "SHORT_BAR", "BELT", "STRAPS")
    }
}

/**
 * Exercise categories for organization
 * Used primarily for filtering and grouping in the UI
 */
enum class ExerciseCategory(val displayName: String) {
    CHEST("Chest"),
    BACK("Back"),
    SHOULDERS("Shoulders"),
    BICEPS("Biceps"),
    TRICEPS("Triceps"),
    LEGS("Legs"),
    GLUTES("Glutes"),
    CORE("Core"),
    FULL_BODY("Full Body")
}
