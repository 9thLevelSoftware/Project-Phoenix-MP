package com.devil.phoenixproject.presentation.navigation

import androidx.navigation.NavController

/**
 * Issue #1366 — the single navigation owner for the Just Lift STOP -> Exit path.
 *
 * The stop/exit flow has two navigation triggers: the non-routine Exit dialog confirm
 * and the Just-Lift Idle observer in `ActiveWorkoutScreen`. Both delegate to
 * [exitToJustLift], which performs AT MOST ONE effective transition for a given
 * ActiveWorkout entry/session and targets the EXISTING JustLift destination
 * non-inclusively, instead of a relative `navigateUp()` (the documented Issue #204
 * double-navigateUp failure mode that over-pops [Home, JustLift, ActiveWorkout] to
 * Home — Issue #1366).
 *
 * Identity rules (RCA remediation §2): the transition is verified against the
 * originating entry AND session identity captured when the exit was authorised, plus
 * destination checks — not a composition-local boolean, which cannot survive
 * composition recreation mid-teardown. The ended engine lease is deliberately
 * invalidated by `stopWorkout(exitingWorkout = true)`; it is NOT required to remain
 * current for the authorised navigation to complete (a null live session is accepted),
 * but a callback from an ended session is rejected while a LATER session is current.
 */
class JustLiftExitContext internal constructor(
    /** The back-stack entry (ActiveWorkout) that owns/authorised this exit. */
    val sourceEntryId: String?,
    /**
     * The workout session that authorised this exit (null = unknown). Used only to
     * reject stale callbacks while a later session is current; never required to
     * remain current for the authorised navigation to finish.
     */
    val sessionId: String?,
) {
    companion object {
        /**
         * Captures the originating entry/session identity. Callers that initiate a
         * stop must capture BEFORE `stopWorkout(exitingWorkout = true)` mutates
         * engine state.
         */
        fun capture(navController: NavController, sessionId: String?): JustLiftExitContext =
            JustLiftExitContext(
                sourceEntryId = navController.currentBackStackEntry?.id,
                sessionId = sessionId,
            )
    }
}

/**
 * Idempotent, destination-specific Just Lift exit. Returns true when THIS call
 * performed the one effective transition; false when it no-opped (exit already done,
 * stale caller, or already at the destination).
 *
 * Exactly one transition is guaranteed even when both owners fire (dialog confirm +
 * Idle observer), when the composition is recreated mid-teardown, or when the same
 * callback is delivered repeatedly: the first call pops the back stack to the existing
 * JustLift entry, and every later call fails the source-entry/destination checks.
 * Duplicate ActiveWorkout entries are tolerated: the pop to JustLift removes ALL
 * entries above the destination in one transition.
 *
 * [currentSessionId] reads the LIVE workout session identity at execution time.
 */
fun NavController.exitToJustLift(
    context: JustLiftExitContext,
    currentSessionId: () -> String?,
): Boolean {
    val currentEntry = currentBackStackEntry ?: return false

    // 1. Source-entry identity: once the exit has landed (or any later flow/session has
    //    taken over), the originating entry is no longer current and every repeat or
    //    stale delivery no-ops. This is what makes the transition idempotent across
    //    composition recreation and duplicated callbacks.
    if (context.sourceEntryId != null && currentEntry.id != context.sourceEntryId) return false

    // 2. Session identity: reject a callback authorised by an ENDED session once a
    //    LATER session is current. A null live session (the normal stop path — the
    //    engine invalidates the lease before publishing Idle) still completes the
    //    authorised navigation.
    val liveSessionId = currentSessionId()
    if (
        context.sessionId != null &&
        liveSessionId != null &&
        liveSessionId != context.sessionId
    ) {
        return false
    }

    // 3. Destination check: if JustLift is already current there is nothing to do.
    if (currentEntry.destination.route == NavigationRoutes.JustLift.route) return false

    // 4+5. Destination-specific non-inclusive pop to the EXISTING JustLift entry
    //    (tolerates duplicate ActiveWorkout entries), with the guarded
    //    absent-destination fallback preserving Home when JustLift is not on the
    //    stack (unusual entry path / state restoration).
    safePopOrNavigate(NavigationRoutes.JustLift.route)
    return true
}
