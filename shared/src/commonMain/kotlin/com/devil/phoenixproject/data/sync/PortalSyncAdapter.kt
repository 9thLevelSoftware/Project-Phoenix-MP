package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Transforms mobile data structures into portal-compatible DTOs.
 *
 * The core challenge: mobile's WorkoutSession represents one exercise execution,
 * while the portal expects a 3-tier hierarchy:
 *   workout_sessions → exercises → sets → rep_summaries
 *
 * This adapter handles:
 *  - Grouping mobile sessions by routineSessionId into portal workout sessions
 *  - Unit conversions (mm/s → m/s, kg → N, cable A/B → left/right)
 *  - Rep data mapping (RepMetricData → rep_summaries)
 *  - Routine/superset mapping
 */
object PortalSyncAdapter {

    // ─── Session Mapping ────────────────────────────────────────────

    /**
     * Represent the data for a single mobile WorkoutSession plus its associated rep data.
     */
    data class SessionWithReps(
        val session: WorkoutSession,
        val repMetrics: List<RepMetricData> = emptyList(),
        val repBiomechanics: List<RepBiomechanicsData> = emptyList(),
        val muscleGroup: String = "General",
        val isPr: Boolean = false
    )

    /**
     * Lightweight representation of RepBiomechanics DB row for sync.
     */
    data class RepBiomechanicsData(
        val repNumber: Int,
        val mcvMmS: Float,
        val peakVelocityMmS: Float,
        val velocityZone: String,
        val asymmetryPercent: Float,
        val dominantSide: String,
        val avgLoadA: Float,
        val avgLoadB: Float
    )

    /**
     * Convert a list of mobile WorkoutSessions (with rep data) into portal workout session DTOs.
     *
     * Sessions are grouped by routineSessionId:
     *  - Sessions sharing a routineSessionId → one portal workout with multiple exercises
     *  - Standalone sessions (null routineSessionId) → one portal workout per session
     *
     * @param sessionsWithReps Mobile sessions with their associated rep metric data
     * @param userId The portal user ID (Supabase auth.uid)
     * @return List of portal-format workout session DTOs
     */
    fun toPortalWorkoutSessions(
        sessionsWithReps: List<SessionWithReps>,
        userId: String
    ): List<PortalWorkoutSessionDto> {
        // Group: null routineSessionId = standalone, non-null = routine group
        val standalone = sessionsWithReps.filter { it.session.routineSessionId == null }
        val routineGroups = sessionsWithReps
            .filter { it.session.routineSessionId != null }
            .groupBy { it.session.routineSessionId!! }

        val result = mutableListOf<PortalWorkoutSessionDto>()

        // Standalone sessions: each becomes its own portal workout
        for (swr in standalone) {
            result.add(buildPortalSession(listOf(swr), userId, routineSessionId = null))
        }

        // Routine groups: each group becomes one portal workout
        for ((routineSessionId, group) in routineGroups) {
            result.add(buildPortalSession(group, userId, routineSessionId))
        }

        return result
    }

    private fun buildPortalSession(
        sessionsWithReps: List<SessionWithReps>,
        userId: String,
        routineSessionId: String?
    ): PortalWorkoutSessionDto {
        val sorted = sessionsWithReps.sortedBy { it.session.timestamp }
        val first = sorted.first().session
        val portalSessionId = routineSessionId ?: first.id

        // Aggregate metrics across all exercises in this workout
        val totalDuration = sorted.sumOf { it.session.duration.toInt() / 1000 } // ms → s
        val totalVolume = sorted.sumOf {
            (it.session.totalVolumeKg ?: (it.session.weightPerCableKg * it.session.totalReps)).toDouble()
        }.toFloat()
        val totalSets = sorted.size // Each mobile session = one set in portal terms
        val totalPrs = 0 // PR detection happens separately

        // Build exercise entries
        val exercises = sorted.mapIndexed { index, swr ->
            buildPortalExercise(swr, portalSessionId, index)
        }

        // Determine the dominant workout mode (most common among exercises)
        val primaryMode = sorted
            .groupingBy { it.session.mode }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return PortalWorkoutSessionDto(
            id = portalSessionId,
            userId = userId,
            name = first.routineName ?: first.exerciseName,
            startedAt = epochToIso8601(first.timestamp),
            durationSeconds = totalDuration,
            totalVolume = totalVolume,
            setCount = totalSets,
            exerciseCount = sorted.size,
            prCount = totalPrs,
            routineName = first.routineName,
            workoutMode = primaryMode?.let { PortalMappings.workoutModeToSync(it) },
            routineSessionId = routineSessionId,
            exercises = exercises
        )
    }

    private fun buildPortalExercise(
        swr: SessionWithReps,
        portalSessionId: String,
        orderIndex: Int
    ): PortalExerciseDto {
        val session = swr.session
        val exerciseId = generateUUID()
        val setId = generateUUID()

        // Build rep summaries from RepMetricData + RepBiomechanics
        val repSummaries = buildRepSummaries(swr, setId)

        // One mobile session = one "set" in portal (the entire exercise execution)
        val set = PortalSetDto(
            id = setId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetReps = session.reps,
            actualReps = session.totalReps,
            weightKg = session.weightPerCableKg,
            rpe = session.rpe,
            isPr = swr.isPr,
            workoutMode = PortalMappings.workoutModeToSync(session.mode),
            repSummaries = repSummaries
        )

        return PortalExerciseDto(
            id = exerciseId,
            sessionId = portalSessionId,
            name = session.exerciseName ?: "Unknown Exercise",
            muscleGroup = swr.muscleGroup,
            orderIndex = orderIndex,
            sets = listOf(set)
        )
    }

    // ─── Rep Data Mapping ───────────────────────────────────────────

    private fun buildRepSummaries(
        swr: SessionWithReps,
        setId: String
    ): List<PortalRepSummaryDto> {
        val biomechanicsMap = swr.repBiomechanics.associateBy { it.repNumber }

        return swr.repMetrics.map { rep ->
            val bio = biomechanicsMap[rep.repNumber]

            PortalRepSummaryDto(
                id = generateUUID(),
                setId = setId,
                repNumber = rep.repNumber,
                // Velocity: mm/s → m/s
                meanVelocityMps = PortalMappings.velocityMmSToMps(rep.avgVelocityConcentric),
                peakVelocityMps = bio?.let { PortalMappings.velocityMmSToMps(it.peakVelocityMmS) }
                    ?: PortalMappings.velocityMmSToMps(rep.peakVelocity),
                // Force: kg → Newtons (combined cable A+B average)
                meanForceN = PortalMappings.loadKgToNewtons(
                    rep.avgForceConcentricA + rep.avgForceConcentricB
                ),
                peakForceN = PortalMappings.loadKgToNewtons(
                    rep.peakForceA + rep.peakForceB
                ),
                // Power: already in Watts
                powerWatts = rep.avgPowerWatts,
                // ROM: already in mm
                romMm = rep.rangeOfMotionMm,
                // TUT: concentric + eccentric duration
                tutMs = (rep.concentricDurationMs + rep.eccentricDurationMs).toInt(),
                // Cable A → left, Cable B → right (force in Newtons)
                leftForceAvg = PortalMappings.loadKgToNewtons(rep.avgForceConcentricA),
                rightForceAvg = PortalMappings.loadKgToNewtons(rep.avgForceConcentricB),
                // Asymmetry from biomechanics if available
                asymmetryPct = bio?.asymmetryPercent,
                // VBT zone from biomechanics
                vbtZone = bio?.velocityZone
            )
        }
    }

    // ─── Rep Telemetry (force curves) ───────────────────────────────

    /**
     * Convert RepMetricData force curve arrays into portal telemetry points.
     * This produces per-cable time-series data for force curve visualization.
     */
    fun toRepTelemetry(
        rep: RepMetricData,
        setId: String
    ): List<PortalRepTelemetryDto> {
        val points = mutableListOf<PortalRepTelemetryDto>()

        // Concentric phase - Cable A (left)
        for (i in rep.concentricTimestamps.indices) {
            points.add(
                PortalRepTelemetryDto(
                    id = generateUUID(),
                    setId = setId,
                    timestampMs = rep.startTimestamp + rep.concentricTimestamps[i],
                    forceN = if (i < rep.concentricLoadsA.size)
                        PortalMappings.loadKgToNewtons(rep.concentricLoadsA[i]) else null,
                    velocityMps = if (i < rep.concentricVelocities.size)
                        PortalMappings.velocityMmSToMps(rep.concentricVelocities[i]) else null,
                    positionMm = if (i < rep.concentricPositions.size)
                        rep.concentricPositions[i] else null,
                    cable = "left"
                )
            )
        }

        // Concentric phase - Cable B (right)
        for (i in rep.concentricTimestamps.indices) {
            if (i < rep.concentricLoadsB.size) {
                points.add(
                    PortalRepTelemetryDto(
                        id = generateUUID(),
                        setId = setId,
                        timestampMs = rep.startTimestamp + rep.concentricTimestamps[i],
                        forceN = PortalMappings.loadKgToNewtons(rep.concentricLoadsB[i]),
                        velocityMps = if (i < rep.concentricVelocities.size)
                            PortalMappings.velocityMmSToMps(rep.concentricVelocities[i]) else null,
                        positionMm = if (i < rep.concentricPositions.size)
                            rep.concentricPositions[i] else null,
                        cable = "right"
                    )
                )
            }
        }

        // Eccentric phase - Cable A (left)
        val eccentricOffset = rep.concentricDurationMs
        for (i in rep.eccentricTimestamps.indices) {
            points.add(
                PortalRepTelemetryDto(
                    id = generateUUID(),
                    setId = setId,
                    timestampMs = rep.startTimestamp + eccentricOffset + rep.eccentricTimestamps[i],
                    forceN = if (i < rep.eccentricLoadsA.size)
                        PortalMappings.loadKgToNewtons(rep.eccentricLoadsA[i]) else null,
                    velocityMps = if (i < rep.eccentricVelocities.size)
                        PortalMappings.velocityMmSToMps(rep.eccentricVelocities[i]) else null,
                    positionMm = if (i < rep.eccentricPositions.size)
                        rep.eccentricPositions[i] else null,
                    cable = "left"
                )
            )
        }

        // Eccentric phase - Cable B (right)
        for (i in rep.eccentricTimestamps.indices) {
            if (i < rep.eccentricLoadsB.size) {
                points.add(
                    PortalRepTelemetryDto(
                        id = generateUUID(),
                        setId = setId,
                        timestampMs = rep.startTimestamp + eccentricOffset + rep.eccentricTimestamps[i],
                        forceN = PortalMappings.loadKgToNewtons(rep.eccentricLoadsB[i]),
                        velocityMps = if (i < rep.eccentricVelocities.size)
                            PortalMappings.velocityMmSToMps(rep.eccentricVelocities[i]) else null,
                        positionMm = if (i < rep.eccentricPositions.size)
                            rep.eccentricPositions[i] else null,
                        cable = "right"
                    )
                )
            }
        }

        return points
    }

    // ─── Routine Mapping ────────────────────────────────────────────

    /**
     * Convert a mobile Routine to portal-format DTO.
     */
    fun toPortalRoutine(
        routine: Routine,
        userId: String
    ): PortalRoutineSyncDto {
        // Map superset colors: index → name
        val colorNames = mapOf(
            SupersetColors.INDIGO to "indigo",
            SupersetColors.PINK to "pink",
            SupersetColors.GREEN to "green",
            SupersetColors.AMBER to "amber"
        )

        val exercises = routine.exercises.map { ex ->
            PortalRoutineExerciseSyncDto(
                id = ex.id,
                routineId = routine.id,
                name = ex.exercise.name,
                muscleGroup = ex.exercise.muscleGroup,
                sets = ex.sets,
                reps = ex.reps,
                weight = ex.weightPerCableKg,
                restSeconds = ex.setRestSeconds.firstOrNull() ?: 60,
                mode = ex.programMode.toSyncString(),
                orderIndex = ex.orderIndex,
                // Superset
                supersetId = ex.supersetId,
                supersetColor = ex.supersetId?.let { ssId ->
                    routine.supersets.find { it.id == ssId }
                        ?.let { colorNames[it.colorIndex] ?: "indigo" }
                },
                supersetOrder = if (ex.supersetId != null) ex.orderInSuperset else null,
                // Per-set config
                perSetWeights = ex.setWeightsPerCableKg.takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(ListSerializer(Float.serializer()), it) },
                perSetRest = ex.setRestSeconds.takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(ListSerializer(Int.serializer()), it) },
                isAmrap = ex.isAMRAP,
                prPercentage = if (ex.usePercentOfPR) ex.weightPercentOfPR.toFloat() else null,
                repCountTiming = ex.repCountTiming.name,
                stopAtPosition = if (ex.stopAtTop) "TOP" else null,
                stallDetection = ex.stallDetectionEnabled,
                eccentricLoad = if (ex.programMode == ProgramMode.Echo)
                    ex.eccentricLoad.name else null,
                echoLevel = if (ex.programMode == ProgramMode.Echo)
                    ex.echoLevel.name else null
            )
        }

        return PortalRoutineSyncDto(
            id = routine.id,
            userId = userId,
            name = routine.name,
            description = routine.description,
            exerciseCount = routine.exercises.size,
            estimatedDuration = estimateRoutineDuration(routine),
            timesCompleted = routine.useCount,
            isFavorite = false,
            exercises = exercises
        )
    }

    private fun estimateRoutineDuration(routine: Routine): Int {
        // Estimate in seconds: 2 min per set + rest time
        return routine.exercises.sumOf { ex ->
            val setsTime = ex.sets * 120 // 2 min per set in seconds
            val restTime = ex.setRestSeconds.sum().takeIf { it > 0 }
                ?: (ex.sets * 60) // default 60s rest per set
            setsTime + restTime
        }
    }

    // ─── Utility ────────────────────────────────────────────────────

    /**
     * Convert epoch millis to ISO 8601 string.
     * Uses a simple manual approach (no platform-specific formatter needed).
     */
    private fun epochToIso8601(epochMs: Long): String {
        // Use kotlinx-datetime for proper formatting
        val instant = kotlin.time.Instant.fromEpochMilliseconds(epochMs)
        return instant.toString() // ISO 8601 format
    }
}
