package com.devil.phoenixproject.domain.model

/**
 * Cable configuration for Vitruvian exercises
 * - SINGLE: One cable only (unilateral - e.g., one-arm row)
 * - DOUBLE: Both cables required (bilateral - e.g., bench press)
 * - EITHER: User can choose single or double (e.g., bicep curls)
 */
enum class CableConfiguration {
    SINGLE,
    DOUBLE,
    EITHER
}

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
    val defaultCableConfig: CableConfiguration = CableConfiguration.DOUBLE,
    val id: String? = null,  // Optional exercise library ID for loading videos/thumbnails
    val isFavorite: Boolean = false, // Whether exercise is marked as favorite
    val timesPerformed: Int = 0 // Number of times this exercise has been performed
) {
    /**
     * Display name for UI (same as name for now)
     */
    val displayName: String
        get() = name

    /**
     * Resolve the default cable configuration based on equipment.
     * If equipment suggests single cable use (e.g., single handle), defaults to SINGLE.
     */
    fun resolveDefaultCableConfig(): CableConfiguration {
        // Check if equipment suggests single cable
        val singleCableEquipment = listOf("SINGLE_HANDLE", "ANKLE_STRAP", "STRAPS")
        val equipmentList = equipment.uppercase().split(",").map { it.trim() }

        return if (equipmentList.any { it in singleCableEquipment }) {
            CableConfiguration.SINGLE
        } else {
            defaultCableConfig
        }
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
