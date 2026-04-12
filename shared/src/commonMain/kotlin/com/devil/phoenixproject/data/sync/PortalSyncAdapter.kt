package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
        val isPr: Boolean = false,
        val prRecord: PersonalRecord? = null, // Carries PR metadata (type, phase, volume)
    )

    /**
     * Bundle returned by buildPortalExercise, pairing the exercise DTO
     * with telemetry that references the correct generated setId.
     */
    data class ExerciseWithTelemetry(val exercise: PortalExerciseDto, val telemetry: List<PortalRepTelemetryDto>)

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
        val avgLoadB: Float,
    )

    /**
     * Result of building portal sessions -- includes both session DTOs and
     * correctly-keyed telemetry data where setIds match the generated exercise set IDs.
     */
    data class PortalSessionBuildResult(val sessions: List<PortalWorkoutSessionDto>, val telemetry: List<PortalRepTelemetryDto>)

    fun toPortalWorkoutSessions(sessionsWithReps: List<SessionWithReps>, userId: String): List<PortalWorkoutSessionDto> = toPortalWorkoutSessionsWithTelemetry(sessionsWithReps, userId).sessions

    /**
     * Build portal workout sessions AND correctly-keyed telemetry in one pass.
     * Telemetry setIds are guaranteed to match the generated set IDs in the exercises.
     */
    fun toPortalWorkoutSessionsWithTelemetry(sessionsWithReps: List<SessionWithReps>, userId: String): PortalSessionBuildResult {
        // Group: null routineSessionId = standalone, non-null = routine group
        val standalone = sessionsWithReps.filter { it.session.routineSessionId == null }
        val routineGroups = sessionsWithReps
            .filter { it.session.routineSessionId != null }
            .groupBy { it.session.routineSessionId!! }

        val resultSessions = mutableListOf<PortalWorkoutSessionDto>()
        val resultTelemetry = mutableListOf<PortalRepTelemetryDto>()

        // Standalone sessions: each becomes its own portal workout
        for (swr in standalone) {
            val (session, telemetry) = buildPortalSession(listOf(swr), userId, routineSessionId = null)
            resultSessions.add(session)
            resultTelemetry.addAll(telemetry)
        }

        // Routine groups: each group becomes one portal workout
        for ((routineSessionId, group) in routineGroups) {
            val (session, telemetry) = buildPortalSession(group, userId, routineSessionId)
            resultSessions.add(session)
            resultTelemetry.addAll(telemetry)
        }

        return PortalSessionBuildResult(resultSessions, resultTelemetry)
    }

    private fun buildPortalSession(
        sessionsWithReps: List<SessionWithReps>,
        userId: String,
        routineSessionId: String?,
    ): Pair<PortalWorkoutSessionDto, List<PortalRepTelemetryDto>> {
        val sorted = sessionsWithReps.sortedBy { it.session.timestamp }
        val first = sorted.first().session
        val portalSessionId = routineSessionId ?: first.id

        // Aggregate metrics across all exercises in this workout
        val totalDuration = sorted.sumOf { it.session.duration.toInt() / 1000 } // ms → s
        // Portal expects per-cable volume and applies ×2 transform for display.
        // - Measured totalVolumeKg is TOTAL (both cables) → divide by cableCount
        // - Fallback weightPerCableKg × totalReps is already per-cable
        val totalVolume = sorted.sumOf { swr ->
            val session = swr.session
            val cables = (session.cableCount ?: 1).coerceAtLeast(1)
            val perCableVolume = session.totalVolumeKg?.let { it / cables }
                ?: (session.weightPerCableKg * session.totalReps)
            perCableVolume.toDouble()
        }.toFloat()
        val totalSets = sorted.size // Each mobile session = one set in portal terms
        val totalPrs = sorted.count { it.isPr }

        // Build exercise entries with telemetry (setIds are generated inside)
        val exerciseBundles = sorted.mapIndexed { index, swr ->
            buildPortalExerciseWithTelemetry(swr, portalSessionId, index)
        }
        val exercises = exerciseBundles.map { it.exercise }
        val telemetry = exerciseBundles.flatMap { it.telemetry }

        // Determine the dominant workout mode (most common among exercises)
        val primaryMode = sorted
            .groupingBy { it.session.mode }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        // Aggregate biomechanics from sessions that have values
        val sessionsWithBio = sorted.filter { it.session.avgMcvMmS != null && it.session.avgMcvMmS > 0f }
        val avgVelocity = sessionsWithBio.mapNotNull { it.session.avgMcvMmS }
            .takeIf { it.isNotEmpty() }
            ?.let { vals -> vals.sum() / vals.size }
            ?.let { PortalMappings.velocityMmSToMps(it) }
        val avgAsymmetry = sorted.mapNotNull { it.session.avgAsymmetryPercent }
            .takeIf { it.isNotEmpty() }
            ?.let { vals -> vals.sum() / vals.size }
        val velLoss = sorted.mapNotNull { it.session.totalVelocityLossPercent }
            .takeIf { it.isNotEmpty() }
            ?.let { vals -> vals.sum() / vals.size }
        // Use the first session's values for single-valued fields
        val domSide = sorted.firstNotNullOfOrNull { it.session.dominantSide }
        val strProfile = sorted.firstNotNullOfOrNull { it.session.strengthProfile }

        // Safety & form aggregation
        val formScoreAvg = sorted.mapNotNull { it.session.formScore }
            .takeIf { it.isNotEmpty() }
            ?.let { vals -> vals.sum() / vals.size }
        val totalDeloads = sorted.sumOf { it.session.deloadWarningCount }
        val totalRomViolations = sorted.sumOf { it.session.romViolationCount }
        val totalSpotterActs = sorted.sumOf { it.session.spotterActivations }

        // Force metrics (peak across all sessions)
        val peakForce = sorted.mapNotNull { swr ->
            val a = swr.session.peakForceConcentricA ?: 0f
            val b = swr.session.peakForceConcentricB ?: 0f
            if (a > 0f || b > 0f) PortalMappings.loadKgToNewtons(a + b) else null
        }.maxOrNull()
        val calories = sorted.mapNotNull { it.session.estimatedCalories }
            .takeIf { it.isNotEmpty() }?.sum()
        val heaviest = sorted.mapNotNull { it.session.heaviestLiftKg }
            .maxOrNull()

        // Config context (from first session — routine sessions share config)
        val eccLoad = first.eccentricLoad.takeIf { it > 0 }
        val echoLvl = first.echoLevel.takeIf { it > 0 }
        val warmup = first.warmupReps.takeIf { it > 0 }
        val working = first.workingReps.takeIf { it > 0 }

        val sessionDto = PortalWorkoutSessionDto(
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
            exercises = exercises,
            // Session enrichment
            avgVelocityMps = avgVelocity,
            avgAsymmetryPct = avgAsymmetry,
            velocityLossPct = velLoss,
            dominantSide = domSide,
            strengthProfile = strProfile,
            formScore = formScoreAvg,
            deloadWarnings = totalDeloads.takeIf { it > 0 },
            romViolations = totalRomViolations.takeIf { it > 0 },
            spotterActivations = totalSpotterActs.takeIf { it > 0 },
            peakForceN = peakForce,
            estimatedCalories = calories,
            heaviestLiftKg = heaviest,
            eccentricLoad = eccLoad,
            echoLevel = echoLvl,
            warmupReps = warmup,
            workingReps = working,
        )
        return Pair(sessionDto, telemetry)
    }

    /**
     * Build a portal exercise DTO with correctly-keyed telemetry.
     * The generated setId is shared between the set DTO and the telemetry points,
     * ensuring FK consistency when inserted into the portal database.
     */
    private fun buildPortalExerciseWithTelemetry(swr: SessionWithReps, portalSessionId: String, orderIndex: Int): ExerciseWithTelemetry {
        val session = swr.session
        val exerciseId = generateUUID()
        val setId = generateUUID()

        // Build rep summaries from RepMetricData + RepBiomechanics
        val repSummaries = buildRepSummaries(swr, setId)

        // Build telemetry using the SAME setId
        val telemetry = mutableListOf<PortalRepTelemetryDto>()
        for (rep in swr.repMetrics) {
            if (rep.concentricTimestamps.isNotEmpty() || rep.eccentricTimestamps.isNotEmpty()) {
                telemetry.addAll(toRepTelemetry(rep, setId))
            }
        }

        // One mobile session = one "set" in portal (the entire exercise execution)
        val pr = swr.prRecord
        val set = PortalSetDto(
            id = setId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetReps = session.reps,
            actualReps = session.totalReps,
            weightKg = session.weightPerCableKg,
            rpe = session.rpe,
            isPr = swr.isPr,
            prType = pr?.prType?.name, // "MAX_WEIGHT" or "MAX_VOLUME"
            prPhase = pr?.phase?.name, // "COMBINED", "CONCENTRIC", "ECCENTRIC"
            prVolume = if (pr?.prType?.name == "MAX_VOLUME") pr.volume else null,
            workoutMode = PortalMappings.workoutModeToSync(session.mode),
            repSummaries = repSummaries,
        )

        val exercise = PortalExerciseDto(
            id = exerciseId,
            sessionId = portalSessionId,
            name = session.exerciseName ?: "Unknown Exercise",
            muscleGroup = swr.muscleGroup,
            orderIndex = orderIndex,
            sets = listOf(set),
        )
        return ExerciseWithTelemetry(exercise, telemetry)
    }

    // ─── Rep Data Mapping ───────────────────────────────────────────

    private fun buildRepSummaries(swr: SessionWithReps, setId: String): List<PortalRepSummaryDto> {
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
                    rep.avgForceConcentricA + rep.avgForceConcentricB,
                ),
                peakForceN = PortalMappings.loadKgToNewtons(
                    rep.peakForceA + rep.peakForceB,
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
                vbtZone = bio?.velocityZone,
            )
        }
    }

    // ─── Rep Telemetry (force curves) ───────────────────────────────

    /**
     * Convert RepMetricData force curve arrays into portal telemetry points.
     * This produces per-cable time-series data for force curve visualization.
     */
    fun toRepTelemetry(rep: RepMetricData, setId: String): List<PortalRepTelemetryDto> {
        val points = mutableListOf<PortalRepTelemetryDto>()

        // Concentric phase - Cable A (left)
        for (i in rep.concentricTimestamps.indices) {
            points.add(
                PortalRepTelemetryDto(
                    id = generateUUID(),
                    setId = setId,
                    timestampMs = rep.startTimestamp + rep.concentricTimestamps[i],
                    forceN = if (i < rep.concentricLoadsA.size) {
                        PortalMappings.loadKgToNewtons(rep.concentricLoadsA[i])
                    } else {
                        null
                    },
                    velocityMps = if (i < rep.concentricVelocities.size) {
                        PortalMappings.velocityMmSToMps(rep.concentricVelocities[i])
                    } else {
                        null
                    },
                    positionMm = if (i < rep.concentricPositions.size) {
                        rep.concentricPositions[i]
                    } else {
                        null
                    },
                    cable = "left",
                ),
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
                        velocityMps = if (i < rep.concentricVelocities.size) {
                            PortalMappings.velocityMmSToMps(rep.concentricVelocities[i])
                        } else {
                            null
                        },
                        positionMm = if (i < rep.concentricPositions.size) {
                            rep.concentricPositions[i]
                        } else {
                            null
                        },
                        cable = "right",
                    ),
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
                    forceN = if (i < rep.eccentricLoadsA.size) {
                        PortalMappings.loadKgToNewtons(rep.eccentricLoadsA[i])
                    } else {
                        null
                    },
                    velocityMps = if (i < rep.eccentricVelocities.size) {
                        PortalMappings.velocityMmSToMps(rep.eccentricVelocities[i])
                    } else {
                        null
                    },
                    positionMm = if (i < rep.eccentricPositions.size) {
                        rep.eccentricPositions[i]
                    } else {
                        null
                    },
                    cable = "left",
                ),
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
                        velocityMps = if (i < rep.eccentricVelocities.size) {
                            PortalMappings.velocityMmSToMps(rep.eccentricVelocities[i])
                        } else {
                            null
                        },
                        positionMm = if (i < rep.eccentricPositions.size) {
                            rep.eccentricPositions[i]
                        } else {
                            null
                        },
                        cable = "right",
                    ),
                )
            }
        }

        return points
    }

    // ─── Routine Mapping ────────────────────────────────────────────

    /**
     * Convert a mobile Routine to portal-format DTO.
     */
    fun toPortalRoutine(routine: Routine, userId: String): PortalRoutineSyncDto {
        // Map superset colors: index → name
        val colorNames = mapOf(
            SupersetColors.INDIGO to "indigo",
            SupersetColors.PINK to "pink",
            SupersetColors.GREEN to "green",
            SupersetColors.AMBER to "amber",
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
                eccentricLoad = if (ex.programMode == ProgramMode.Echo) {
                    ex.eccentricLoad.name
                } else {
                    null
                },
                echoLevel = if (ex.programMode == ProgramMode.Echo) {
                    ex.echoLevel.name
                } else {
                    null
                },
                perSetEchoLevels = ex.setEchoLevels.takeIf { it.isNotEmpty() }
                    ?.let { levels ->
                        Json.encodeToString(
                            ListSerializer(String.serializer().nullable),
                            levels.map { it?.name },
                        )
                    },
                warmupSets = ex.warmupSets.takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(it) },
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
            exercises = exercises,
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

    // ─── Training Cycle Mapping ─────────────────────────────────────────

    /**
     * Data bundle for a cycle with its optional progress + progression.
     * SyncRepository gathers these for push.
     */
    data class CycleWithContext(val cycle: TrainingCycle, val progress: CycleProgress? = null, val progression: CycleProgression? = null)

    /**
     * Convert a mobile TrainingCycle (with context) to portal-format DTO.
     *
     * Schema translation:
     *  - is_active → status ("active" / "draft")
     *  - days.count(!rest) → workoutDays, days.count(rest) → restDays
     *  - ceil(days.size / 7) → durationWeeks
     *  - CycleProgress.cycleStartDate → startedAt
     *  - CycleProgress.lastCompletedDate → lastUsedAt
     *  - CycleProgression → progressionSettings (JSON)
     *
     * Note: weekNumber defaults to 1 since mobile DB has no week_number column.
     * The portal's current_week is set to 1 unless CycleProgress tracks it.
     */
    fun toPortalTrainingCycle(ctx: CycleWithContext, userId: String): PortalTrainingCycleSyncDto {
        val cycle = ctx.cycle
        val progress = ctx.progress
        val progression = ctx.progression

        val workoutDays = cycle.days.count { !it.isRestDay }
        val restDays = cycle.days.count { it.isRestDay }
        val durationWeeks = if (cycle.days.isEmpty()) {
            1
        } else {
            ((cycle.days.size + 6) / 7) // ceil division
        }

        val progressionJson = progression?.let {
            Json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                buildMap {
                    put("frequencyCycles", it.frequencyCycles.toString())
                    it.weightIncreasePercent?.let { w -> put("weightIncreasePercent", w.toString()) }
                    if (it.echoLevelIncrease) put("echoLevelIncrease", "true")
                    it.eccentricLoadIncreasePercent?.let { e -> put("eccentricLoadIncreasePercent", e.toString()) }
                },
            )
        }

        val days = cycle.days.map { day ->
            PortalCycleDaySyncDto(
                id = day.id,
                cycleId = cycle.id,
                dayNumber = day.dayNumber,
                dayType = if (day.isRestDay) "rest" else "workout",
                routineId = day.routineId,
                weightAdjustment = day.weightProgressionPercent ?: 0f,
                repModifier = day.repModifier ?: 0,
                restOverride = day.restTimeOverrideSeconds,
                restType = null,
                notes = day.name,
            )
        }

        return PortalTrainingCycleSyncDto(
            id = cycle.id,
            userId = userId,
            name = cycle.name,
            description = cycle.description,
            durationWeeks = durationWeeks,
            workoutDays = workoutDays,
            restDays = restDays,
            currentWeek = cycle.weekNumber,
            status = if (cycle.isActive) "active" else "draft",
            startedAt = progress?.cycleStartDate?.let { epochToIso8601(it) },
            lastUsedAt = progress?.lastCompletedDate?.let { epochToIso8601(it) },
            progressionSettings = progressionJson,
            deloadSettings = null,
            days = days,
        )
    }

    // ─── Phase Statistics (GAP 7) ──────────────────────────────────

    /**
     * Convert SQLDelight PhaseStatistics to portal DTO.
     * Velocities are converted from mm/s to m/s.
     */
    fun toPortalPhaseStatistics(stats: com.devil.phoenixproject.database.PhaseStatistics): PortalPhaseStatisticsDto = PortalPhaseStatisticsDto(
        id = generateUUID(),
        sessionId = stats.sessionId,
        concentricKgAvg = stats.concentricKgAvg.toFloat(),
        concentricKgMax = stats.concentricKgMax.toFloat(),
        concentricVelAvg = PortalMappings.velocityMmSToMps(stats.concentricVelAvg.toFloat()),
        concentricVelMax = PortalMappings.velocityMmSToMps(stats.concentricVelMax.toFloat()),
        concentricWattAvg = stats.concentricWattAvg.toFloat(),
        concentricWattMax = stats.concentricWattMax.toFloat(),
        eccentricKgAvg = stats.eccentricKgAvg.toFloat(),
        eccentricKgMax = stats.eccentricKgMax.toFloat(),
        eccentricVelAvg = PortalMappings.velocityMmSToMps(stats.eccentricVelAvg.toFloat()),
        eccentricVelMax = PortalMappings.velocityMmSToMps(stats.eccentricVelMax.toFloat()),
        eccentricWattAvg = stats.eccentricWattAvg.toFloat(),
        eccentricWattMax = stats.eccentricWattMax.toFloat(),
    )

    // ─── Exercise Signatures (GAP 8) ────────────────────────────────

    /**
     * Convert SQLDelight ExerciseSignature to portal DTO.
     */
    fun toPortalExerciseSignature(sig: com.devil.phoenixproject.database.ExerciseSignature): PortalExerciseSignatureDto = PortalExerciseSignatureDto(
        id = generateUUID(),
        exerciseId = sig.exerciseId,
        romMm = sig.romMm.toFloat(),
        durationMs = sig.durationMs,
        symmetryRatio = sig.symmetryRatio.toFloat(),
        velocityProfile = sig.velocityProfile,
        cableConfig = sig.cableConfig,
        sampleCount = sig.sampleCount.toInt(),
        confidence = sig.confidence.toFloat(),
        updatedAt = epochToIso8601(sig.updatedAt),
    )

    // ─── VBT Assessments (GAP 9) ────────────────────────────────────

    /**
     * Convert SQLDelight AssessmentResult to portal DTO.
     */
    fun toPortalAssessmentResult(result: com.devil.phoenixproject.database.AssessmentResult): PortalAssessmentResultDto = PortalAssessmentResultDto(
        id = result.id.toString(),
        exerciseId = result.exerciseId,
        estimatedOneRepMaxKg = result.estimatedOneRepMaxKg.toFloat(),
        loadVelocityData = result.loadVelocityData,
        assessmentSessionId = result.assessmentSessionId,
        userOverrideKg = result.userOverrideKg?.toFloat(),
        createdAt = epochToIso8601(result.createdAt),
    )

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
