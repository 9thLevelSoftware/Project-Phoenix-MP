package com.devil.phoenixproject.presentation.manager

/** Internal completion contract for asynchronous rest-control mutations. */
internal sealed interface RestControlOutcome {
    data object Applied : RestControlOutcome

    data class Rejected(
        val reason: RestControlRejectionReason,
    ) : RestControlOutcome
}

internal enum class RestControlRejectionReason {
    NOT_RESTING,
    STALE_OWNER,
    AUTHORITY_FAILED,
    PERSISTENCE_FAILED,
    COMMIT_UNCERTAIN,
    POST_WRITE_DRIFT,
}

internal enum class RestControlAction {
    EXTEND,
    TOGGLE_PAUSE,
    RESET,
}

/** Stable owner identity captured at the UI boundary; independent of runtime-document revisions. */
internal sealed interface RestControlOwnerIdentity {
    val generation: Long

    data class LiveExecution(val executionId: Long) : RestControlOwnerIdentity {
        override val generation: Long get() = executionId
    }

    data class RestoredRuntime(val ownerId: Long) : RestControlOwnerIdentity {
        override val generation: Long get() = ownerId
    }
}

/** Ephemeral UI-to-engine correlation context; contains only trace-safe fields. */
internal data class RestControlRequest(
    val actionId: Long,
    val action: RestControlAction,
    val ownerGeneration: Long?,
    val seconds: Int?,
    val ownerIdentity: RestControlOwnerIdentity? = null,
)

/** Privacy-safe failure feedback correlation; never carries display text or runtime identity. */
internal data class RestControlFeedbackEvent(
    val actionId: Long,
    val action: RestControlAction,
    val ownerGeneration: Long?,
    val reason: RestControlRejectionReason,
    val seconds: Int?,
)

internal enum class RestControlReceiptStage {
    TAP_ENTRY,
    ENGINE_DISPATCH,
    AUTHORITY_ACCEPTED,
    AUTHORITY_REJECTED,
    PERSISTENCE_STARTED,
    PERSISTENCE_NOT_REQUIRED,
    PERSISTENCE_FAILED,
    COMMIT_UNCERTAIN,
    PERSISTENCE_CONFIRMED,
    PUBLICATION,
    REJECTED,
    /** A frame boundary followed the outcome; it does not assert the countdown was painted. */
    FRAME_BOUNDARY_OBSERVED,
    FEEDBACK_VISIBLE,
}

/** Ephemeral, privacy-safe rest-control diagnostic; it contains no runtime document or identity token. */
internal data class RestControlReceipt(
    val actionId: Long,
    val action: RestControlAction,
    val stage: RestControlReceiptStage,
    val ownerGeneration: Long?,
    val reason: RestControlRejectionReason? = null,
    val seconds: Int? = null,
)

internal const val REST_CONTROL_FAILURE_FEEDBACK_KEY = "rest_control_update_failed"
