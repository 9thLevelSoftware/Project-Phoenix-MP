package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.util.withPlatformLock
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MachineTeardownState {
    data object Ready : MachineTeardownState
    data class TearingDown(val executionId: Long, val attempt: Int) : MachineTeardownState
    data class RecoveryRequired(val executionId: Long) : MachineTeardownState
}

internal enum class TeardownFailureReason {
    RESET_FAILED,
    TIMED_OUT,
    DISCONNECTED,
}

internal enum class TeardownReason {
    AUTO_COMPLETE,
    MANUAL_STOP,
    STOP_SET,
    SKIP_EXERCISE,
    END_WORKOUT,
    WARMUP_TRANSITION,
    EXERCISE_JUMP,
    RECOVERY,
}

internal enum class ExecutionInvalidationReason {
    END_WORKOUT,
    STOP_SET,
    SKIP_EXERCISE,
    CLEANUP,
    START_FAILED,
    RESET_FOR_NEW_WORKOUT,
}

internal enum class TerminalPath {
    AUTO_COMPLETE,
    MANUAL_STOP,
    END_WORKOUT,
}

internal data class ExecutionSeed(
    val sessionId: String,
    val profileId: String,
    val requiresMachine: Boolean,
    val workingRepTarget: Int,
    val isBodyweight: Boolean = false,
    val isJustLift: Boolean = false,
    val isAmrap: Boolean = false,
    val isTimedCable: Boolean = false,
)

internal data class ExecutionLease(
    val executionId: Long,
    val sessionId: String,
    val profileId: String,
    val requiresMachine: Boolean,
    val workingRepTarget: Int,
    val isBodyweight: Boolean,
    val isJustLift: Boolean,
    val isAmrap: Boolean,
    val isTimedCable: Boolean,
    val activationCutoverTimestampMs: Long? = null,
)

internal sealed interface PersistenceClaimResult {
    data object Claimed : PersistenceClaimResult
    data object DuplicateInProgress : PersistenceClaimResult
    data object AlreadyPersisted : PersistenceClaimResult
}

internal sealed interface CompletionClaimResult {
    data class Claimed(val completion: SetExecutionCompletion) : CompletionClaimResult
    data class AlreadyClaimed(val completion: SetExecutionCompletion) : CompletionClaimResult
    data object Rejected : CompletionClaimResult
}

internal enum class PersistenceClaimStatus {
    UNCLAIMED,
    IN_PROGRESS,
    PERSISTED,
    FAILED,
}

internal data class RecoveryAttempt(
    val lease: ExecutionLease,
    val attempt: Int,
)

internal data class ResetCleanupToken(
    val lease: ExecutionLease?,
    val executionGeneration: Long,
)

internal class WorkoutExecutionGuard(
    private val logger: (eventType: String, details: String) -> Unit = { _, _ -> },
) {
    internal val repFreshnessGate = RepNotificationFreshnessGate()
    private val executionSequence = atomic(0L)
    private val currentLeaseRef = atomic<ExecutionLease?>(null)
    private val invalidatedLeaseRef = atomic<ExecutionLease?>(null)
    private val teardownLock = Any()
    private val persistenceLock = Any()
    private val persistedClaims = LinkedHashMap<String, PersistenceClaimStatus>()

    private var teardownLease: ExecutionLease? = null
    private var teardownAttempt = 0
    private var teardownFailureReason: TeardownFailureReason? = null
    private var completionJob: Job? = null
    private var completionJobLease: ExecutionLease? = null
    private var alertDeliveryJob: Job? = null
    private var alertDeliveryJobLease: ExecutionLease? = null
    private var teardownJob: Job? = null
    private var teardownJobLease: ExecutionLease? = null
    private var completionClaim: SetExecutionCompletion? = null
    private var jobOwnershipClosed = false
    private val _machineTeardownState = MutableStateFlow<MachineTeardownState>(MachineTeardownState.Ready)

    val machineTeardownState: StateFlow<MachineTeardownState> = _machineTeardownState.asStateFlow()
    val currentLease: ExecutionLease?
        get() = currentLeaseRef.value

    fun captureResetCleanupToken(): ResetCleanupToken = withPlatformLock(teardownLock) {
        ResetCleanupToken(
            lease = currentLeaseRef.value,
            executionGeneration = executionSequence.value,
        )
    }

    fun beginExecution(seed: ExecutionSeed): Result<ExecutionLease> = withPlatformLock(teardownLock) {
        if (seed.requiresMachine && _machineTeardownState.value !is MachineTeardownState.Ready) {
            return@withPlatformLock Result.failure(
                IllegalStateException("Machine teardown must be ready before beginning an execution"),
            )
        }

        val lease = ExecutionLease(
            executionId = executionSequence.incrementAndGet(),
            sessionId = seed.sessionId,
            profileId = seed.profileId,
            requiresMachine = seed.requiresMachine,
            workingRepTarget = seed.workingRepTarget,
            isBodyweight = seed.isBodyweight,
            isJustLift = seed.isJustLift,
            isAmrap = seed.isAmrap,
            isTimedCable = seed.isTimedCable,
        )
        currentLeaseRef.value?.let { outgoingLease ->
            if (sameIdentity(completionClaim?.lease, outgoingLease)) {
                completionClaim = null
            }
            cancelPresentationJobsLocked(outgoingLease)
            currentLeaseRef.value = null
        }
        invalidatedLeaseRef.value = null
        currentLeaseRef.value = lease
        completionClaim = null
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun")
        Result.success(lease)
    }

    fun activate(lease: ExecutionLease, cutoverTimestampMs: Long): ExecutionLease? = withPlatformLock(teardownLock) {
        val current = currentLeaseRef.value ?: return@withPlatformLock null
        if (!sameIdentity(current, lease)) return@withPlatformLock null
        val activated = current.copy(activationCutoverTimestampMs = cutoverTimestampMs)
        currentLeaseRef.value = activated
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=activated")
        activated
    }

    fun isCurrent(lease: ExecutionLease): Boolean = currentLeaseRef.value?.let { current ->
        sameIdentity(current, lease)
    } == true

    fun invalidateCurrent(reason: ExecutionInvalidationReason): ExecutionLease? = withPlatformLock(teardownLock) {
        val invalidated = currentLeaseRef.value ?: return null
        cancelPresentationJobsLocked(invalidated)
        currentLeaseRef.value = null
        if (sameIdentity(completionClaim?.lease, invalidated)) {
            completionClaim = null
        }
        invalidatedLeaseRef.value = invalidated
        log(
            LogEventType.WORKOUT_EXECUTION,
            "executionId=${invalidated.executionId},sessionId=${invalidated.sessionId},transition=invalidated,reason=$reason",
        )
        invalidated
    }

    fun invalidate(lease: ExecutionLease, reason: ExecutionInvalidationReason): Boolean = withPlatformLock(teardownLock) {
        val current = currentLeaseRef.value ?: return@withPlatformLock false
        if (!sameIdentity(current, lease)) return@withPlatformLock false
        cancelPresentationJobsLocked(current)
        currentLeaseRef.value = null
        if (sameIdentity(completionClaim?.lease, current)) {
            completionClaim = null
        }
        invalidatedLeaseRef.value = current
        log(
            LogEventType.WORKOUT_EXECUTION,
            "executionId=${current.executionId},sessionId=${current.sessionId},transition=invalidated,reason=$reason",
        )
        true
    }

    fun commitResetCleanupIfNoSuccessor(
        token: ResetCleanupToken,
        invalidatedLease: ExecutionLease?,
        block: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (currentLeaseRef.value != null) return@withPlatformLock false
        if (executionSequence.value != token.executionGeneration) return@withPlatformLock false
        if (token.lease == null && invalidatedLease != null) return@withPlatformLock false
        if (token.lease != null && (invalidatedLease == null || !sameIdentity(token.lease, invalidatedLease))) {
            return@withPlatformLock false
        }
        block()
        true
    }

    fun commitIfCurrent(
        lease: ExecutionLease,
        beforeCommit: () -> Unit = {},
        block: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (!sameIdentity(currentLeaseRef.value, lease)) return@withPlatformLock false
        beforeCommit()
        if (!sameIdentity(currentLeaseRef.value, lease)) return@withPlatformLock false
        block()
        true
    }

    fun claimCompletion(completion: SetExecutionCompletion): CompletionClaimResult = withPlatformLock(teardownLock) {
        if (!sameIdentity(currentLeaseRef.value, completion.lease)) {
            return@withPlatformLock CompletionClaimResult.Rejected
        }
        completionClaim?.let { claimed ->
            return@withPlatformLock if (sameIdentity(claimed.lease, completion.lease)) {
                CompletionClaimResult.AlreadyClaimed(claimed)
            } else {
                CompletionClaimResult.Rejected
            }
        }
        completionClaim = completion
        CompletionClaimResult.Claimed(completion)
    }

    fun tryClaimCompletion(completion: SetExecutionCompletion): Boolean = claimCompletion(completion) is CompletionClaimResult.Claimed

    fun claimedCompletion(lease: ExecutionLease): SetExecutionCompletion? = withPlatformLock(teardownLock) {
        completionClaim?.takeIf { claimed -> sameIdentity(claimed.lease, lease) }
    }

    fun releaseCompletionClaim(lease: ExecutionLease) = withPlatformLock(teardownLock) {
        if (sameIdentity(completionClaim?.lease, lease)) {
            completionClaim = null
        }
    }

    fun attachCompletionJob(lease: ExecutionLease, job: Job): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed) {
            return@withPlatformLock false
        }
        if (!isCurrent(lease) || completionJob != null) {
            return@withPlatformLock false
        }
        completionJob = job
        completionJobLease = lease
        true
    }

    fun clearCompletionJobIfOwned(lease: ExecutionLease) = withPlatformLock(teardownLock) {
        if (sameIdentity(completionJobLease, lease)) {
            completionJob = null
            completionJobLease = null
        }
    }

    fun attachAlertDeliveryJob(lease: ExecutionLease, job: Job): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || !sameIdentity(currentLeaseRef.value, lease) || alertDeliveryJob != null) {
            return@withPlatformLock false
        }
        alertDeliveryJob = job
        alertDeliveryJobLease = lease
        true
    }

    fun clearAlertDeliveryJobIfOwned(lease: ExecutionLease, job: Job) = withPlatformLock(teardownLock) {
        if (sameIdentity(alertDeliveryJobLease, lease) && alertDeliveryJob === job) {
            alertDeliveryJob = null
            alertDeliveryJobLease = null
        }
    }

    fun cancelPresentationJobsFor(lease: ExecutionLease) {
        val ownedJobs = withPlatformLock(teardownLock) {
            buildList {
                if (sameIdentity(completionJobLease, lease)) {
                    completionJobLease = null
                    completionJob?.let(::add)
                    completionJob = null
                }
                if (sameIdentity(alertDeliveryJobLease, lease)) {
                    alertDeliveryJobLease = null
                    alertDeliveryJob?.let(::add)
                    alertDeliveryJob = null
                }
            }
        }
        ownedJobs.forEach(Job::cancel)
    }

    private fun cancelPresentationJobsLocked(lease: ExecutionLease) {
        val ownedJobs = buildList {
            if (sameIdentity(completionJobLease, lease)) {
                completionJobLease = null
                completionJob?.let(::add)
                completionJob = null
            }
            if (sameIdentity(alertDeliveryJobLease, lease)) {
                alertDeliveryJobLease = null
                alertDeliveryJob?.let(::add)
                alertDeliveryJob = null
            }
        }
        // Job.cancel() is non-suspending. Marking these jobs cancelled before publishing
        // a successor closes the stale-delivery window without waiting under this lock.
        ownedJobs.forEach(Job::cancel)
    }

    fun cancelAllOwnedJobs() {
        val ownedJobs = withPlatformLock(teardownLock) {
            jobOwnershipClosed = true
            val jobs = listOfNotNull(completionJob, alertDeliveryJob, teardownJob)
            completionJob = null
            completionJobLease = null
            alertDeliveryJob = null
            alertDeliveryJobLease = null
            teardownJob = null
            teardownJobLease = null
            jobs
        }
        ownedJobs.forEach(Job::cancel)
    }

    fun beginTeardown(lease: ExecutionLease, attempt: Int = 1): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed) return@withPlatformLock false
        val ownsExecution = isCurrent(lease) || sameIdentity(invalidatedLeaseRef.value, lease)
        if (attempt < 1 || _machineTeardownState.value !is MachineTeardownState.Ready || !ownsExecution) {
            return@withPlatformLock false
        }
        if (sameIdentity(invalidatedLeaseRef.value, lease)) {
            invalidatedLeaseRef.value = null
        }
        teardownLease = lease
        teardownAttempt = attempt
        teardownFailureReason = null
        teardownJob = null
        teardownJobLease = null
        _machineTeardownState.value = MachineTeardownState.TearingDown(lease.executionId, attempt)
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun,attempt=$attempt")
        true
    }

    fun attachTeardownJob(lease: ExecutionLease, job: Job): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed) {
            return@withPlatformLock false
        }
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.TearingDown || !sameIdentity(teardownLease, lease)) {
            return@withPlatformLock false
        }
        if (teardownJob != null) {
            return@withPlatformLock false
        }
        teardownJob = job
        teardownJobLease = lease
        true
    }

    fun clearTeardownJobIfOwned(lease: ExecutionLease) = withPlatformLock(teardownLock) {
        if (sameIdentity(teardownJobLease, lease)) {
            teardownJob = null
            teardownJobLease = null
        }
    }

    fun markTeardownReady(lease: ExecutionLease): Boolean = withPlatformLock(teardownLock) {
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.TearingDown || !sameIdentity(teardownLease, lease)) {
            return@withPlatformLock false
        }
        teardownLease = null
        teardownAttempt = 0
        teardownFailureReason = null
        if (sameIdentity(invalidatedLeaseRef.value, lease)) {
            invalidatedLeaseRef.value = null
        }
        _machineTeardownState.value = MachineTeardownState.Ready
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=ready,attempt=${state.attempt}")
        true
    }

    fun markRecoveryRequired(lease: ExecutionLease, reason: TeardownFailureReason): Boolean = withPlatformLock(teardownLock) {
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.TearingDown || !sameIdentity(teardownLease, lease)) {
            return@withPlatformLock false
        }
        teardownFailureReason = reason
        _machineTeardownState.value = MachineTeardownState.RecoveryRequired(lease.executionId)
        log(
            LogEventType.WORKOUT_TEARDOWN,
            "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=recovery_required,reason=$reason,attempt=${state.attempt}",
        )
        true
    }

    fun beginRecoveryAttempt(): RecoveryAttempt? = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed) return@withPlatformLock null
        val lease = teardownLease ?: return@withPlatformLock null
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.RecoveryRequired || state.executionId != lease.executionId) {
            return@withPlatformLock null
        }
        val attempt = teardownAttempt + 1
        teardownAttempt = attempt
        _machineTeardownState.value = MachineTeardownState.TearingDown(lease.executionId, attempt)
        log(
            LogEventType.WORKOUT_TEARDOWN,
            "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=recovery_attempt,attempt=$attempt,previousReason=$teardownFailureReason",
        )
        RecoveryAttempt(lease, attempt)
    }

    fun claimPersistence(sessionId: String, path: TerminalPath): PersistenceClaimResult = withPlatformLock(persistenceLock) {
        when (persistedClaims[sessionId]) {
            null, PersistenceClaimStatus.UNCLAIMED, PersistenceClaimStatus.FAILED -> {
                persistedClaims[sessionId] = PersistenceClaimStatus.IN_PROGRESS
                log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=claimed,path=$path")
                PersistenceClaimResult.Claimed
            }

            PersistenceClaimStatus.IN_PROGRESS -> PersistenceClaimResult.DuplicateInProgress

            PersistenceClaimStatus.PERSISTED -> PersistenceClaimResult.AlreadyPersisted
        }
    }

    fun persistenceClaimStatus(sessionId: String): PersistenceClaimStatus = withPlatformLock(persistenceLock) {
        persistedClaims[sessionId] ?: PersistenceClaimStatus.UNCLAIMED
    }

    fun markPersistenceSucceeded(sessionId: String) = withPlatformLock(persistenceLock) {
        if (persistedClaims[sessionId] == PersistenceClaimStatus.IN_PROGRESS) {
            persistedClaims.remove(sessionId)
            persistedClaims[sessionId] = PersistenceClaimStatus.PERSISTED
            log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=persisted")
        }
    }

    fun markPersistenceFailed(sessionId: String) = withPlatformLock(persistenceLock) {
        if (persistedClaims[sessionId] == PersistenceClaimStatus.IN_PROGRESS) {
            persistedClaims[sessionId] = PersistenceClaimStatus.FAILED
            log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=failed")
        }
    }

    fun prunePersistedClaims(retainNewest: Int = 32) = withPlatformLock(persistenceLock) {
        val persistedSessionIds = persistedClaims.filterValues { it == PersistenceClaimStatus.PERSISTED }.keys
        val toRemove = (persistedSessionIds.size - retainNewest.coerceAtLeast(0)).coerceAtLeast(0)
        persistedSessionIds.take(toRemove).forEach(persistedClaims::remove)
    }

    private fun sameIdentity(first: ExecutionLease?, second: ExecutionLease): Boolean = first?.executionId == second.executionId && first.sessionId == second.sessionId

    private fun log(eventType: String, details: String) {
        try {
            logger(eventType, details)
        } catch (_: Throwable) {
            // Diagnostics must never alter completed authority transitions.
        }
    }
}
