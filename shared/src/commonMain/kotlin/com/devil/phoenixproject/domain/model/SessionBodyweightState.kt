package com.devil.phoenixproject.domain.model

/**
 * Session-scoped bodyweight prompt state for bodyweight-containing workout sessions.
 *
 * A null [sessionBodyWeightKg] means the active session should fall back to the
 * saved [UserPreferences.bodyWeightKg] value. A positive value is an explicit
 * override for this loaded routine/session only unless the user also chose to
 * save it to profile.
 */
data class SessionBodyweightState(
    val routineHasBodyweight: Boolean = false,
    val promptHandled: Boolean = false,
    val sessionBodyWeightKg: Float? = null,
    val lastAction: SessionBodyweightAction? = null,
)

enum class SessionBodyweightAction {
    CONFIRMED_STORED,
    EDITED_FOR_SESSION,
    EDITED_AND_SAVED_TO_PROFILE,
    SKIPPED,
}
