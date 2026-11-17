package com.devil.phoenixproject.presentation.manager

/**
 * Issue #1391 Phase 1 (packet B1): internal seam helpers for the bounded remote
 * set-control operation [DefaultWorkoutSessionManager.endCurrentSetAndPrepareNext].
 *
 * The receipt type itself ([RemoteSetControlReceipt] and its enums) is FROZEN and
 * lives in RemoteSetControlReceipt.kt. This file only carries the internal
 * reservation/identity plumbing that the manager-level seam and
 * [ActiveSessionEngine] share.
 */

/**
 * Canonical string identity of a set execution. Two leases are "the same
 * execution" exactly when `WorkoutExecutionGuard.sameIdentity` says so
 * (executionId + sessionId); this string form is what the watch-side callers
 * pass as `expectedExecutionIdentity` and what the receipt echoes back.
 */
internal fun executionIdentityOf(lease: ExecutionLease): String = "${lease.executionId}:${lease.sessionId}"

/**
 * How long the operation waits for the commit path and the machine teardown to
 * settle before reporting PENDING outcomes. Matches the Issue #869 summary
 * commit bound: a normal commit finishes well inside it.
 */
internal const val REMOTE_SET_CONTROL_RESULT_WINDOW_MS: Long = SUMMARY_COMMIT_WAIT_MS

/**
 * Which existing manual-completion route the reservation selected. The route is
 * the phone-equivalent save/discard attribution of `stopAndReturnToSetReady`
 * (Issue #320) extended with the Just Lift / bodyweight specializations the
 * engine's completion path already owns.
 */
internal enum class RemoteCompletionRoute {
    /**
     * Real routine or Just Lift set with working reps: the existing
     * `handleSetCompletion(..., USER_STOPPED)` machinery captures the immutable
     * completion facts, finalizes exactly once and tears the machine down
     * independently of the save.
     */
    SAVE_AND_ADVANCE,

    /**
     * temp_single_* / zero-working-reps / warmup sets: the existing
     * return-to-SetReady discard path (no save decision on the commit path).
     */
    DISCARD_AND_RETRY,

    /**
     * A bodyweight rep-entry prompt is already pending for this execution: the
     * phone owns the completion decision, so the seam mutates nothing.
     */
    PHONE_INPUT_PENDING,
}

/**
 * Token proving the operation currently owns the "no autostart" authority for
 * its window. Released exactly once when the operation finishes.
 */
internal class RemoteSetControlHold(val id: Long)

/**
 * The reserved current execution. Created only when `expectedExecutionIdentity`
 * matched the live execution at claim time, so every later step can revalidate
 * against an immutable anchor.
 */
internal data class RemoteSetControlReservation(
    val operationId: String,
    val expectedExecutionIdentity: String,
    val lease: ExecutionLease,
    /** Immutable completion facts claimed for this transition (the reserve step). */
    val completion: SetExecutionCompletion,
    val route: RemoteCompletionRoute,
    val hold: RemoteSetControlHold,
    /** Routine context captured at reserve time, for the pre-prepare revalidation. */
    val routineSessionIdAtReserve: String?,
    val routineIdAtReserve: String?,
)
