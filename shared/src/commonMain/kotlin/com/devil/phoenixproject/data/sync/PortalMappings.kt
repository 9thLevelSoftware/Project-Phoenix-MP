package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Canonical mappings between mobile and portal wire formats used by push and pull:
 * cable count, workout mode, and Newton/velocity units.
 */
object PortalMappings {

    // -- Cable Count --

    /**
     * Map a mobile cable count to the portal wire value for `exercises.cableCount`.
     *
     * The portal accepts exactly 1 or 2 (or null/absent = unknown) and rejects the
     * WHOLE push batch with 400 for any other value, so anything outside {1, 2}
     * becomes null (unknown) rather than being clamped to a guess. Also used on
     * pull so an out-of-range server value never reaches the local DB.
     */
    fun cableCountToWire(count: Int?): Int? = count?.takeIf { it == 1 || it == 2 }

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
        ProgramMode.Echo,
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

    // -- Unit Conversions --

    /** Convert velocity from mm/s (mobile BLE) to m/s (portal). */
    fun velocityMmSToMps(mmPerSec: Float): Float = mmPerSec / 1000f

    /** Convert force from kg (mobile load) to Newtons (portal). Assumes standard gravity. */
    fun loadKgToNewtons(kg: Float): Float = kg * 9.80665f

    /** Convert Newtons back to kg load. */
    fun newtonsToLoadKg(newtons: Float): Float = newtons / 9.80665f
}
