package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Canonical mappings between mobile and portal data formats.
 *
 * Mobile stores granular muscle groups as free-text strings (e.g., "Quads", "Hamstrings").
 * Portal stores them as flexible TEXT columns (no enum validation).
 * This object provides mapping for aggregation/analytics where broader categories are needed.
 */
object PortalMappings {

    // -- Workout Mode --

    /**
     * All ProgramMode instances, keyed by class simpleName.
     * Used to resolve DB-stored object names (e.g., "OldSchool", "TUTBeast").
     */
    private val programModesByClassName: Map<String, ProgramMode> = listOf(
        ProgramMode.OldSchool,
        ProgramMode.Pump,
        ProgramMode.TUT,
        ProgramMode.TUTBeast,
        ProgramMode.EccentricOnly,
        ProgramMode.Echo
    ).associateBy { it::class.simpleName ?: "" }

    fun workoutModeToSync(modeString: String): String {
        // Try class simpleName match first (DB stores "OldSchool", "TUTBeast", etc.)
        programModesByClassName[modeString]?.let { return it.toSyncString() }

        // Then try display name match ("Old School", "TUT Beast", etc.)
        ProgramMode.fromDisplayName(modeString)?.let { return it.toSyncString() }

        // Then try if it's already a sync string ("OLD_SCHOOL", "TUT_BEAST", etc.)
        ProgramMode.fromSyncString(modeString)?.let { return it.toSyncString() }

        // Final fallback: SCREAMING_SNAKE conversion
        return modeString.uppercase().replace(" ", "_")
    }

    /**
     * Convert portal sync format back to display name.
     */
    fun workoutModeFromSync(syncString: String): String {
        return ProgramMode.fromSyncString(syncString)?.displayName ?: syncString
    }

    // -- Muscle Group --

    /**
     * Map from granular mobile muscle group to portal analytics category.
     *
     * Mobile uses 12+ granular groups; portal analytics sometimes aggregates to broader categories.
     * The raw granular string is always preserved in portal DB — this mapping is for display/grouping.
     */
    private val muscleGroupToCategory = mapOf(
        "Chest" to "Chest",
        "Back" to "Back",
        "Shoulders" to "Shoulders",
        "Biceps" to "Arms",
        "Triceps" to "Arms",
        "Forearms" to "Arms",
        "Quads" to "Legs",
        "Hamstrings" to "Legs",
        "Calves" to "Legs",
        "Glutes" to "Glutes",
        "Core" to "Core",
        "Abs" to "Core",
        "Full Body" to "Full Body"
    )

    /**
     * Map granular muscle group to a broader portal analytics category.
     * Returns the input unchanged if no mapping exists (safe passthrough).
     */
    fun toPortalCategory(muscleGroup: String): String {
        return muscleGroupToCategory[muscleGroup] ?: muscleGroup
    }

    /**
     * All portal analytics categories, for building UI filters.
     */
    val portalCategories = listOf("Chest", "Back", "Shoulders", "Arms", "Legs", "Glutes", "Core", "Full Body")

    // -- Cable Mapping --

    /**
     * Convert mobile cable identifier (A/B) to portal left/right convention.
     */
    fun cableToPortal(cable: String): String = when (cable.uppercase()) {
        "A" -> "left"
        "B" -> "right"
        else -> cable.lowercase()
    }

    // -- Unit Conversions --

    /** Convert velocity from mm/s (mobile BLE) to m/s (portal). */
    fun velocityMmSToMps(mmPerSec: Float): Float = mmPerSec / 1000f

    /** Convert force from kg (mobile load) to Newtons (portal). Assumes standard gravity. */
    fun loadKgToNewtons(kg: Float): Float = kg * 9.80665f

    /** Convert Newtons back to kg load. */
    fun newtonsToLoadKg(newtons: Float): Float = newtons / 9.80665f
}
