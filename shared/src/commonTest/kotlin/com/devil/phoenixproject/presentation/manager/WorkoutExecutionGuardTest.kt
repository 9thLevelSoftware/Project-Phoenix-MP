@file:OptIn(kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class)

package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
    fun `reset token rejects cleanup after a successor begins and ends`() {
        val guard = WorkoutExecutionGuard()
        val resetToken = guard.captureResetCleanupToken()
        val leaseB = guard.beginExecution(seed("session-b")).getOrThrow()
        guard.invalidate(leaseB, ExecutionInvalidationReason.RESET_FOR_NEW_WORKOUT)
        var cleanupRan = false

        val committed = guard.commitResetCleanupIfNoSuccessor(resetToken, invalidatedLease = null) {
            cleanupRan = true
        }

        assertFalse(committed)
        assertFalse(cleanupRan)
    }

    @Test
    fun `reset token commits cleanup after A teardown becomes ready without a successor`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        assertTrue(guard.beginTeardown(leaseA))
        val resetToken = guard.captureResetCleanupToken()
        assertTrue(guard.invalidate(leaseA, ExecutionInvalidationReason.RESET_FOR_NEW_WORKOUT))
        assertTrue(guard.markTeardownReady(leaseA))
        var cleanupRan = false

        val committed = guard.commitResetCleanupIfNoSuccessor(resetToken, leaseA) {
            cleanupRan = true
        }

        assertTrue(committed)
        assertTrue(cleanupRan)
    }

    @Test
    fun `invalidation cancels a backpressured alert before publishing authority loss`() = runTest {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        val flow = MutableSharedFlow<Int>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val firstEventReceived = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val deliveryAuthority = mutableListOf<Boolean>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { event ->
                when (event) {
                    1 -> {
                        firstEventReceived.complete(Unit)
                        releaseCollector.await()
                    }

                    3 -> deliveryAuthority += guard.isCurrent(leaseA)
                }
            }
        }
        try {
            flow.emit(1)
            firstEventReceived.await()
            flow.emit(2)
            assertFalse(flow.tryEmit(4), "fixture must hold the flow at capacity")

            val alertJob = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
                flow.emit(3)
            }
            val releaseBeforeCancelJob = object : Job by alertJob {
                override fun cancel(cause: CancellationException?) {
                    releaseCollector.complete(Unit)
                    alertJob.cancel(cause)
                }
            }
            assertTrue(guard.attachAlertDeliveryJob(leaseA, releaseBeforeCancelJob))
            alertJob.start()
            runCurrent()

            assertTrue(guard.invalidate(leaseA, ExecutionInvalidationReason.RESET_FOR_NEW_WORKOUT))
            runCurrent()

            assertEquals(listOf(true), deliveryAuthority)
            assertFalse(guard.isCurrent(leaseA))
        } finally {
            releaseCollector.complete(Unit)
            collectorJob.cancel()
        }
    }

    @Test
    fun `current invalidation and replacement cancel jobs while A remains authoritative`() {
        listOf<(WorkoutExecutionGuard, ExecutionLease) -> Unit>(
            { guard, _ -> guard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT) },
            { guard, _ -> guard.beginExecution(seed("session-b")).getOrThrow() },
        ).forEach { transition ->
            val guard = WorkoutExecutionGuard()
            val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
            val delegate = Job()
            var authorityAtCancellation = false
            val observingJob = object : Job by delegate {
                override fun cancel(cause: CancellationException?) {
                    authorityAtCancellation = guard.isCurrent(leaseA)
                    delegate.cancel(cause)
                }
            }
            assertTrue(guard.attachAlertDeliveryJob(leaseA, observingJob))

            transition(guard, leaseA)

            assertTrue(authorityAtCancellation)
        }
    }

    @Test
    fun `current commit discards A mutation after B begins despite optimistic A validation`() = runTest {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        val validatedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        var mutationOwner: String? = null

        val aCommit = async(Dispatchers.Default) {
            assertTrue(guard.isCurrent(leaseA))
            validatedA.complete(Unit)
            releaseA.await()
            guard.commitIfCurrent(leaseA) {
                mutationOwner = leaseA.sessionId
            }
        }

        validatedA.await()
        val leaseB = guard.beginExecution(seed("session-b")).getOrThrow()
        releaseA.complete(Unit)

        assertFalse(aCommit.await())
        assertNull(mutationOwner)
        assertTrue(
            guard.commitIfCurrent(leaseB) {
                mutationOwner = leaseB.sessionId
            },
        )
        assertEquals("session-b", mutationOwner)
    }

    @Test
    fun `stale A completion claim cannot arm or release B ownership`() = runTest {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(seed("session-a")).getOrThrow()
        val validatedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()

        val staleClaim = async(Dispatchers.Default) {
            assertTrue(guard.isCurrent(leaseA))
            validatedA.complete(Unit)
            releaseA.await()
            guard.tryClaimCompletion(leaseA)
        }

        validatedA.await()
        val leaseB = guard.beginExecution(seed("session-b")).getOrThrow()
        assertTrue(guard.tryClaimCompletion(leaseB))
        releaseA.complete(Unit)

        assertFalse(staleClaim.await())
        guard.releaseCompletionClaim(leaseA)
        assertFalse(guard.tryClaimCompletion(leaseB))
        guard.releaseCompletionClaim(leaseB)
        assertTrue(guard.tryClaimCompletion(leaseB))
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

        assertEquals(PersistenceClaimStatus.UNCLAIMED, guard.persistenceClaimStatus("session-a"))
        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE),
        )
        assertEquals(PersistenceClaimStatus.IN_PROGRESS, guard.persistenceClaimStatus("session-a"))
        assertIs<PersistenceClaimResult.DuplicateInProgress>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )

        guard.markPersistenceSucceeded("session-a")
        assertEquals(PersistenceClaimStatus.PERSISTED, guard.persistenceClaimStatus("session-a"))
        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }

    @Test
    fun `failed persistence claim can retry with the same stable session id`() {
        val guard = WorkoutExecutionGuard()
        guard.claimPersistence("session-a", TerminalPath.MANUAL_STOP)
        guard.markPersistenceFailed("session-a")

        assertEquals(PersistenceClaimStatus.FAILED, guard.persistenceClaimStatus("session-a"))
        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
        assertEquals(PersistenceClaimStatus.IN_PROGRESS, guard.persistenceClaimStatus("session-a"))
    }

    @Test
    fun `failed persistence claim has exactly one atomic reclaimer`() = kotlinx.coroutines.test.runTest {
        val guard = WorkoutExecutionGuard()
        guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE)
        guard.markPersistenceFailed("session-a")
        val start = CompletableDeferred<Unit>()

        val claims = coroutineScope {
            List(16) {
                async(Dispatchers.Default) {
                    start.await()
                    guard.claimPersistence("session-a", TerminalPath.END_WORKOUT)
                }
            }.also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, claims.count { it is PersistenceClaimResult.Claimed })
        assertEquals(15, claims.count { it is PersistenceClaimResult.DuplicateInProgress })
        assertEquals(PersistenceClaimStatus.IN_PROGRESS, guard.persistenceClaimStatus("session-a"))
    }

    @Test
    fun `persistence status is isolated by stable session id`() {
        val guard = WorkoutExecutionGuard()

        guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE)
        guard.markPersistenceFailed("session-a")
        guard.claimPersistence("session-b", TerminalPath.END_WORKOUT)
        guard.markPersistenceSucceeded("session-b")

        assertEquals(PersistenceClaimStatus.FAILED, guard.persistenceClaimStatus("session-a"))
        assertEquals(PersistenceClaimStatus.PERSISTED, guard.persistenceClaimStatus("session-b"))
        assertEquals(PersistenceClaimStatus.UNCLAIMED, guard.persistenceClaimStatus("session-c"))
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
