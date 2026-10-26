package com.devil.phoenixproject.presentation.tv

/** Remote inputs that affect an active workout session. */
enum class TvRemoteInput {
    PLAY_PAUSE,
    MENU,
}

/** Only the shared workout stop path may stop machine load. */
enum class TvRemoteAction {
    NO_OP,
    STOP_ACTIVE_SET,
    STOP_ACTIVE_SET_THEN_CONFIRM_SESSION_EXIT,
    CONFIRM_SESSION_EXIT,
}

/**
 * Maps Siri Remote actions to safe app-level actions. This policy never emits
 * machine packets: callers route stop actions through MainViewModel.stopWorkout.
 */
fun resolveTvRemoteAction(
    input: TvRemoteInput,
    machineSetActive: Boolean,
    sessionOpen: Boolean,
): TvRemoteAction = when {
    input == TvRemoteInput.PLAY_PAUSE && machineSetActive -> TvRemoteAction.STOP_ACTIVE_SET
    input == TvRemoteInput.PLAY_PAUSE -> TvRemoteAction.NO_OP
    machineSetActive && sessionOpen -> TvRemoteAction.STOP_ACTIVE_SET_THEN_CONFIRM_SESSION_EXIT
    sessionOpen -> TvRemoteAction.CONFIRM_SESSION_EXIT
    else -> TvRemoteAction.NO_OP
}

/** A session-exit prompt is safe only after the active set is gone and teardown is ready. */
fun canConfirmTvSessionExit(machineSetActive: Boolean, teardownReady: Boolean): Boolean =
    !machineSetActive && teardownReady
