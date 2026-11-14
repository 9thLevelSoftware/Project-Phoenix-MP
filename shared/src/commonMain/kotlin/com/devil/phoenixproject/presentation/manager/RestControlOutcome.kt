package com.devil.phoenixproject.presentation.manager

/**
 * Issue #1360: internal typed completion contract for rest-timer controls
 * (+30s extension, pause/resume, reset).
 *
 * The public control callbacks remain asynchronous `Unit` commands. A
 * [RestControlOutcome.Applied] completion is delivered only after the durable runtime-document
 * commit, ownership revalidation and state publication all succeeded — never on enqueue. The
 * [RestControlOutcome.AppliedLocally] completion distinguishes the legacy no-plan fallback, which
 * mutates in-memory timer state without a durable runtime-document write. A
 * [RestControlOutcome.Rejected] completion names the exact guard or uncertain post-write state;
 * these outcomes never retry, roll back, or apply display-only optimistic state.
 */
internal sealed interface RestControlOutcome {
    /** The mutation was durably committed, revalidated and published. */
    data object Applied : RestControlOutcome

    /**
     * The legacy no-plan fallback changed only the in-memory timer state; no durable write was
     * attempted. This is not evidence that the runtime document was committed.
     */
    data object AppliedLocally : RestControlOutcome

    /** The mutation was refused or its authority was lost; [reason] identifies the outcome. */
    data class Rejected(val reason: RestControlRejection) : RestControlOutcome
}

/**
 * Refusal or uncertain post-write status for a rest-control mutation. Mirrors the guard and
 * commit-verification outcomes that previously early-returned silently inside
 * [ActiveSessionEngine].
 */
internal enum class RestControlRejection {
    /** The command arrived outside an active rest countdown. */
    NotResting,

    /**
     * The captured rest owner/generation no longer matches the current rest (missing lease,
     * document, plan, or mismatched identity/generation). A stale action must never reach the
     * next rest.
     */
    StaleOwner,

    /** Ownership/authority revalidation failed for the current rest. */
    AuthorityFailed,

    /** The durable runtime-document write failed and did not land (commit status UNCHANGED_PRIOR). */
    PersistenceFailed,

    /**
     * The write may have landed but ownership/publication drifted before completion (commit
     * status COMMITTED-without-publication, DIVERGED, or UNKNOWN). DIVERGED is not proof of a
     * refused write: the candidate may have been changed or removed after commit. Kept distinct
     * from [PersistenceFailed], this uncertain mutation must never be blindly retried or rolled
     * back.
     */
    PostWriteDrift,
}

/** Canonical action names for rest-control receipts. */
internal object RestControlActions {
    const val EXTEND_REST = "extend_rest"
    const val TOGGLE_REST_PAUSE = "toggle_rest_pause"
    const val RESET_REST_TIMER = "reset_rest_timer"
}

/**
 * Ephemeral, privacy-safe completion event for one rest-control command. Carries the action
 * kind, receipt id, stable owner-generation identity (not a document revision) and the phase a
 * rejection happened at — never tokens, profile identifiers, or runtime documents.
 */
internal data class RestControlCompletion(
    val action: String,
    val receiptId: Long,
    val ownerGeneration: String,
    val rejectedAtPhase: String?,
    val outcome: RestControlOutcome,
)

/**
 * Canonical feedback strings for rest-control rejections, emitted on
 * `WorkoutCoordinator._userFeedbackEvents` (the existing coordinator user-feedback plumbing).
 * The phone UI localizes these exact strings via its feedback message map; the English text is
 * the wire contract. Known rejections state failure; post-write drift explicitly remains uncertain.
 */
internal object RestControlFeedback {
    const val NOT_RESTING = "Rest timer isn't active right now."
    const val STALE_OWNER = "This rest changed before the change was applied."
    const val AUTHORITY_FAILED = "Couldn't confirm the current rest timer. The change was not applied."
    const val PERSISTENCE_FAILED = "Couldn't save the rest timer change. You can try again."
    const val POST_WRITE_DRIFT = "Rest timer changed while saving. The change may not have been applied."

    fun forReason(reason: RestControlRejection): String = when (reason) {
        RestControlRejection.NotResting -> NOT_RESTING
        RestControlRejection.StaleOwner -> STALE_OWNER
        RestControlRejection.AuthorityFailed -> AUTHORITY_FAILED
        RestControlRejection.PersistenceFailed -> PERSISTENCE_FAILED
        RestControlRejection.PostWriteDrift -> POST_WRITE_DRIFT
    }
}
