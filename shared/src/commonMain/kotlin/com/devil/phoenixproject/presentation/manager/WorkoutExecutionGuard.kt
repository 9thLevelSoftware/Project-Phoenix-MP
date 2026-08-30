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
    val usesUnlimitedRepTarget: Boolean = false,
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
    val usesUnlimitedRepTarget: Boolean = false,
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

internal sealed interface MachineTeardownClaimResult {
    data object Begun : MachineTeardownClaimResult
    data object DeferredUntilConfigurationCompletes : MachineTeardownClaimResult
    data object Rejected : MachineTeardownClaimResult
}

internal sealed interface MachineConfigurationCompletion {
    data class Activated(val lease: ExecutionLease) : MachineConfigurationCompletion
    data class TeardownBegun(
        val lease: ExecutionLease,
        val configurationInputsSuperseded: Boolean = false,
    ) : MachineConfigurationCompletion
    data object ReleasedWithoutActivation : MachineConfigurationCompletion
    data object Rejected : MachineConfigurationCompletion
}

internal enum class MachineConfigurationClaimResult {
    CLAIMED,
    CONFIGURATION_INPUT_SUPERSEDED,
    REJECTED,
}

internal data class ResetCleanupToken(
    val lease: ExecutionLease?,
    val executionGeneration: Long,
    val configurationInputEpoch: Long,
)

internal data class NoCurrentSuccessorToken(
    val executionGeneration: Long,
    val supersessionEpoch: Long,
    val configurationInputEpoch: Long,
)

internal data class ConfigurationInputMutationToken(
    val id: Long,
)

internal data class ConfigurationInputCapture<T>(
    val configurationInputEpoch: Long,
    val value: T,
)

private data class QueuedSuccessorSetup(
    val lease: ExecutionLease,
    val configurationInputEpoch: Long,
)

private data class MachineConfigurationClaim(
    val lease: ExecutionLease,
    val configurationInputEpoch: Long,
)

internal data class RecoveryPublicationClaim(
    val id: Long,
    val expectedLease: ExecutionLease?,
    val executionGeneration: Long,
    val supersessionEpoch: Long,
    val allowNoCurrentAfterOwnedInvalidation: Boolean,
    val expectedRestoredOwner: RestoredRuntimeOwnerToken?,
)

internal data class RestoredTeardownSeed(
    val sourceExecutionId: Long,
    val sourceStableSessionId: String,
    val profileId: String,
    val requiresMachine: Boolean,
) {
    init {
        require(sourceExecutionId > 0L)
        require(sourceStableSessionId.isNotBlank())
        require(profileId.isNotBlank())
    }
}

internal data class RestoredRuntimeOwnerToken(
    val id: Long,
    val seed: RestoredTeardownSeed,
    val executionGeneration: Long,
    val recoverySupersessionEpoch: Long,
    val configurationInputEpoch: Long,
)

internal data class RestoredRecoveryAttempt(
    val owner: RestoredRuntimeOwnerToken,
    val attempt: Int,
)

private data class RestoredTeardownRecord(
    val owner: RestoredRuntimeOwnerToken,
    val attempt: Int,
    val ready: Boolean,
    val revoked: Boolean,
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
    private var expectedResetClaim: ExecutionLease? = null
    private var machineConfigurationClaim: MachineConfigurationClaim? = null
    private var deferredConfigurationTeardownLease: ExecutionLease? = null
    private var deferredConfigurationTeardownAttempt = 0
    private var recoveryPublicationSequence = 0L
    private var restoredRuntimeOwnerSequence = 0L
    private var recoveryPublicationSupersessionEpoch = 0L
    private var recoveryPublicationClaim: RecoveryPublicationClaim? = null
    private var queuedSuccessorSupersessionEpoch = 0L
    private var configurationInputEpoch = 0L
    private var configurationInputMutationSequence = 0L
    private val openConfigurationInputMutations = mutableSetOf<Long>()
    private var queuedSuccessorSetup: QueuedSuccessorSetup? = null
    private var teardownAttempt = 0
    private var teardownFailureReason: TeardownFailureReason? = null
    private var completionJob: Job? = null
    private var completionJobLease: ExecutionLease? = null
    private var alertDeliveryJob: Job? = null
    private var alertDeliveryJobLease: ExecutionLease? = null
    private var teardownJob: Job? = null
    private var teardownJobLease: ExecutionLease? = null
    private var restoredTeardownRecord: RestoredTeardownRecord? = null
    private var restoredTeardownJob: Job? = null
    private var completionClaim: SetExecutionCompletion? = null
    private var jobOwnershipClosed = false
    private val _machineTeardownState = MutableStateFlow<MachineTeardownState>(MachineTeardownState.Ready)

    val machineTeardownState: StateFlow<MachineTeardownState> = _machineTeardownState.asStateFlow()
    val currentLease: ExecutionLease?
        get() = currentLeaseRef.value

    fun captureMachineTeardownLease(): ExecutionLease? = withPlatformLock(teardownLock) {
        val state = _machineTeardownState.value
        teardownLease?.takeIf { lease ->
            state is MachineTeardownState.TearingDown && state.executionId == lease.executionId
        }
    }

    fun captureResetCleanupToken(): ResetCleanupToken = withPlatformLock(teardownLock) {
        captureResetCleanupTokenLocked()
    }

    fun supersedeRecoveryPublicationAndCaptureResetCleanupToken(): ResetCleanupToken = withPlatformLock(teardownLock) {
        supersedeRecoveryPublicationLocked()
        captureResetCleanupTokenLocked()
    }

    private fun captureResetCleanupTokenLocked() = ResetCleanupToken(
        lease = currentLeaseRef.value,
        executionGeneration = executionSequence.value,
        configurationInputEpoch = configurationInputEpoch,
    )

    fun captureNoCurrentSuccessorToken(): NoCurrentSuccessorToken? = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || currentLeaseRef.value != null) return@withPlatformLock null
        NoCurrentSuccessorToken(
            executionGeneration = executionSequence.value,
            supersessionEpoch = queuedSuccessorSupersessionEpoch,
            configurationInputEpoch = configurationInputEpoch,
        )
    }

    fun <T : Any> prepareNoCurrentSuccessor(
        prepareAndCapture: () -> T?,
    ): Pair<NoCurrentSuccessorToken, T>? = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || currentLeaseRef.value != null) return@withPlatformLock null
        revokeRestoredRuntimeLocked()
        configurationInputEpoch += 1
        val captured = prepareAndCapture() ?: return@withPlatformLock null
        NoCurrentSuccessorToken(
            executionGeneration = executionSequence.value,
            supersessionEpoch = queuedSuccessorSupersessionEpoch,
            configurationInputEpoch = configurationInputEpoch,
        ) to captured
    }

    fun captureConfigurationInputEpoch(): Long = withPlatformLock(teardownLock) {
        configurationInputEpoch
    }

    fun <T> captureConfigurationInputs(block: () -> T): ConfigurationInputCapture<T> = withPlatformLock(teardownLock) {
        ConfigurationInputCapture(
            configurationInputEpoch = configurationInputEpoch,
            value = block(),
        )
    }

    fun mutateConfigurationInputs(block: () -> Unit) = withPlatformLock(teardownLock) {
        revokeRestoredRuntimeLocked()
        configurationInputEpoch += 1
        block()
    }

    fun mutateConfigurationInputsIf(
        candidateStillCurrent: () -> Boolean,
        block: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || !candidateStillCurrent()) return@withPlatformLock false
        revokeRestoredRuntimeLocked()
        configurationInputEpoch += 1
        block()
        true
    }

    /**
     * Marks a command-authoritative mutation that must suspend outside the guard lock.
     * CONFIG claims fail closed while the token is open, and both boundaries advance
     * the input epoch so a command captured on either side cannot cross the mutation.
     */
    fun beginConfigurationInputMutation(): ConfigurationInputMutationToken = withPlatformLock(teardownLock) {
        revokeRestoredRuntimeLocked()
        configurationInputEpoch += 1
        ConfigurationInputMutationToken(id = ++configurationInputMutationSequence).also { token ->
            openConfigurationInputMutations += token.id
        }
    }

    fun endConfigurationInputMutation(token: ConfigurationInputMutationToken) = withPlatformLock(teardownLock) {
        if (openConfigurationInputMutations.remove(token.id)) {
            revokeRestoredRuntimeLocked()
            configurationInputEpoch += 1
        }
    }

    fun supersedeQueuedSuccessors() = withPlatformLock(teardownLock) {
        supersedeQueuedSuccessorsLocked()
    }

    fun beginExecution(seed: ExecutionSeed): Result<ExecutionLease> = withPlatformLock(teardownLock) {
        beginExecutionLocked(seed)
    }

    fun captureRecoveryPublicationEpoch(): Long = withPlatformLock(teardownLock) {
        recoveryPublicationSupersessionEpoch
    }

    /**
     * Atomically begins a successor only while [expectedSource] is still the
     * connection-wide owner. A null expectation is the process-restored case and
     * succeeds only when no execution is currently installed.
     */
    fun beginSuccessorExecution(
        expectedSource: ExecutionLease?,
        seed: ExecutionSeed,
    ): Result<ExecutionLease> = withPlatformLock(teardownLock) {
        val current = currentLeaseRef.value
        val expectedSourceStillOwns = if (expectedSource == null) {
            current == null
        } else {
            current != null && sameIdentity(current, expectedSource)
        }
        if (!expectedSourceStillOwns) {
            return@withPlatformLock Result.failure(
                IllegalStateException("Expected source execution no longer owns the connection"),
            )
        }
        beginExecutionLocked(seed)
    }

    fun beginNoCurrentSuccessorExecution(
        token: NoCurrentSuccessorToken,
        seed: ExecutionSeed,
        candidateStillCurrent: () -> Boolean = { true },
    ): Result<ExecutionLease> = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed ||
            currentLeaseRef.value != null ||
            executionSequence.value != token.executionGeneration ||
            queuedSuccessorSupersessionEpoch != token.supersessionEpoch ||
            configurationInputEpoch != token.configurationInputEpoch ||
            !candidateStillCurrent()
        ) {
            return@withPlatformLock Result.failure(
                IllegalStateException("Queued successor authority was superseded"),
            )
        }
        beginExecutionLocked(seed).onSuccess { lease ->
            queuedSuccessorSetup = QueuedSuccessorSetup(
                lease = lease,
                configurationInputEpoch = token.configurationInputEpoch,
            )
        }
    }

    private fun beginExecutionLocked(seed: ExecutionSeed): Result<ExecutionLease> {
        if (expectedResetClaim != null ||
            machineConfigurationClaim != null ||
            recoveryPublicationClaim != null ||
            queuedSuccessorSetup != null ||
            restoredTeardownRecord != null ||
            (seed.requiresMachine && _machineTeardownState.value !is MachineTeardownState.Ready)
        ) {
            return Result.failure(
                IllegalStateException("Machine command boundary must be ready before beginning an execution"),
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
            usesUnlimitedRepTarget = seed.usesUnlimitedRepTarget,
        )
        currentLeaseRef.value?.let { outgoingLease ->
            if (sameIdentity(completionClaim?.lease, outgoingLease)) {
                completionClaim = null
            }
            cancelPresentationJobsLocked(outgoingLease)
            currentLeaseRef.value = null
        }
        invalidatedLeaseRef.value = null
        supersedeQueuedSuccessorsLocked()
        currentLeaseRef.value = lease
        completionClaim = null
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun")
        return Result.success(lease)
    }

    /**
     * Reserves the non-suspending manual-recovery publication boundary. While the
     * claim is held, no successor can replace the validated source execution.
     */
    fun beginRecoveryPublication(
        expectedLease: ExecutionLease?,
        expectedSupersessionEpoch: Long,
        allowNoCurrentAfterOwnedInvalidation: Boolean,
        expectedRestoredOwner: RestoredRuntimeOwnerToken? = null,
    ): RecoveryPublicationClaim? = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || recoveryPublicationClaim != null) return@withPlatformLock null
        if (recoveryPublicationSupersessionEpoch != expectedSupersessionEpoch) return@withPlatformLock null
        if (!recoveryPublicationAuthorityMatches(expectedLease, allowNoCurrentAfterOwnedInvalidation)) {
            return@withPlatformLock null
        }
        if (expectedRestoredOwner != null && !restoredRuntimeIsCurrentLocked(expectedRestoredOwner)) {
            return@withPlatformLock null
        }
        RecoveryPublicationClaim(
            id = ++recoveryPublicationSequence,
            expectedLease = expectedLease,
            executionGeneration = executionSequence.value,
            supersessionEpoch = expectedSupersessionEpoch,
            allowNoCurrentAfterOwnedInvalidation = allowNoCurrentAfterOwnedInvalidation,
            expectedRestoredOwner = expectedRestoredOwner,
        ).also { recoveryPublicationClaim = it }
    }

    fun commitRecoveryPublication(
        claim: RecoveryPublicationClaim,
        candidateStillCurrent: () -> Boolean = { true },
        block: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed ||
            recoveryPublicationClaim != claim ||
            executionSequence.value != claim.executionGeneration ||
            recoveryPublicationSupersessionEpoch != claim.supersessionEpoch ||
            claim.expectedRestoredOwner?.let(::restoredRuntimeIsCurrentLocked) == false ||
            !recoveryPublicationAuthorityMatches(
                expectedLease = claim.expectedLease,
                allowNoCurrentAfterOwnedInvalidation = claim.allowNoCurrentAfterOwnedInvalidation,
            ) ||
            !candidateStillCurrent()
        ) {
            if (recoveryPublicationClaim == claim) {
                recoveryPublicationClaim = null
            }
            return@withPlatformLock false
        }
        try {
            claim.expectedRestoredOwner?.let(::revokeRestoredRuntimeLocked)
            block()
            true
        } finally {
            if (recoveryPublicationClaim == claim) {
                recoveryPublicationClaim = null
            }
        }
    }

    /**
     * Publishes restored runtime state atomically with its exact teardown barrier.
     * The callback must only mutate in-memory presentation state and must not suspend.
     */
    fun commitRestoredRuntimePublication(
        claim: RecoveryPublicationClaim,
        seed: RestoredTeardownSeed,
        expectedConfigurationInputEpoch: Long,
        block: (RestoredRuntimeOwnerToken) -> Unit,
    ): RestoredRuntimeOwnerToken? = withPlatformLock(teardownLock) {
        if (!recoveryPublicationClaimIsCurrent(claim) ||
            claim.expectedLease != null ||
            claim.allowNoCurrentAfterOwnedInvalidation ||
            currentLeaseRef.value != null ||
            restoredTeardownRecord != null ||
            machineConfigurationClaim != null ||
            configurationInputEpoch != expectedConfigurationInputEpoch ||
            openConfigurationInputMutations.isNotEmpty() ||
            _machineTeardownState.value !is MachineTeardownState.Ready
        ) {
            if (recoveryPublicationClaim == claim) recoveryPublicationClaim = null
            return@withPlatformLock null
        }
        configurationInputEpoch += 1
        val owner = RestoredRuntimeOwnerToken(
            id = ++restoredRuntimeOwnerSequence,
            seed = seed,
            executionGeneration = executionSequence.value,
            recoverySupersessionEpoch = claim.supersessionEpoch,
            configurationInputEpoch = configurationInputEpoch,
        )
        val record = RestoredTeardownRecord(
            owner = owner,
            attempt = if (seed.requiresMachine) 1 else 0,
            ready = !seed.requiresMachine,
            revoked = false,
        )
        restoredTeardownRecord = record
        restoredTeardownJob = null
        if (seed.requiresMachine) {
            _machineTeardownState.value = MachineTeardownState.TearingDown(seed.sourceExecutionId, 1)
        }
        return@withPlatformLock try {
            block(owner)
            owner
        } catch (error: Throwable) {
            restoredTeardownRecord = null
            restoredTeardownJob = null
            if (seed.requiresMachine) _machineTeardownState.value = MachineTeardownState.Ready
            throw error
        } finally {
            if (recoveryPublicationClaim == claim) recoveryPublicationClaim = null
        }
    }

    private fun recoveryPublicationClaimIsCurrent(claim: RecoveryPublicationClaim): Boolean = !jobOwnershipClosed &&
        recoveryPublicationClaim == claim &&
        executionSequence.value == claim.executionGeneration &&
        recoveryPublicationSupersessionEpoch == claim.supersessionEpoch &&
        claim.expectedRestoredOwner?.let(::restoredRuntimeIsCurrentLocked) != false &&
        recoveryPublicationAuthorityMatches(
            expectedLease = claim.expectedLease,
            allowNoCurrentAfterOwnedInvalidation = claim.allowNoCurrentAfterOwnedInvalidation,
        )

    fun isRestoredTeardownReady(owner: RestoredRuntimeOwnerToken): Boolean = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord ?: return@withPlatformLock false
        record.owner == owner && !record.revoked && record.ready &&
            (!owner.seed.requiresMachine || _machineTeardownState.value is MachineTeardownState.Ready)
    }

    fun isRestoredRuntimeCurrent(owner: RestoredRuntimeOwnerToken): Boolean = withPlatformLock(teardownLock) {
        restoredRuntimeIsCurrentLocked(owner)
    }

    /**
     * Commits a restored countdown tick at the same boundary that owns restored
     * execution authority. This prevents a reset or configuration supersession
     * from interleaving between the final timer check and presentation write.
     */
    fun commitRestoredTimerPublication(
        owner: RestoredRuntimeOwnerToken,
        candidateStillCurrent: () -> Boolean,
        publish: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed ||
            !restoredRuntimeIsCurrentLocked(owner) ||
            !candidateStillCurrent()
        ) {
            return@withPlatformLock false
        }
        if (!restoredRuntimeIsCurrentLocked(owner)) return@withPlatformLock false
        publish()
        true
    }

    private fun restoredRuntimeIsCurrentLocked(owner: RestoredRuntimeOwnerToken): Boolean {
        val record = restoredTeardownRecord ?: return false
        return record.owner == owner &&
            !record.revoked &&
            currentLeaseRef.value == null &&
            executionSequence.value == owner.executionGeneration &&
            recoveryPublicationSupersessionEpoch == owner.recoverySupersessionEpoch &&
            configurationInputEpoch == owner.configurationInputEpoch
    }

    fun attachRestoredTeardownJob(owner: RestoredRuntimeOwnerToken, job: Job): Boolean = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord ?: return@withPlatformLock false
        val state = _machineTeardownState.value
        if (jobOwnershipClosed ||
            record.owner != owner ||
            !owner.seed.requiresMachine ||
            record.ready ||
            state !is MachineTeardownState.TearingDown ||
            state.executionId != owner.seed.sourceExecutionId ||
            restoredTeardownJob != null
        ) {
            return@withPlatformLock false
        }
        restoredTeardownJob = job
        true
    }

    fun clearRestoredTeardownJobIfOwned(owner: RestoredRuntimeOwnerToken, job: Job) = withPlatformLock(teardownLock) {
        if (restoredTeardownRecord?.owner == owner && restoredTeardownJob === job) {
            restoredTeardownJob = null
        }
    }

    fun markRestoredTeardownReady(owner: RestoredRuntimeOwnerToken): Boolean = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord ?: return@withPlatformLock false
        val state = _machineTeardownState.value
        if (record.owner != owner ||
            !owner.seed.requiresMachine ||
            record.ready ||
            state !is MachineTeardownState.TearingDown ||
            state.executionId != owner.seed.sourceExecutionId
        ) {
            return@withPlatformLock false
        }
        restoredTeardownJob = null
        _machineTeardownState.value = MachineTeardownState.Ready
        if (record.revoked) {
            restoredTeardownRecord = null
        } else {
            restoredTeardownRecord = record.copy(ready = true)
        }
        true
    }

    fun markRestoredRecoveryRequired(
        owner: RestoredRuntimeOwnerToken,
        reason: TeardownFailureReason,
    ): Boolean = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord ?: return@withPlatformLock false
        val state = _machineTeardownState.value
        if (record.owner != owner ||
            !owner.seed.requiresMachine ||
            record.ready ||
            state !is MachineTeardownState.TearingDown ||
            state.executionId != owner.seed.sourceExecutionId
        ) {
            return@withPlatformLock false
        }
        restoredTeardownJob = null
        _machineTeardownState.value = MachineTeardownState.RecoveryRequired(owner.seed.sourceExecutionId)
        true
    }

    fun beginRestoredRecoveryAttempt(owner: RestoredRuntimeOwnerToken): RestoredRecoveryAttempt? = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord ?: return@withPlatformLock null
        val state = _machineTeardownState.value
        if (jobOwnershipClosed ||
            record.owner != owner ||
            record.ready ||
            state !is MachineTeardownState.RecoveryRequired ||
            state.executionId != owner.seed.sourceExecutionId
        ) {
            return@withPlatformLock null
        }
        val attempt = record.attempt + 1
        restoredTeardownRecord = record.copy(attempt = attempt)
        _machineTeardownState.value = MachineTeardownState.TearingDown(owner.seed.sourceExecutionId, attempt)
        RestoredRecoveryAttempt(owner, attempt)
    }

    fun revokeRestoredRuntime(owner: RestoredRuntimeOwnerToken? = null): Boolean = withPlatformLock(teardownLock) { revokeRestoredRuntimeLocked(owner) }

    private fun revokeRestoredRuntimeLocked(owner: RestoredRuntimeOwnerToken? = null): Boolean {
        val record = restoredTeardownRecord ?: return false
        if (owner != null && record.owner != owner) return false
        if (record.ready || !record.owner.seed.requiresMachine) {
            restoredTeardownRecord = null
            restoredTeardownJob = null
            _machineTeardownState.value = MachineTeardownState.Ready
        } else {
            restoredTeardownRecord = record.copy(revoked = true)
        }
        return true
    }

    fun beginRestoredSuccessorExecution(
        owner: RestoredRuntimeOwnerToken,
        seed: ExecutionSeed,
        candidateStillCurrent: () -> Boolean,
    ): Result<ExecutionLease> = withPlatformLock(teardownLock) {
        val record = restoredTeardownRecord
        if (record == null ||
            record.owner != owner ||
            record.revoked ||
            !record.ready ||
            currentLeaseRef.value != null ||
            executionSequence.value != owner.executionGeneration ||
            recoveryPublicationSupersessionEpoch != owner.recoverySupersessionEpoch ||
            configurationInputEpoch != owner.configurationInputEpoch ||
            _machineTeardownState.value !is MachineTeardownState.Ready ||
            !candidateStillCurrent()
        ) {
            return@withPlatformLock Result.failure(
                IllegalStateException("Restored runtime authority was superseded"),
            )
        }
        restoredTeardownRecord = null
        val result = beginExecutionLocked(seed)
        if (result.isFailure) {
            restoredTeardownRecord = record
        }
        result
    }

    fun supersedeRecoveryPublication() = withPlatformLock(teardownLock) {
        supersedeRecoveryPublicationLocked()
    }

    private fun supersedeRecoveryPublicationLocked() {
        revokeRestoredRuntimeLocked()
        recoveryPublicationSupersessionEpoch += 1
        recoveryPublicationClaim = null
    }

    private fun supersedeQueuedSuccessorsLocked() {
        queuedSuccessorSupersessionEpoch += 1
    }

    private fun clearQueuedSuccessorSetupLocked(lease: ExecutionLease) {
        if (sameIdentity(queuedSuccessorSetup?.lease, lease)) {
            queuedSuccessorSetup = null
        }
    }

    private fun recoveryPublicationAuthorityMatches(
        expectedLease: ExecutionLease?,
        allowNoCurrentAfterOwnedInvalidation: Boolean,
    ): Boolean {
        val current = currentLeaseRef.value
        if (expectedLease == null) return current == null
        if (sameIdentity(current, expectedLease)) return true
        return allowNoCurrentAfterOwnedInvalidation &&
            current == null &&
            sameIdentity(invalidatedLeaseRef.value, expectedLease)
    }

    fun beginMachineConfiguration(lease: ExecutionLease): Boolean = claimMachineConfiguration(lease) == MachineConfigurationClaimResult.CLAIMED

    fun claimMachineConfiguration(
        lease: ExecutionLease,
        expectedConfigurationInputEpoch: Long? = null,
        inputAuthorityStillCurrent: () -> Boolean = { true },
    ): MachineConfigurationClaimResult = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || !lease.requiresMachine) {
            return@withPlatformLock MachineConfigurationClaimResult.REJECTED
        }
        if (!sameIdentity(currentLeaseRef.value, lease)) {
            return@withPlatformLock MachineConfigurationClaimResult.REJECTED
        }
        if (_machineTeardownState.value !is MachineTeardownState.Ready || machineConfigurationClaim != null) {
            return@withPlatformLock MachineConfigurationClaimResult.REJECTED
        }
        if (openConfigurationInputMutations.isNotEmpty()) {
            return@withPlatformLock MachineConfigurationClaimResult.CONFIGURATION_INPUT_SUPERSEDED
        }
        val queuedEpoch = queuedSuccessorSetup
            ?.takeIf { sameIdentity(it.lease, lease) }
            ?.configurationInputEpoch
        val requiredEpoch = expectedConfigurationInputEpoch ?: queuedEpoch
        if (requiredEpoch != null && requiredEpoch != configurationInputEpoch) {
            return@withPlatformLock MachineConfigurationClaimResult.CONFIGURATION_INPUT_SUPERSEDED
        }
        if (!inputAuthorityStillCurrent()) {
            return@withPlatformLock MachineConfigurationClaimResult.CONFIGURATION_INPUT_SUPERSEDED
        }
        machineConfigurationClaim = MachineConfigurationClaim(
            lease = lease,
            configurationInputEpoch = configurationInputEpoch,
        )
        MachineConfigurationClaimResult.CLAIMED
    }

    fun endMachineConfiguration(lease: ExecutionLease): Boolean = withPlatformLock(teardownLock) {
        if (!sameIdentity(machineConfigurationClaim?.lease, lease)) return@withPlatformLock false
        if (sameIdentity(deferredConfigurationTeardownLease, lease)) return@withPlatformLock false
        machineConfigurationClaim = null
        true
    }

    /**
     * Finishes the CONFIG boundary atomically with either activation or a teardown
     * handoff. The callback is non-suspending and must only publish in-memory state.
     */
    fun completeMachineConfiguration(
        lease: ExecutionLease,
        activationCutoverTimestampMs: Long?,
        inputAuthorityStillCurrent: () -> Boolean = { true },
        onActivated: (ExecutionLease) -> Unit,
    ): MachineConfigurationCompletion = withPlatformLock(teardownLock) {
        val configurationClaim = machineConfigurationClaim
            ?: return@withPlatformLock MachineConfigurationCompletion.Rejected
        if (!sameIdentity(configurationClaim.lease, lease)) {
            return@withPlatformLock MachineConfigurationCompletion.Rejected
        }
        machineConfigurationClaim = null

        if (sameIdentity(deferredConfigurationTeardownLease, lease)) {
            val attempt = deferredConfigurationTeardownAttempt
            deferredConfigurationTeardownLease = null
            deferredConfigurationTeardownAttempt = 0
            return@withPlatformLock if (beginTeardownLocked(lease, attempt)) {
                MachineConfigurationCompletion.TeardownBegun(lease)
            } else {
                MachineConfigurationCompletion.ReleasedWithoutActivation
            }
        }

        if (configurationClaim.configurationInputEpoch != configurationInputEpoch ||
            !inputAuthorityStillCurrent()
        ) {
            return@withPlatformLock if (beginTeardownLocked(lease, attempt = 1)) {
                MachineConfigurationCompletion.TeardownBegun(
                    lease = lease,
                    configurationInputsSuperseded = true,
                )
            } else {
                MachineConfigurationCompletion.ReleasedWithoutActivation
            }
        }

        if (activationCutoverTimestampMs == null) {
            return@withPlatformLock MachineConfigurationCompletion.ReleasedWithoutActivation
        }
        val current = currentLeaseRef.value
        if (current == null ||
            !sameIdentity(current, lease) ||
            _machineTeardownState.value !is MachineTeardownState.Ready
        ) {
            return@withPlatformLock MachineConfigurationCompletion.ReleasedWithoutActivation
        }
        val activated = current.copy(activationCutoverTimestampMs = activationCutoverTimestampMs)
        currentLeaseRef.value = activated
        clearQueuedSuccessorSetupLocked(lease)
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=activated")
        onActivated(activated)
        MachineConfigurationCompletion.Activated(activated)
    }

    fun activate(
        lease: ExecutionLease,
        cutoverTimestampMs: Long,
        expectedConfigurationInputEpoch: Long? = null,
        inputAuthorityStillCurrent: () -> Boolean = { true },
    ): ExecutionLease? = withPlatformLock(teardownLock) {
        val current = currentLeaseRef.value ?: return@withPlatformLock null
        if (!sameIdentity(current, lease)) return@withPlatformLock null
        if (expectedConfigurationInputEpoch != null &&
            (expectedConfigurationInputEpoch != configurationInputEpoch || openConfigurationInputMutations.isNotEmpty())
        ) {
            return@withPlatformLock null
        }
        if (!inputAuthorityStillCurrent()) return@withPlatformLock null
        if (lease.requiresMachine &&
            (_machineTeardownState.value !is MachineTeardownState.Ready || machineConfigurationClaim != null)
        ) {
            return@withPlatformLock null
        }
        val activated = current.copy(activationCutoverTimestampMs = cutoverTimestampMs)
        currentLeaseRef.value = activated
        clearQueuedSuccessorSetupLocked(lease)
        log(LogEventType.WORKOUT_EXECUTION, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=activated")
        activated
    }

    fun releaseQueuedSuccessorSetup(lease: ExecutionLease): Boolean = withPlatformLock(teardownLock) {
        if (!sameIdentity(queuedSuccessorSetup?.lease, lease)) return@withPlatformLock false
        queuedSuccessorSetup = null
        true
    }

    fun isCurrent(lease: ExecutionLease): Boolean = currentLeaseRef.value?.let { current ->
        sameIdentity(current, lease)
    } == true

    fun invalidateCurrent(reason: ExecutionInvalidationReason): ExecutionLease? = withPlatformLock(teardownLock) {
        val invalidated = currentLeaseRef.value ?: return null
        if (reason == ExecutionInvalidationReason.START_FAILED) {
            recoveryPublicationClaim = null
        } else {
            supersedeRecoveryPublicationLocked()
        }
        cancelPresentationJobsLocked(invalidated)
        clearQueuedSuccessorSetupLocked(invalidated)
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

    private fun invalidateLocked(lease: ExecutionLease, reason: ExecutionInvalidationReason): Boolean {
        val current = currentLeaseRef.value ?: return false
        if (!sameIdentity(current, lease)) return false
        if (reason == ExecutionInvalidationReason.START_FAILED) {
            recoveryPublicationClaim = null
        } else {
            supersedeRecoveryPublicationLocked()
        }
        cancelPresentationJobsLocked(current)
        clearQueuedSuccessorSetupLocked(current)
        currentLeaseRef.value = null
        if (sameIdentity(completionClaim?.lease, current)) {
            completionClaim = null
        }
        invalidatedLeaseRef.value = current
        log(
            LogEventType.WORKOUT_EXECUTION,
            "executionId=${current.executionId},sessionId=${current.sessionId},transition=invalidated,reason=$reason",
        )
        return true
    }

    fun invalidate(lease: ExecutionLease, reason: ExecutionInvalidationReason): Boolean =
        withPlatformLock(teardownLock) {
            invalidateLocked(lease, reason)
        }

    /** Atomically reserves the reset boundary, captures cleanup, and invalidates an exact lease. */
    fun claimExpectedResetAndCaptureResetCleanupToken(lease: ExecutionLease): ResetCleanupToken? =
        withPlatformLock(teardownLock) {
            if (expectedResetClaim != null || !sameIdentity(currentLeaseRef.value, lease)) {
                return@withPlatformLock null
            }
            val token = captureResetCleanupTokenLocked()
            invalidateLocked(lease, ExecutionInvalidationReason.RESET_FOR_NEW_WORKOUT)
            expectedResetClaim = lease
            token
        }

    fun releaseExpectedResetClaim(lease: ExecutionLease) = withPlatformLock(teardownLock) {
        if (sameIdentity(expectedResetClaim, lease)) {
            expectedResetClaim = null
        }
    }

    fun commitResetCleanupIfNoSuccessor(
        token: ResetCleanupToken,
        invalidatedLease: ExecutionLease?,
        block: () -> Unit,
    ): Boolean = withPlatformLock(teardownLock) {
        if (currentLeaseRef.value != null) return@withPlatformLock false
        if (executionSequence.value != token.executionGeneration) return@withPlatformLock false
        if (configurationInputEpoch != token.configurationInputEpoch) return@withPlatformLock false
        if (token.lease == null && invalidatedLease != null) return@withPlatformLock false
        if (token.lease != null && (invalidatedLease == null || !sameIdentity(token.lease, invalidatedLease))) {
            return@withPlatformLock false
        }
        supersedeRecoveryPublicationLocked()
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
            supersedeRecoveryPublicationLocked()
            supersedeQueuedSuccessorsLocked()
            queuedSuccessorSetup = null
            if (openConfigurationInputMutations.isNotEmpty()) {
                openConfigurationInputMutations.clear()
                configurationInputEpoch += 1
            }
            jobOwnershipClosed = true
            val jobs = listOfNotNull(completionJob, alertDeliveryJob, teardownJob, restoredTeardownJob)
            completionJob = null
            completionJobLease = null
            alertDeliveryJob = null
            alertDeliveryJobLease = null
            teardownJob = null
            teardownJobLease = null
            restoredTeardownJob = null
            restoredTeardownRecord = restoredTeardownRecord?.copy(revoked = true)
            jobs
        }
        ownedJobs.forEach(Job::cancel)
    }

    fun beginTeardown(lease: ExecutionLease, attempt: Int = 1): Boolean = withPlatformLock(teardownLock) {
        beginTeardownLocked(lease, attempt)
    }

    fun requestTeardown(lease: ExecutionLease, attempt: Int = 1): MachineTeardownClaimResult = withPlatformLock(teardownLock) {
        if (jobOwnershipClosed || attempt < 1) return@withPlatformLock MachineTeardownClaimResult.Rejected
        val ownsExecution = isCurrent(lease) || sameIdentity(invalidatedLeaseRef.value, lease)
        if (_machineTeardownState.value !is MachineTeardownState.Ready || !ownsExecution) {
            return@withPlatformLock MachineTeardownClaimResult.Rejected
        }
        val configuringLease = machineConfigurationClaim?.lease
        if (configuringLease != null) {
            if (!sameIdentity(configuringLease, lease) || deferredConfigurationTeardownLease != null) {
                return@withPlatformLock MachineTeardownClaimResult.Rejected
            }
            deferredConfigurationTeardownLease = lease
            deferredConfigurationTeardownAttempt = attempt
            return@withPlatformLock MachineTeardownClaimResult.DeferredUntilConfigurationCompletes
        }
        if (beginTeardownLocked(lease, attempt)) {
            MachineTeardownClaimResult.Begun
        } else {
            MachineTeardownClaimResult.Rejected
        }
    }

    private fun beginTeardownLocked(lease: ExecutionLease, attempt: Int): Boolean {
        if (jobOwnershipClosed) return false
        val ownsExecution = isCurrent(lease) || sameIdentity(invalidatedLeaseRef.value, lease)
        if (attempt < 1 ||
            _machineTeardownState.value !is MachineTeardownState.Ready ||
            machineConfigurationClaim != null ||
            !ownsExecution
        ) {
            return false
        }
        if (sameIdentity(invalidatedLeaseRef.value, lease)) {
            invalidatedLeaseRef.value = null
        }
        clearQueuedSuccessorSetupLocked(lease)
        teardownLease = lease
        teardownAttempt = attempt
        teardownFailureReason = null
        teardownJob = null
        teardownJobLease = null
        _machineTeardownState.value = MachineTeardownState.TearingDown(lease.executionId, attempt)
        log(LogEventType.WORKOUT_TEARDOWN, "executionId=${lease.executionId},sessionId=${lease.sessionId},transition=begun,attempt=$attempt")
        return true
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

    internal fun persistenceClaimsSnapshot(): Map<String, PersistenceClaimStatus> = withPlatformLock(persistenceLock) {
        persistedClaims.toMap()
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
