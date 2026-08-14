package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.util.withPlatformLock
import kotlinx.atomicfu.atomic

internal data class SetExecutionCompletion(
    val lease: ExecutionLease,
    val reason: SetEndReason,
)

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

    fun tryConsume(completion: SetExecutionCompletion): Boolean {
        while (true) {
            val current = state.value as? BodyweightCompletionState.Pending ?: return false
            if (current.completion != completion) return false
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
    val plannedSetId: String?,
    val sessionBodyWeightKg: Float,
    val routineSessionId: String?,
    val routineId: String?,
    val routineName: String?,
    val cycleId: String?,
    val cycleDayNumber: Int?,
    val routineExerciseId: String? = null,
    val attemptNumber: Int = 1,
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
