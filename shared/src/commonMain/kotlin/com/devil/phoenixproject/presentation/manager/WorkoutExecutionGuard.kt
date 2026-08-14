package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.util.withPlatformLock
import kotlinx.atomicfu.atomic
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

internal enum class ExecutionInvalidationReason {
    END_WORKOUT,
    STOP_SET,
    SKIP_EXERCISE,
    CLEANUP,
    START_FAILED,
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

internal data class RecoveryAttempt(
    val lease: ExecutionLease,
    val attempt: Int,
)

internal class WorkoutExecutionGuard(
    private val logger: (eventType: String, details: String) -> Unit = { _, _ -> },
) {
    internal val repFreshnessGate = RepNotificationFreshnessGate()
    private val executionSequence = atomic(0L)
    private val currentLeaseRef = atomic<ExecutionLease?>(null)
    private val teardownLock = Any()
    private val persistenceLock = Any()
    private val persistedClaims = LinkedHashMap<String, PersistenceClaimState>()

    private var teardownLease: ExecutionLease? = null
    private var teardownAttempt = 0
    private val _machineTeardownState = MutableStateFlow<MachineTeardownState>(MachineTeardownState.Ready)

    val machineTeardownState: StateFlow<MachineTeardownState> = _machineTeardownState.asStateFlow()
    val currentLease: ExecutionLease?
        get() = currentLeaseRef.value

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
        currentLeaseRef.value = lease
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun")
        Result.success(lease)
    }

    fun activate(lease: ExecutionLease, cutoverTimestampMs: Long): ExecutionLease? {
        while (true) {
            val current = currentLeaseRef.value ?: return null
            if (!sameIdentity(current, lease)) return null
            val activated = current.copy(activationCutoverTimestampMs = cutoverTimestampMs)
            if (currentLeaseRef.compareAndSet(current, activated)) {
                log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=activated")
                return activated
            }
        }
    }

    fun isCurrent(lease: ExecutionLease): Boolean = currentLeaseRef.value?.let { current ->
        sameIdentity(current, lease)
    } == true

    fun invalidateCurrent(reason: ExecutionInvalidationReason): ExecutionLease? {
        val invalidated = currentLeaseRef.getAndSet(null) ?: return null
        log(
            LogEventType.WORKOUT_EXECUTION,
            "executionId=${invalidated.executionId},sessionId=${invalidated.sessionId},transition=invalidated,reason=$reason",
        )
        return invalidated
    }

    fun invalidate(lease: ExecutionLease, reason: ExecutionInvalidationReason): Boolean {
        while (true) {
            val current = currentLeaseRef.value ?: return false
            if (!sameIdentity(current, lease)) return false
            if (currentLeaseRef.compareAndSet(current, null)) {
                log(
                    LogEventType.WORKOUT_EXECUTION,
                    "executionId=${current.executionId},sessionId=${current.sessionId},transition=invalidated,reason=$reason",
                )
                return true
            }
        }
    }

    fun beginTeardown(lease: ExecutionLease, attempt: Int = 1): Boolean = withPlatformLock(teardownLock) {
        if (attempt < 1 || _machineTeardownState.value !is MachineTeardownState.Ready || !isCurrent(lease)) {
            return@withPlatformLock false
        }
        teardownLease = lease
        teardownAttempt = attempt
        _machineTeardownState.value = MachineTeardownState.TearingDown(lease.executionId, attempt)
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun,attempt=$attempt")
        true
    }

    fun markTeardownReady(lease: ExecutionLease): Boolean = withPlatformLock(teardownLock) {
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.TearingDown || !sameIdentity(teardownLease, lease)) {
            return@withPlatformLock false
        }
        teardownLease = null
        teardownAttempt = 0
        _machineTeardownState.value = MachineTeardownState.Ready
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=ready,attempt=${state.attempt}")
        true
    }

    fun markRecoveryRequired(lease: ExecutionLease, reason: TeardownFailureReason): Boolean = withPlatformLock(teardownLock) {
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.TearingDown || !sameIdentity(teardownLease, lease)) {
            return@withPlatformLock false
        }
        _machineTeardownState.value = MachineTeardownState.RecoveryRequired(lease.executionId)
        log(
            LogEventType.WORKOUT_TEARDOWN,
            "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=recovery_required,reason=$reason,attempt=${state.attempt}",
        )
        true
    }

    fun beginRecoveryAttempt(): RecoveryAttempt? = withPlatformLock(teardownLock) {
        val lease = teardownLease ?: return@withPlatformLock null
        val state = _machineTeardownState.value
        if (state !is MachineTeardownState.RecoveryRequired || state.executionId != lease.executionId) {
            return@withPlatformLock null
        }
        val attempt = teardownAttempt + 1
        teardownAttempt = attempt
        _machineTeardownState.value = MachineTeardownState.TearingDown(lease.executionId, attempt)
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=recovery_attempt,attempt=$attempt")
        RecoveryAttempt(lease, attempt)
    }

    fun claimPersistence(sessionId: String, path: TerminalPath): PersistenceClaimResult = withPlatformLock(persistenceLock) {
        when (persistedClaims[sessionId]) {
            null -> {
                persistedClaims[sessionId] = PersistenceClaimState.InProgress
                log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=claimed,path=$path")
                PersistenceClaimResult.Claimed
            }
            PersistenceClaimState.InProgress -> PersistenceClaimResult.DuplicateInProgress
            PersistenceClaimState.Persisted -> PersistenceClaimResult.AlreadyPersisted
        }
    }

    fun markPersistenceSucceeded(sessionId: String) = withPlatformLock(persistenceLock) {
        if (persistedClaims[sessionId] == PersistenceClaimState.InProgress) {
            persistedClaims.remove(sessionId)
            persistedClaims[sessionId] = PersistenceClaimState.Persisted
            log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=persisted")
        }
    }

    fun markPersistenceFailed(sessionId: String) = withPlatformLock(persistenceLock) {
        if (persistedClaims[sessionId] == PersistenceClaimState.InProgress) {
            persistedClaims.remove(sessionId)
            log(LogEventType.WORKOUT_PERSISTENCE, "sessionId=$sessionId,transition=failed")
        }
    }

    fun prunePersistedClaims(retainNewest: Int = 32) = withPlatformLock(persistenceLock) {
        val persistedSessionIds = persistedClaims.filterValues { it == PersistenceClaimState.Persisted }.keys
        val toRemove = (persistedSessionIds.size - retainNewest.coerceAtLeast(0)).coerceAtLeast(0)
        persistedSessionIds.take(toRemove).forEach(persistedClaims::remove)
    }

    private fun sameIdentity(first: ExecutionLease?, second: ExecutionLease): Boolean =
        first?.executionId == second.executionId && first.sessionId == second.sessionId

    private fun log(eventType: String, details: String) {
        try {
            logger(eventType, details)
        } catch (_: Throwable) {
            // Diagnostics must never alter completed authority transitions.
        }
    }

    private enum class PersistenceClaimState {
        InProgress,
        Persisted,
    }
}
