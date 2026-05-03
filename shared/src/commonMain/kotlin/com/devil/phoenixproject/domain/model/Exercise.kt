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
enum class ExerciseCableIntent {
    SINGLE,
    DUAL,
    EITHER,
}

data class Exercise(
    val name: String,
    val muscleGroup: String,
    val muscleGroups: String = muscleGroup, // Comma-separated list of primary muscle groups (defaults to muscleGroup for backward compatibility)
    val equipment: String = "",
    val id: String? = null, // Optional exercise library ID for loading videos/thumbnails
    val isFavorite: Boolean = false, // Whether exercise is marked as favorite
    val isCustom: Boolean = false, // Whether exercise was created by user
    val timesPerformed: Int = 0, // Number of times this exercise has been performed
    val oneRepMaxKg: Float? = null, // User's 1RM for percentage-based programming
    val cableIntent: ExerciseCableIntent? = null, // Explicit single/dual cable metadata when known
    val displayName: String = name, // Disambiguated name from catalog; defaults to base name
) {
    /**
     * Whether this exercise uses any cable accessory (handles, bar, rope, etc.).
     * Exercises with only non-cable equipment (e.g., bench) or no equipment are bodyweight.
     */
    val hasCableAccessory: Boolean
        get() = equipment.split(",").any { it.trim().uppercase() in CABLE_ACCESSORIES }

    /**
     * Whether this is a bodyweight exercise (no cable accessories attached).
     * Inverse of [hasCableAccessory] for readability at call sites.
     */
    val isBodyweight: Boolean
        get() = !hasCableAccessory

    /**
     * Preferred cable count for summary calculations when the exercise metadata is explicit.
     * Null means the caller should fall back to telemetry heuristics.
     */
    val preferredCableCount: Int?
        get() = when (cableIntent) {
            ExerciseCableIntent.SINGLE -> 1
            ExerciseCableIntent.DUAL -> 2
            ExerciseCableIntent.EITHER, null -> null
        }

    /**
     * Whether this exercise uses a unified attachment (long bar or belt) connecting both cables.
     * Only BAR and BELT create a single combined load from both cables.
     * HANDLES, ROPE, SHORT_BAR, STRAPS are individual per-cable attachments.
     */
    val usesUnifiedAttachment: Boolean
        get() {
            val equipmentParts = equipment.split(",").map { it.trim().uppercase() }
            return equipmentParts.any { it == "BAR" || it == "BELT" }
        }

    /**
     * Display multiplier for weight presentation.
     * Returns 2 only when exercise uses dual cables with a unified attachment (BAR/BELT).
     * Individual-handle dual exercises (e.g., bicep curls with HANDLES) return 1
     * because each arm lifts per-cable weight independently.
     * Null when cable intent is unknown (EITHER or null) -- let caller decide.
     */
    val displayMultiplier: Int?
        get() = when (cableIntent) {
            ExerciseCableIntent.SINGLE -> 1
            ExerciseCableIntent.DUAL -> if (usesUnifiedAttachment) 2 else 1
            ExerciseCableIntent.EITHER, null -> null
        }

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
    FULL_BODY("Full Body"),
}
