package com.devil.phoenixproject.presentation.navigation

import androidx.navigation.NavController

/**
 * Issue #1366 — the single navigation owner for the Just Lift STOP -> Exit path.
 *
 * The stop/exit flow has two navigation triggers: the non-routine Exit dialog confirm
 * and the Just-Lift Idle observer in `ActiveWorkoutScreen`. Both delegate to
 * [exitToJustLift], which performs AT MOST ONE effective transition for a given
 * ActiveWorkout entry and targets the EXISTING JustLift destination non-inclusively,
 * instead of a relative `navigateUp()` (the documented Issue #204 double-navigateUp
 * failure mode that over-pops [Home, JustLift, ActiveWorkout] to Home — Issue #1366).
 *
 * Identity rule: the transition is verified against the ActiveWorkout back-stack entry
 * that hosts the screen, plus a destination check — not a composition-local boolean,
 * which cannot survive composition recreation mid-teardown. Workout session identity is
 * deliberately NOT checked: a later session started from JustLiftScreen always gets a
 * NEW ActiveWorkout entry (rejected by the entry check), while a Just Lift set
 * auto-started from SetSummary legitimately continues on the SAME entry and must still
 * be able to exit when it ends.
 */
class JustLiftExitContext internal constructor(
    /** The back-stack entry (ActiveWorkout) that owns/authorised this exit. */
    val sourceEntryId: String,
)

/**
 * Idempotent, destination-specific Just Lift exit. Returns true when THIS call
 * performed the one effective transition; false when it no-opped (the source entry is
 * no longer current, or JustLift is already current).
 *
 * Exactly one transition is guaranteed even when both owners fire (dialog confirm +
 * Idle observer), when the composition is recreated mid-teardown, or when the same
 * callback is delivered repeatedly: the first call leaves the source entry, and every
 * later call fails the source-entry/destination checks. Duplicate ActiveWorkout entries
 * are tolerated: the pop to JustLift removes ALL entries above the destination in one
 * transition.
 */
fun NavController.exitToJustLift(context: JustLiftExitContext): Boolean {
    val currentEntry = currentBackStackEntry ?: return false

    // 1. Source-entry identity: once the exit has landed (or any later flow has taken
    //    over), the originating entry is no longer current and every repeat or stale
    //    delivery no-ops. This is what makes the transition idempotent across
    //    composition recreation and duplicated callbacks.
    if (currentEntry.id != context.sourceEntryId) return false

    // 2. Destination check: if JustLift is already current there is nothing to do.
    if (currentEntry.destination.route == NavigationRoutes.JustLift.route) return false

    // 3. Destination-specific non-inclusive pop to the EXISTING JustLift entry
    //    (tolerates duplicate ActiveWorkout entries). When JustLift is not on the stack
    //    (session resumed into ActiveWorkout from elsewhere, or state restoration),
    //    return to the previous screen as before #1366 instead of creating a fresh
    //    JustLiftScreen that would re-prepare and re-arm handle auto-start after Exit.
    if (!popBackStack(NavigationRoutes.JustLift.route, false)) {
        navigateUp()
    }
    return true
}
