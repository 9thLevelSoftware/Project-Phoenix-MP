package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.manager.WorkoutExecutionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorkoutExecutionGuardPendingDefaultsTest {
    @Test
    fun `read-side pending snapshots must be newer than durable profile value`() = runTest {
        val guard = WorkoutExecutionGuard()
        assertTrue(guard.persistJustLiftDefaultsIfNewer("profile-a", executionId = 2L) {})

        assertEquals(
            4L,
            guard.newestPendingJustLiftDefaultsExecutionId("profile-a", listOf(1L, 2L, 4L)),
        )
        assertNull(
            guard.newestPendingJustLiftDefaultsExecutionId("profile-a", listOf(1L, 2L)),
            "A retained snapshot at or below the durable execution must not shadow persisted defaults",
        )
        assertEquals(
            1L,
            guard.newestPendingJustLiftDefaultsExecutionId("profile-b", listOf(1L)),
            "The persistence ledger is isolated by profile",
        )
    }
}
