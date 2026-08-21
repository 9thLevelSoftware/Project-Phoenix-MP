package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.data.repository.programModeFromSnapshotName
import com.devil.phoenixproject.data.repository.toSnapshotName
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.util.withPlatformLock
import kotlinx.atomicfu.atomic

internal data class SetExecutionCompletion(
    val lease: ExecutionLease,
    val reason: SetEndReason,
    val routineIdentity: RoutineExecutionIdentity?,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val plannedSetType: SetType,
    val programMode: ProgramMode,
    val programmedBaseWeightPerCableKg: Float,
    val configuredStartWeightPerCableKg: Float,
    val progressionKg: Float,
    val actualReps: Int,
    val targetReps: Int?,
    val isWarmup: Boolean,
    val isEcho: Boolean,
    val isJustLift: Boolean,
    val isBodyweight: Boolean,
    val isTimed: Boolean,
    val isAmrap: Boolean,
    val isCableExercise: Boolean,
    val physicalCableCount: Int? = null,
    val logicalPreRackCommandTemplate: com.devil.phoenixproject.domain.model.WorkoutParameters,
) {
    init {
        require(attemptNumber > 0)
        require(acceptedDropCount in 0..2)
        require(actualReps >= 0)
        require(targetReps == null || targetReps > 0)
        routineIdentity?.let { identity ->
            require(identity.profileId == lease.profileId)
            require(identity.logicalSetKey.setKind == plannedSetType)
            require(identity.logicalSetKey.setIndex == identity.setIndex)
        }
    }
}

/**
 * Immutable command authority captured from the failed source execution for a
 * retry resumed without an in-memory execution lease. Task 8 owns hydrating
 * this authority after process death; Task 7 only consumes an explicit,
 * identity-bound instance and never reconstructs it from mutable coordinator
 * parameters.
 */
internal data class RestoredRetrySourceContext(
    val sourceStableSessionId: String,
    val sourceExecutionId: String,
    val profileId: String,
    val routineIdentity: RoutineExecutionIdentity,
    val reason: SetEndReason,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val plannedSetType: SetType,
    val programMode: ProgramMode,
    val programmedBaseWeightPerCableKg: Float,
    val configuredStartWeightPerCableKg: Float,
    val progressionKg: Float,
    val actualReps: Int,
    val targetReps: Int?,
    val isWarmup: Boolean,
    val isEcho: Boolean,
    val isJustLift: Boolean,
    val isBodyweight: Boolean,
    val isTimed: Boolean,
    val isAmrap: Boolean,
    val isCableExercise: Boolean,
    val physicalCableCount: Int?,
    val commandTemplate: com.devil.phoenixproject.domain.model.WorkoutParameters,
) {
    init {
        require(sourceStableSessionId.isNotBlank())
        require(sourceExecutionId.isNotBlank())
        require(profileId == routineIdentity.profileId)
        require(attemptNumber > 0)
        require(acceptedDropCount in 0..2)
        require(actualReps >= 0)
        require(targetReps == null || targetReps > 0)
        require(routineIdentity.logicalSetKey.setKind == plannedSetType)
    }
}

internal fun SetExecutionCompletion.toRestoredRetrySourceContext(): RestoredRetrySourceContext = RestoredRetrySourceContext(
    sourceStableSessionId = lease.sessionId,
    sourceExecutionId = lease.executionId.toString(),
    profileId = lease.profileId,
    routineIdentity = requireNotNull(routineIdentity),
    reason = reason,
    attemptNumber = attemptNumber,
    acceptedDropCount = acceptedDropCount,
    plannedSetType = plannedSetType,
    programMode = programMode,
    programmedBaseWeightPerCableKg = programmedBaseWeightPerCableKg,
    configuredStartWeightPerCableKg = configuredStartWeightPerCableKg,
    progressionKg = progressionKg,
    actualReps = actualReps,
    targetReps = targetReps,
    isWarmup = isWarmup,
    isEcho = isEcho,
    isJustLift = isJustLift,
    isBodyweight = isBodyweight,
    isTimed = isTimed,
    isAmrap = isAmrap,
    isCableExercise = isCableExercise,
    physicalCableCount = physicalCableCount,
    commandTemplate = logicalPreRackCommandTemplate,
)

internal fun SetExecutionCompletion.toRuntimeSourceAuthoritySnapshot(): RestoredRetrySourceAuthoritySnapshot = RestoredRetrySourceAuthoritySnapshot(
    sourceStableSessionId = lease.sessionId,
    sourceExecutionId = lease.executionId.toString(),
    profileId = lease.profileId,
    routineIdentity = requireNotNull(routineIdentity),
    reasonName = reason.name,
    attemptNumber = attemptNumber,
    acceptedDropCount = acceptedDropCount,
    plannedSetTypeName = plannedSetType.name,
    programModeName = programMode.toSnapshotName(),
    programmedBaseWeightPerCableKg = programmedBaseWeightPerCableKg,
    configuredStartWeightPerCableKg = configuredStartWeightPerCableKg,
    progressionKg = progressionKg,
    actualReps = actualReps,
    targetReps = targetReps,
    isWarmup = isWarmup,
    isEcho = isEcho,
    isJustLift = isJustLift,
    isBodyweight = isBodyweight,
    isTimed = isTimed,
    isAmrap = isAmrap,
    isCableExercise = isCableExercise,
    physicalCableCount = physicalCableCount,
    commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(logicalPreRackCommandTemplate),
)

internal fun RestoredRetrySourceAuthoritySnapshot.toRestoredRetrySourceContext(): RestoredRetrySourceContext = RestoredRetrySourceContext(
    sourceStableSessionId = sourceStableSessionId,
    sourceExecutionId = sourceExecutionId,
    profileId = profileId,
    routineIdentity = routineIdentity,
    reason = SetEndReason.valueOf(reasonName),
    attemptNumber = attemptNumber,
    acceptedDropCount = acceptedDropCount,
    plannedSetType = SetType.valueOf(plannedSetTypeName),
    programMode = programModeFromSnapshotName(programModeName),
    programmedBaseWeightPerCableKg = programmedBaseWeightPerCableKg,
    configuredStartWeightPerCableKg = configuredStartWeightPerCableKg,
    progressionKg = progressionKg,
    actualReps = actualReps,
    targetReps = targetReps,
    isWarmup = isWarmup,
    isEcho = isEcho,
    isJustLift = isJustLift,
    isBodyweight = isBodyweight,
    isTimed = isTimed,
    isAmrap = isAmrap,
    isCableExercise = isCableExercise,
    physicalCableCount = physicalCableCount,
    commandTemplate = commandTemplate.toWorkoutParameters(),
)

internal data class SetExecutionActivationFacts(
    val routineIdentity: RoutineExecutionIdentity?,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val plannedSetType: SetType,
    val programMode: ProgramMode,
    val programmedBaseWeightPerCableKg: Float,
    val configuredStartWeightPerCableKg: Float,
    val progressionKg: Float,
    val targetReps: Int?,
    val isWarmup: Boolean,
    val isEcho: Boolean,
    val isJustLift: Boolean,
    val isBodyweight: Boolean,
    val isTimed: Boolean,
    val isAmrap: Boolean,
    val isCableExercise: Boolean,
    val physicalCableCount: Int? = null,
    val logicalPreRackCommandTemplate: com.devil.phoenixproject.domain.model.WorkoutParameters,
) {
    fun complete(lease: ExecutionLease, reason: SetEndReason, actualReps: Int) = SetExecutionCompletion(
        lease = lease,
        reason = reason,
        routineIdentity = routineIdentity,
        attemptNumber = attemptNumber,
        acceptedDropCount = acceptedDropCount,
        plannedSetType = plannedSetType,
        programMode = programMode,
        programmedBaseWeightPerCableKg = programmedBaseWeightPerCableKg,
        configuredStartWeightPerCableKg = configuredStartWeightPerCableKg,
        progressionKg = progressionKg,
        actualReps = actualReps,
        targetReps = targetReps,
        isWarmup = isWarmup,
        isEcho = isEcho,
        isJustLift = isJustLift,
        isBodyweight = isBodyweight,
        isTimed = isTimed,
        isAmrap = isAmrap,
        isCableExercise = isCableExercise,
        physicalCableCount = physicalCableCount,
        logicalPreRackCommandTemplate = logicalPreRackCommandTemplate,
    )
}

private sealed interface BodyweightCompletionState {
    data object Empty : BodyweightCompletionState
    data class Available(val lease: ExecutionLease) : BodyweightCompletionState
    data class Pending(val completion: SetExecutionCompletion) : BodyweightCompletionState
    data class Consuming(val completion: SetExecutionCompletion) : BodyweightCompletionState
    data class Invalidated(val lease: ExecutionLease) : BodyweightCompletionState
}

internal class BodyweightCompletionGate {
    private val state = atomic<BodyweightCompletionState>(BodyweightCompletionState.Empty)

    fun beginExecution(lease: ExecutionLease) {
        while (true) {
            val current = state.value
            val currentExecutionId = when (current) {
                is BodyweightCompletionState.Available -> current.lease.executionId
                is BodyweightCompletionState.Pending -> current.completion.lease.executionId
                is BodyweightCompletionState.Consuming -> current.completion.lease.executionId
                is BodyweightCompletionState.Invalidated -> current.lease.executionId
                BodyweightCompletionState.Empty -> Long.MIN_VALUE
            }
            if (currentExecutionId >= lease.executionId) return
            if (state.compareAndSet(current, BodyweightCompletionState.Available(lease))) return
        }
    }

    fun tryPublish(completion: SetExecutionCompletion): Boolean {
        while (true) {
            val current = state.value as? BodyweightCompletionState.Available ?: return false
            if (!current.lease.sameExecutionAs(completion.lease)) return false
            if (state.compareAndSet(current, BodyweightCompletionState.Pending(completion))) return true
        }
    }

    fun pendingFor(lease: ExecutionLease): SetExecutionCompletion? = (state.value as? BodyweightCompletionState.Pending)
        ?.completion
        ?.takeIf { it.lease.sameExecutionAs(lease) }

    fun hasClaimedCompletion(lease: ExecutionLease): Boolean = when (val current = state.value) {
        is BodyweightCompletionState.Pending -> current.completion.lease.sameExecutionAs(lease)
        is BodyweightCompletionState.Consuming -> current.completion.lease.sameExecutionAs(lease)
        else -> false
    }

    fun tryConsume(pending: SetExecutionCompletion, completion: SetExecutionCompletion = pending): Boolean {
        while (true) {
            val current = state.value as? BodyweightCompletionState.Pending ?: return false
            if (current.completion != pending || !pending.lease.sameExecutionAs(completion.lease)) return false
            if (state.compareAndSet(current, BodyweightCompletionState.Consuming(completion))) return true
        }
    }

    fun invalidate(lease: ExecutionLease) {
        while (true) {
            val current = state.value
            val ownsState = when (current) {
                is BodyweightCompletionState.Available -> current.lease.sameExecutionAs(lease)
                is BodyweightCompletionState.Pending -> current.completion.lease.sameExecutionAs(lease)
                is BodyweightCompletionState.Consuming -> current.completion.lease.sameExecutionAs(lease)
                is BodyweightCompletionState.Invalidated -> current.lease.sameExecutionAs(lease)
                BodyweightCompletionState.Empty -> false
            }
            if (!ownsState || current is BodyweightCompletionState.Invalidated) return
            if (state.compareAndSet(current, BodyweightCompletionState.Invalidated(lease))) return
        }
    }

    private fun ExecutionLease.sameExecutionAs(other: ExecutionLease): Boolean = executionId == other.executionId && sessionId == other.sessionId
}

private data class DangerZoneCountdownClaim(
    val lease: ExecutionLease,
    val startTimeMs: Long,
)

/** Persistence authority for a same-logical-set retry. */
internal sealed interface RetryPersistenceGate {
    val sourceStableSessionId: String

    data class Live(
        override val sourceStableSessionId: String,
    ) : RetryPersistenceGate

    data class Restored(
        override val sourceStableSessionId: String,
        val actionIdentity: RestActionIdentity,
        val sourceContext: RestoredRetrySourceContext,
    ) : RetryPersistenceGate
}

internal class DangerZoneCountdownGate {
    private val claim = atomic<DangerZoneCountdownClaim?>(null)

    fun tryPrime(lease: ExecutionLease, startTimeMs: Long): Boolean {
        val candidate = DangerZoneCountdownClaim(lease, startTimeMs)
        while (true) {
            val current = claim.value
            if (current != null && current.lease.executionId > lease.executionId) return false
            if (current != null && current.lease.executionId == lease.executionId && current.lease.sessionId != lease.sessionId) {
                return false
            }
            if (claim.compareAndSet(current, candidate)) return true
        }
    }

    fun consume(lease: ExecutionLease): Long? {
        while (true) {
            val current = claim.value ?: return null
            if (!current.lease.sameExecutionAs(lease)) return null
            if (claim.compareAndSet(current, null)) return current.startTimeMs
        }
    }

    fun clear(lease: ExecutionLease) {
        while (true) {
            val current = claim.value ?: return
            if (!current.lease.sameExecutionAs(lease)) return
            if (claim.compareAndSet(current, null)) return
        }
    }

    private fun ExecutionLease.sameExecutionAs(other: ExecutionLease): Boolean = executionId == other.executionId && sessionId == other.sessionId
}

internal data class WorkoutExitSnapshot(
    val lease: ExecutionLease,
    val completion: SetExecutionCompletion,
    val terminalPath: TerminalPath,
    val session: WorkoutSession,
    val completedSet: CompletedSet?,
    val metrics: List<WorkoutMetric>,
    val repMetrics: List<RepMetricData>,
    val biomechanicsRepResults: List<BiomechanicsRepResult>,
    val singleExerciseDefaults: SingleExerciseDefaultsDocument? = null,
    val presentationSummary: WorkoutState.SetSummary,
    val exerciseIndex: Int,
    val setIndex: Int,
    val isRoutineSet: Boolean,
    val shouldAccumulateRoutineCalories: Boolean,
    val shouldExportIndividualHealthSession: Boolean,
    val shouldExportIndividualBackup: Boolean,
    val shouldUpdateCycleProgress: Boolean,
    val cycleId: String?,
    val cycleDayNumber: Int?,
    val postSaveInput: PostSaveWorkoutInput,
)

internal data class WorkoutExecutionContext(
    val lease: ExecutionLease,
    val exerciseName: String?,
    val preferredCableCount: Int?,
    val displayMultiplier: Int?,
    val sessionBodyWeightKg: Float,
    val routineSessionId: String?,
    val routineId: String?,
    val routineName: String?,
    val cycleId: String?,
    val cycleDayNumber: Int?,
    val completionFacts: SetExecutionActivationFacts,
)

internal data class PostSaveWorkoutInput(
    val profileId: String,
    val exerciseId: String?,
    val workingReps: Int,
    val achievedWeightKg: Float,
    val volumeWeightKg: Float,
    val programMode: ProgramMode,
    val isJustLift: Boolean,
    val isEchoMode: Boolean,
    val peakConcentricForceKg: Float,
    val peakEccentricForceKg: Float,
    val sessionMcvMmS: Float?,
)

private data class WorkoutExitSnapshotKey(
    val executionId: Long,
    val sessionId: String,
    val profileId: String,
)

internal class WorkoutExitSnapshotStore {
    private val lock = Any()
    private val snapshots = LinkedHashMap<WorkoutExitSnapshotKey, WorkoutExitSnapshot>()

    fun getOrCapture(
        completion: SetExecutionCompletion,
        terminalPath: TerminalPath,
        onInstalled: (WorkoutExitSnapshot) -> Unit = {},
        capture: () -> WorkoutExitSnapshot,
    ): WorkoutExitSnapshot {
        val key = completion.lease.snapshotKey()
        withPlatformLock(lock) {
            snapshots[key]?.let { return it.copy(terminalPath = terminalPath) }
        }

        val candidate = capture()
        require(
            candidate.lease.snapshotKey() == key &&
                candidate.completion == completion &&
                candidate.session.id == completion.lease.sessionId,
        ) {
            "Captured workout exit snapshot must match its execution lease"
        }

        return withPlatformLock(lock) {
            snapshots[key]?.copy(terminalPath = terminalPath) ?: candidate.also { installed ->
                snapshots[key] = installed
                onInstalled(installed)
            }
        }
    }

    fun retainedSnapshots(): List<WorkoutExitSnapshot> = withPlatformLock(lock) {
        snapshots.values.toList()
    }

    fun findBySessionId(sessionId: String): WorkoutExitSnapshot? = withPlatformLock(lock) {
        snapshots.values.lastOrNull { it.session.id == sessionId }
    }

    fun remove(snapshot: WorkoutExitSnapshot) = withPlatformLock(lock) {
        snapshots.remove(snapshot.lease.snapshotKey())
    }

    private fun ExecutionLease.snapshotKey() = WorkoutExitSnapshotKey(
        executionId = executionId,
        sessionId = sessionId,
        profileId = profileId,
    )
}

internal fun RepMetricData.deepCopyForExitSnapshot() = copy(
    concentricPositions = concentricPositions.copyOf(),
    concentricLoadsA = concentricLoadsA.copyOf(),
    concentricLoadsB = concentricLoadsB.copyOf(),
    concentricVelocities = concentricVelocities.copyOf(),
    concentricTimestamps = concentricTimestamps.copyOf(),
    eccentricPositions = eccentricPositions.copyOf(),
    eccentricLoadsA = eccentricLoadsA.copyOf(),
    eccentricLoadsB = eccentricLoadsB.copyOf(),
    eccentricVelocities = eccentricVelocities.copyOf(),
    eccentricTimestamps = eccentricTimestamps.copyOf(),
)

internal fun ForceCurveResult.deepCopyForExitSnapshot() = copy(
    normalizedForceN = normalizedForceN.copyOf(),
    normalizedPositionPct = normalizedPositionPct.copyOf(),
)

internal fun BiomechanicsRepResult.deepCopyForExitSnapshot() = copy(
    forceCurve = forceCurve.deepCopyForExitSnapshot(),
)

internal fun BiomechanicsSetSummary.deepCopyForExitSnapshot() = copy(
    repResults = repResults.map(BiomechanicsRepResult::deepCopyForExitSnapshot),
    zoneDistribution = zoneDistribution.toMap(),
    avgForceCurve = avgForceCurve?.deepCopyForExitSnapshot(),
)

internal fun WorkoutState.SetSummary.deepCopyForExitSnapshot() = copy(
    metrics = metrics.toList(),
    qualitySummary = qualitySummary?.copy(repScores = qualitySummary.repScores.toList()),
    biomechanicsSummary = biomechanicsSummary?.deepCopyForExitSnapshot(),
)
