package com.devil.phoenixproject.presentation.manager

/**
 * Typed receipt for the bounded remote-control seam [DefaultWorkoutSessionManager.endCurrentSetAndPrepareNext].
 *
 * Issue #1391 Phase 1 (signoff decision 2): `APPLIED` may only be reported when [persistence] is
 * [RemotePersistenceOutcome.SUCCEEDED] or [RemotePersistenceOutcome.NOT_REQUIRED], [teardownState]
 * is [RemoteTeardownState.Ready] and the intended state has been observed. `SetSummary` publication
 * is never a receipt; the receipt comes from the commit path keyed by execution identity.
 *
 * FROZEN CONTRACT — packet B1 implements the producing seam exactly against this type;
 * packets B2/B3 consume it. Do not rename members without coordinated scope change.
 */
enum class RemotePersistenceOutcome {
    /** The operation required no save (explicit no-save decision on the commit path). */
    NOT_REQUIRED,

    /** Save still unresolved at result-window end; surfaces as UNKNOWN_RESULT and blocks next start. */
    PENDING,

    /** Exact successful commit observed on the commit path. */
    SUCCEEDED,

    /** Commit failed; surfaces as SAVE_FAILED and blocks next start. */
    FAILED,
}

enum class RemoteTeardownState {
    /** Safe teardown reached Ready. */
    READY,

    /** Teardown still in flight at capture time. */
    PENDING,
}

enum class RemoteSetControlTerminal {
    /** The next set/target is prepared and NOT started. */
    PREPARED,

    /** No successor was prepared (e.g. rest installed, or the run completed). */
    COMPLETE,

    /** The phone requires user input (e.g. JustLift selection cleared, bodyweight entry). */
    PHONE_INPUT_REQUIRED,

    /** The operation was invalidated (authority/context changed, urgent stop preemption). */
    SUPERSEDED,

    /** The operation failed; a stuck/failed save blocks the next watch start. */
    FAILED,
}

enum class RemoteCompletionDecision {
    /** Phone-equivalent Stop-Set save-and-advance attribution. */
    SAVED_AND_ADVANCED,

    /** Phone-equivalent discard-and-retry attribution (e.g. temp_single_* / no working reps). */
    DISCARDED_AND_RETRIED,

    /** No completion decision was reached. */
    NO_COMPLETION,
}

data class RemoteSetControlReceipt(
    val operationId: String,
    val expectedExecutionIdentity: String,
    val observedExecutionIdentity: String?,
    val completionDecision: RemoteCompletionDecision,
    val persistence: RemotePersistenceOutcome,
    val teardownState: RemoteTeardownState,
    val terminal: RemoteSetControlTerminal,
)
