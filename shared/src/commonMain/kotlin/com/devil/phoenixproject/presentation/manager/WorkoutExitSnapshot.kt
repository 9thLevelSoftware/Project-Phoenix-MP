package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState

internal data class WorkoutExitSnapshot(
    val lease: ExecutionLease,
    val terminalPath: TerminalPath,
    val session: WorkoutSession,
    val completedSet: CompletedSet?,
    val metrics: List<WorkoutMetric>,
    val repMetrics: List<RepMetricData>,
    val biomechanicsRepResults: List<BiomechanicsRepResult>,
    val presentationSummary: WorkoutState.SetSummary,
    val exerciseIndex: Int,
    val setIndex: Int,
    val isRoutineSet: Boolean,
    val shouldAccumulateRoutineCalories: Boolean,
    val shouldExportIndividualHealthSession: Boolean,
    val shouldExportIndividualBackup: Boolean,
    val shouldUpdateCycleProgress: Boolean,
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
