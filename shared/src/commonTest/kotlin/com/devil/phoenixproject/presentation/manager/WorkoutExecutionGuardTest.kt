package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutExecutionGuardTest {

    @Test
    fun `throwing diagnostics do not change begin or persistence claim authority`() {
        val guard = WorkoutExecutionGuard { _, _ -> error("diagnostics unavailable") }

        val lease = guard.beginExecution(seed("session-a")).getOrThrow()

        assertTrue(guard.isCurrent(lease))
        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE),
        )
        assertIs<PersistenceClaimResult.DuplicateInProgress>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `invalidating execution A makes every A lease check fail`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()

        guard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT)
        val leaseB = guard.beginExecution(seed("session-b")).getOrThrow()

        assertFalse(guard.isCurrent(leaseA))
        assertTrue(guard.isCurrent(leaseB))
        assertTrue(leaseB.executionId > leaseA.executionId)
    }

    @Test
    fun `stale exact lease invalidation cannot clear a newer execution`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        guard.invalidateCurrent(ExecutionInvalidationReason.STOP_SET)
        val leaseB = guard.beginExecution(seed("session-b")).getOrThrow()

        assertFalse(guard.invalidate(leaseA, ExecutionInvalidationReason.START_FAILED))
        assertTrue(guard.isCurrent(leaseB))
        assertTrue(guard.invalidate(leaseB, ExecutionInvalidationReason.START_FAILED))
        assertFalse(guard.isCurrent(leaseB))
    }

    @Test
    fun `activation rejects a lease that is no longer current`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        guard.invalidateCurrent(ExecutionInvalidationReason.STOP_SET)
        guard.beginExecution(seed("session-b")).getOrThrow()

        assertNull(guard.activate(leaseA, cutoverTimestampMs = 42L))
    }

    @Test
    fun `execution cannot begin until active machine teardown becomes ready`() {
        val guard = WorkoutExecutionGuard()
        val lease = guard.beginExecution(seed("session-a")).getOrThrow()

        assertTrue(guard.beginTeardown(lease))
        assertIs<MachineTeardownState.TearingDown>(guard.machineTeardownState.value)
        assertTrue(guard.beginExecution(seed("session-b")).isFailure)
        assertTrue(guard.markTeardownReady(lease))
        assertIs<MachineTeardownState.Ready>(guard.machineTeardownState.value)
        assertTrue(guard.beginExecution(seed("session-b")).isSuccess)
    }

    @Test
    fun `recovery required teardown retries from the retained lease`() {
        val guard = WorkoutExecutionGuard()
        val lease = guard.beginExecution(seed("session-a")).getOrThrow()
        guard.beginTeardown(lease, attempt = 1)

        assertTrue(guard.markRecoveryRequired(lease, TeardownFailureReason.TIMED_OUT))
        assertIs<MachineTeardownState.RecoveryRequired>(guard.machineTeardownState.value)
        val recovery = guard.beginRecoveryAttempt()

        assertEquals(lease, recovery?.lease)
        assertEquals(2, recovery?.attempt)
        assertIs<MachineTeardownState.TearingDown>(guard.machineTeardownState.value)
    }

    @Test
    fun `invalidated teardown lease can still complete machine cleanup`() {
        val guard = WorkoutExecutionGuard()
        val lease = guard.beginExecution(seed("session-a")).getOrThrow()
        guard.beginTeardown(lease)
        guard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT)

        assertTrue(guard.markTeardownReady(lease))
        assertIs<MachineTeardownState.Ready>(guard.machineTeardownState.value)
    }

    @Test
    fun `only one terminal path claims a stable session id`() {
        val guard = WorkoutExecutionGuard()

        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE),
        )
        assertIs<PersistenceClaimResult.DuplicateInProgress>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )

        guard.markPersistenceSucceeded("session-a")
        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `failed persistence claim can retry with the same stable session id`() {
        val guard = WorkoutExecutionGuard()
        guard.claimPersistence("session-a", TerminalPath.MANUAL_STOP)
        guard.markPersistenceFailed("session-a")

        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `failure notification cannot reopen an already persisted session`() {
        val guard = WorkoutExecutionGuard()
        guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE)
        guard.markPersistenceSucceeded("session-a")

        guard.markPersistenceFailed("session-a")

        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `pruning retains only the newest terminal persisted claims`() {
        val guard = WorkoutExecutionGuard()
        listOf("session-a", "session-b", "session-c").forEach { sessionId ->
            guard.claimPersistence(sessionId, TerminalPath.AUTO_COMPLETE)
            guard.markPersistenceSucceeded(sessionId)
        }

        guard.prunePersistedClaims(retainNewest = 2)

        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-b", TerminalPath.END_WORKOUT),
        )
        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-c", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `pruning persisted claims leaves in progress claims protected`() {
        val guard = WorkoutExecutionGuard()
        guard.claimPersistence("session-in-progress", TerminalPath.AUTO_COMPLETE)
        guard.claimPersistence("session-persisted", TerminalPath.AUTO_COMPLETE)
        guard.markPersistenceSucceeded("session-persisted")

        guard.prunePersistedClaims(retainNewest = 0)

        assertIs<PersistenceClaimResult.DuplicateInProgress>(
            guard.claimPersistence("session-in-progress", TerminalPath.END_WORKOUT),
        )
        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-persisted", TerminalPath.END_WORKOUT),
        )
    }

    private fun seed(sessionId: String) = ExecutionSeed(
        sessionId = sessionId,
        profileId = "profile-$sessionId",
        requiresMachine = true,
        workingRepTarget = 3,
    )
}
