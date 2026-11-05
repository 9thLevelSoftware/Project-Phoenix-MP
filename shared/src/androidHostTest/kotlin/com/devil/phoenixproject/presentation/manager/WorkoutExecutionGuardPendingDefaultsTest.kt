package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `out-of-band write supersedes retained snapshots from earlier executions`() = runTest {
        val guard = WorkoutExecutionGuard()
        val earlier = guard.beginExecution(
            ExecutionSeed(sessionId = "earlier", profileId = "profile-a", requiresMachine = false, workingRepTarget = 5),
        ).getOrThrow()

        guard.persistJustLiftDefaultsOutOfBand("profile-a") {}

        assertNull(
            guard.newestPendingJustLiftDefaultsExecutionId("profile-a", listOf(earlier.executionId)),
            "A snapshot from an execution issued before the out-of-band write must not shadow it",
        )
        assertFalse(
            guard.persistJustLiftDefaultsIfNewer("profile-a", earlier.executionId) {},
            "Retrying the earlier snapshot must not overwrite the out-of-band write",
        )
        assertTrue(
            guard.persistJustLiftDefaultsIfNewer("profile-a", earlier.executionId + 1) {},
            "A later execution still writes",
        )
    }
}
