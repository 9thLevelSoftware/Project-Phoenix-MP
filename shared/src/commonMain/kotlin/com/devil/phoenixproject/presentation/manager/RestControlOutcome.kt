package com.devil.phoenixproject.presentation.manager

/**
 * Issue #1360: internal typed completion contract for rest-timer controls
 * (+30s extension, pause/resume, reset).
 *
 * The public control callbacks remain asynchronous `Unit` commands. An
 * [RestControlOutcome.Applied] completion is delivered only after the durable runtime-document
 * commit, ownership revalidation and state publication all succeeded — never on enqueue. A
 * [RestControlOutcome.Rejected] completion names the exact guard that refused the mutation, so a
 * silently refused control can no longer look identical to a dead button. This contract is
 * observability/error-contract only: it never retries, rolls back, or applies display-only
 * optimistic state.
 */
internal sealed interface RestControlOutcome {
    /** The mutation was durably committed, revalidated and published. */
    data object Applied : RestControlOutcome

    /** The mutation was refused; [reason] names the guard that refused it. */
    data class Rejected(val reason: RestControlRejection) : RestControlOutcome
}

/**
 * Reason a rest-control mutation was refused. Mirrors the guard classes that previously
 * early-returned silently inside [ActiveSessionEngine].
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
     * The durable write landed (or may have landed) but ownership/publication drifted before
     * completion (commit status COMMITTED-without-publication or UNKNOWN). Kept distinct from
     * [PersistenceFailed]: a possibly-committed +30/pause must never be blindly retried or
     * rolled back.
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
 * kind, receipt id, owner-generation counter and the phase a rejection happened at — never
 * tokens, profile identifiers, or runtime documents.
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
 * the wire contract. These messages state failure only — never implied success.
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
