package com.devil.phoenixproject.presentation.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class TvRemoteControlPolicyTest {
    @Test
    fun activePlayPauseStopsTheMachineSetThroughSharedFlow() {
        assertEquals(
            TvRemoteAction.STOP_ACTIVE_SET,
            resolveTvRemoteAction(TvRemoteInput.PLAY_PAUSE, machineSetActive = true, sessionOpen = true),
        )
    }

    @Test
    fun menuDuringActiveSetStopsBeforeRequestingSessionExitConfirmation() {
        assertEquals(
            TvRemoteAction.STOP_ACTIVE_SET_THEN_CONFIRM_SESSION_EXIT,
            resolveTvRemoteAction(TvRemoteInput.MENU, machineSetActive = true, sessionOpen = true),
        )
    }

    @Test
    fun menuBetweenSetsRequestsConfirmationWithoutIssuingMachineCommand() {
        assertEquals(
            TvRemoteAction.CONFIRM_SESSION_EXIT,
            resolveTvRemoteAction(TvRemoteInput.MENU, machineSetActive = false, sessionOpen = true),
        )
    }

    @Test
    fun playPauseWithoutActiveSetDoesNotIssueMachineCommand() {
        assertEquals(
            TvRemoteAction.NO_OP,
            resolveTvRemoteAction(TvRemoteInput.PLAY_PAUSE, machineSetActive = false, sessionOpen = true),
        )
    }

    @Test
    fun menuOnHomeDoesNothing() {
        assertEquals(
            TvRemoteAction.NO_OP,
            resolveTvRemoteAction(TvRemoteInput.MENU, machineSetActive = false, sessionOpen = false),
        )
    }
}
