package com.devil.phoenixproject.presentation.navigation

import androidx.navigation.NavController

/**
 * Pops the back stack to [dest]. If [dest] is not in the back stack (e.g. after process
 * recreation or an unusual entry path), navigates to [dest] directly, preserving Home as
 * the bottom entry so the user always has a back-path.
 */
fun NavController.safePopOrNavigate(dest: String) {
    if (!popBackStack(dest, false)) {
        navigate(dest) {
            popUpTo(NavigationRoutes.Home.route) { inclusive = false }
        }
    }
}

/**
 * Returns to the existing Just Lift destination only from the ActiveWorkout entry that
 * originated this exit. The entry id prevents delayed effects from an old composition
 * from navigating a later workout that happens to use the same route.
 */
fun NavController.isCurrentActiveWorkoutEntry(sourceEntryId: String?): Boolean {
    if (sourceEntryId == null) return false
    val currentEntry = currentBackStackEntry ?: return false
    return currentEntry.id == sourceEntryId &&
        currentEntry.destination.route == NavigationRoutes.ActiveWorkout.route
}

/**
 * Idempotently exits the originating Just Lift ActiveWorkout entry. A second delivery
 * (for example from the Idle observer after the Exit callback) is ignored once the
 * original entry is no longer current. If Just Lift is absent, the existing safe fallback
 * preserves Home and creates one Just Lift destination.
 */
fun NavController.exitJustLift(sourceEntryId: String?) {
    if (!isCurrentActiveWorkoutEntry(sourceEntryId)) return
    safePopOrNavigate(NavigationRoutes.JustLift.route)
}
