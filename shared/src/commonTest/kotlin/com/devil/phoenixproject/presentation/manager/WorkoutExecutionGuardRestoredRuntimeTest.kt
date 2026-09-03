package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class WorkoutExecutionGuardRestoredRuntimeTest {
    @Test
    fun `ready restored owner begins exactly one successor`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()

        assertTrue(guard.markRestoredTeardownReady(owner))

        val successor = guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.getOrThrow()

        assertEquals("successor", successor.sessionId)
        assertTrue(guard.isCurrent(successor))
        assertFalse(guard.isRestoredRuntimeCurrent(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isFailure)
    }

    @Test
    fun `wrong and consumed restored owners cannot mutate teardown or begin a successor`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()
        val wrongOwner = owner.copy(id = owner.id + 1L)

        assertFalse(guard.markRestoredTeardownReady(wrongOwner))
        assertFalse(guard.markRestoredRecoveryRequired(wrongOwner, TeardownFailureReason.RESET_FAILED))
        assertTrue(guard.markRestoredRecoveryRequired(owner, TeardownFailureReason.RESET_FAILED))
        assertNull(guard.beginRestoredRecoveryAttempt(wrongOwner))

        assertNotNull(guard.beginRestoredRecoveryAttempt(owner))
        assertFalse(guard.markRestoredTeardownReady(wrongOwner))
        assertTrue(guard.markRestoredTeardownReady(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(wrongOwner, executionSeed()) { true }.isFailure)

        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isSuccess)
        assertFalse(guard.markRestoredTeardownReady(owner))
        assertNull(guard.beginRestoredRecoveryAttempt(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isFailure)
    }

    @Test
    fun `candidate rejection retains exact restored owner for a later successor`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()
        assertTrue(guard.markRestoredTeardownReady(owner))

        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { false }.isFailure)

        assertTrue(guard.isRestoredRuntimeCurrent(owner))
        assertTrue(guard.isRestoredTeardownReady(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isSuccess)
    }

    @Test
    fun `failed successor installation retains exact restored owner for a later successor`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()
        assertTrue(guard.markRestoredTeardownReady(owner))
        val competingClaim = assertNotNull(
            guard.beginRecoveryPublication(
                expectedLease = null,
                expectedSupersessionEpoch = guard.captureRecoveryPublicationEpoch(),
                allowNoCurrentAfterOwnedInvalidation = false,
            ),
        )

        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isFailure)
        assertTrue(guard.commitRecoveryPublication(competingClaim) {})

        assertTrue(guard.isRestoredRuntimeCurrent(owner))
        assertTrue(guard.isRestoredTeardownReady(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isSuccess)
    }

    @Test
    fun `revoked restored owner cannot start but may retry only its unfinished teardown`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()

        assertTrue(guard.revokeRestoredRuntime(owner))
        assertFalse(guard.isRestoredRuntimeCurrent(owner))
        assertFalse(guard.isRestoredTeardownReady(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isFailure)

        val initialReset = Job()
        assertTrue(guard.attachRestoredTeardownJob(owner, initialReset))
        assertTrue(guard.markRestoredRecoveryRequired(owner, TeardownFailureReason.RESET_FAILED))
        val retry = assertNotNull(guard.beginRestoredRecoveryAttempt(owner))
        assertTrue(retry.attempt > 1)
        val retryReset = Job()
        assertTrue(guard.attachRestoredTeardownJob(owner, retryReset))
        assertTrue(guard.markRestoredTeardownReady(owner))
        assertFalse(guard.isRestoredTeardownReady(owner))
        assertTrue(guard.beginRestoredSuccessorExecution(owner, executionSeed()) { true }.isFailure)
        assertTrue(guard.beginExecution(executionSeed()).isSuccess)
    }

    @Test
    fun `configuration supersession revokes actions but preserves only unfinished teardown recovery`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()

        guard.mutateConfigurationInputs {}

        assertFalse(guard.isRestoredRuntimeCurrent(owner))
        assertFalse(guard.isRestoredTeardownReady(owner))
        val initialReset = Job()
        assertTrue(guard.attachRestoredTeardownJob(owner, initialReset))
        assertTrue(guard.markRestoredRecoveryRequired(owner, TeardownFailureReason.RESET_FAILED))
        val retry = assertNotNull(guard.beginRestoredRecoveryAttempt(owner))
        val retryReset = Job()
        assertTrue(guard.attachRestoredTeardownJob(retry.owner, retryReset))
        assertTrue(guard.markRestoredTeardownReady(retry.owner))
        assertTrue(guard.beginExecution(executionSeed()).isSuccess)
    }

    @Test
    fun `ready restored records cannot survive configuration or recovery epoch supersession`() {
        listOf<(WorkoutExecutionGuard) -> Unit>(
            { guard -> guard.mutateConfigurationInputs {} },
            { guard -> guard.supersedeRecoveryPublication() },
        ).forEach { supersede ->
            val guard = WorkoutExecutionGuard()
            val owner = guard.publishRestoredOwner()
            assertTrue(guard.markRestoredTeardownReady(owner))

            supersede(guard)

            assertFalse(guard.isRestoredRuntimeCurrent(owner))
            assertFalse(guard.isRestoredTeardownReady(owner))
            assertTrue(guard.beginExecution(executionSeed()).isSuccess)
        }
    }

    @Test
    fun `exact current restored owner publishes timer while tearing down or ready`() {
        listOf(false, true).forEach { markReady ->
            val state = if (markReady) "ready" else "tearing down"
            val guard = WorkoutExecutionGuard()
            val owner = guard.publishRestoredOwner()
            if (markReady) assertTrue(guard.markRestoredTeardownReady(owner))
            var candidateChecks = 0
            var publications = 0

            assertNull(guard.currentLease, "$state restored owner must not coexist with a lease")
            assertTrue(
                guard.commitRestoredTimerPublication(
                    owner = owner,
                    candidateStillCurrent = {
                        candidateChecks += 1
                        true
                    },
                    publish = { publications += 1 },
                ),
                "$state exact owner should publish",
            )

            assertEquals(1, candidateChecks, "$state candidate should be checked exactly once")
            assertEquals(1, publications, "$state publication should run exactly once")
            assertTrue(guard.isRestoredRuntimeCurrent(owner), "$state publication must not consume its owner")
            assertNull(guard.currentLease, "$state publication must not create a lease")
        }
    }

    @Test
    fun `wrong stale revoked and superseded restored owners cannot publish timers`() {
        val wrongGuard = WorkoutExecutionGuard()
        val exactOwner = wrongGuard.publishRestoredOwner()
        assertTimerPublicationRejected(
            label = "wrong token",
            guard = wrongGuard,
            owner = exactOwner.copy(id = exactOwner.id + 1L),
        )
        assertTrue(wrongGuard.isRestoredRuntimeCurrent(exactOwner))

        val staleGuard = WorkoutExecutionGuard()
        val staleOwner = staleGuard.publishRestoredOwner()
        assertTrue(staleGuard.markRestoredTeardownReady(staleOwner))
        assertTrue(staleGuard.revokeRestoredRuntime(staleOwner))
        val newerOwner = staleGuard.publishRestoredOwner()
        assertTimerPublicationRejected("stale token", staleGuard, staleOwner)
        assertTrue(staleGuard.isRestoredRuntimeCurrent(newerOwner))

        val revokedGuard = WorkoutExecutionGuard()
        val revokedOwner = revokedGuard.publishRestoredOwner()
        assertTrue(revokedGuard.revokeRestoredRuntime(revokedOwner))
        assertTimerPublicationRejected("revoked token", revokedGuard, revokedOwner)

        val recoverySupersededGuard = WorkoutExecutionGuard()
        val recoverySupersededOwner = recoverySupersededGuard.publishRestoredOwner()
        recoverySupersededGuard.supersedeRecoveryPublication()
        assertTimerPublicationRejected(
            "recovery-epoch-superseded token",
            recoverySupersededGuard,
            recoverySupersededOwner,
        )

        val configurationSupersededGuard = WorkoutExecutionGuard()
        val configurationSupersededOwner = configurationSupersededGuard.publishRestoredOwner()
        configurationSupersededGuard.mutateConfigurationInputs {}
        assertTimerPublicationRejected(
            "configuration-epoch-superseded token",
            configurationSupersededGuard,
            configurationSupersededOwner,
        )
    }

    @Test
    fun `supersession during restored timer candidate validation prevents publication`() {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()
        var candidateChecks = 0
        var publications = 0

        assertFalse(
            guard.commitRestoredTimerPublication(
                owner = owner,
                candidateStillCurrent = {
                    candidateChecks += 1
                    guard.supersedeRecoveryPublication()
                    true
                },
                publish = { publications += 1 },
            ),
        )

        assertEquals(1, candidateChecks)
        assertEquals(0, publications)
        assertFalse(guard.isRestoredRuntimeCurrent(owner))
    }

    @Test
    fun `restored timer publication callback is atomic against competing supersession`() = runBlocking {
        val guard = WorkoutExecutionGuard()
        val owner = guard.publishRestoredOwner()
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val supersessionStarted = CompletableDeferred<Unit>()
        val supersessionFinished = CompletableDeferred<Unit>()
        var ownerWasCurrentBeforeCallbackReturned = false
        var publications = 0

        val publication = async(Dispatchers.Default) {
            guard.commitRestoredTimerPublication(
                owner = owner,
                candidateStillCurrent = { true },
                publish = {
                    callbackEntered.complete(Unit)
                    runBlocking { releaseCallback.await() }
                    ownerWasCurrentBeforeCallbackReturned = guard.isRestoredRuntimeCurrent(owner)
                    publications += 1
                },
            )
        }

        withTimeout(2_000) { callbackEntered.await() }
        val supersession = async(Dispatchers.Default) {
            supersessionStarted.complete(Unit)
            guard.supersedeRecoveryPublication()
            supersessionFinished.complete(Unit)
        }

        try {
            withTimeout(2_000) { supersessionStarted.await() }
            repeat(100) { yield() }
            assertFalse(
                supersessionFinished.isCompleted,
                "supersession must wait until the publication callback leaves the guard",
            )
        } finally {
            releaseCallback.complete(Unit)
        }

        assertTrue(publication.await())
        withTimeout(2_000) { supersession.await() }
        assertTrue(ownerWasCurrentBeforeCallbackReturned)
        assertEquals(1, publications)
        assertFalse(guard.isRestoredRuntimeCurrent(owner))
    }

    private fun WorkoutExecutionGuard.publishRestoredOwner(): RestoredRuntimeOwnerToken {
        val recoveryEpoch = captureRecoveryPublicationEpoch()
        val configurationEpoch = captureConfigurationInputEpoch()
        val claim = assertNotNull(
            beginRecoveryPublication(
                expectedLease = null,
                expectedSupersessionEpoch = recoveryEpoch,
                allowNoCurrentAfterOwnedInvalidation = false,
            ),
        )
        return assertNotNull(
            commitRestoredRuntimePublication(
                claim = claim,
                seed = RestoredTeardownSeed(
                    sourceExecutionId = 41L,
                    sourceStableSessionId = "stable-source",
                    profileId = "profile-a",
                    requiresMachine = true,
                ),
                expectedConfigurationInputEpoch = configurationEpoch,
            ) {},
        )
    }

    private fun assertTimerPublicationRejected(
        label: String,
        guard: WorkoutExecutionGuard,
        owner: RestoredRuntimeOwnerToken,
    ) {
        var publications = 0

        assertFalse(
            guard.commitRestoredTimerPublication(
                owner = owner,
                candidateStillCurrent = { true },
                publish = { publications += 1 },
            ),
            "$label must not publish",
        )
        assertEquals(0, publications, "$label callback must not run")
    }

    private fun executionSeed() = ExecutionSeed(
        sessionId = "successor",
        profileId = "profile-a",
        requiresMachine = true,
        workingRepTarget = 10,
    )
}
