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
    POST_WRITE_DRIFT,
}

internal enum class RestControlAction {
    EXTEND,
    TOGGLE_PAUSE,
    RESET,
}

internal enum class RestControlReceiptStage {
    TAP_ENTRY,
    ENGINE_DISPATCH,
    AUTHORITY_ACCEPTED,
    AUTHORITY_REJECTED,
    PERSISTENCE_STARTED,
    PERSISTENCE_NOT_REQUIRED,
    PERSISTENCE_FAILED,
    PERSISTENCE_CONFIRMED,
    PUBLICATION,
    REJECTED,
    RENDERED,
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
