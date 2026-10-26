package com.devil.phoenixproject.presentation.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSessionExitPolicyTest {
    @Test
    fun activeSetCannotPromptForSessionExit() {
        assertFalse(canConfirmTvSessionExit(machineSetActive = true, teardownReady = true))
    }

    @Test
    fun teardownRecoveryCannotPromptForSessionExit() {
        assertFalse(canConfirmTvSessionExit(machineSetActive = false, teardownReady = false))
    }

    @Test
    fun safeConfirmationRequiresInactiveSetAndReadyTeardown() {
        assertTrue(canConfirmTvSessionExit(machineSetActive = false, teardownReady = true))
    }
}
