package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.KmpUtils.currentTimeMillis
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

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

    fun toPortalProfilePreferenceMutation(
        section: ProfilePreferenceSectionSyncDto,
    ): PreparedProfilePreferenceMutation = PreparedProfilePreferenceMutation(
        wire = PortalProfilePreferenceSectionMutationDto(
            localProfileId = section.key.localProfileId,
            section = section.key.section.name,
            documentVersion = section.documentVersion,
            baseRevision = section.baseRevision,
            clientModifiedAt = kotlin.time.Instant
                .fromEpochMilliseconds(section.clientModifiedAtEpochMs)
                .toString(),
            payload = section.payload,
        ),
        key = section.key,
        sentLocalGeneration = section.localGeneration,
    )

    // ─── Session Mapping ────────────────────────────────────────────

    /**
     * Represent the data for a single mobile WorkoutSession plus its associated rep data.
     */
    data class LogicalSetSyncIdentity(
        val routineSessionId: String,
        val routineExerciseId: String,
        val setNumber: Int,
    )

    data class SessionWithReps(
        val session: WorkoutSession,
        val repMetrics: List<RepMetricData> = emptyList(),
        val repBiomechanics: List<RepBiomechanicsData> = emptyList(),
        val muscleGroup: String = "General",
        val isPr: Boolean = false,
        val prRecords: List<PersonalRecord> = emptyList(),
        val prRecord: PersonalRecord? = null, // Carries PR metadata (type, phase, volume)
        val logicalSetIdentity: LogicalSetSyncIdentity? = null,
        /**
         * True when this row was in the push delta, i.e. it was never sent or has been
         * edited since the last push. Drives the grouped-session `updatedAt` floor (see
         * [toPortalWorkoutSessionsWithTelemetry]); siblings only re-gathered to keep a
         * routine group complete are false.
         */
        val isPendingUpload: Boolean = false,
    )

    /**
     * One passing velocity-based (VBT) 1RM estimate, with the time it was computed.
     * A session gets the newest estimate that already existed when it was recorded, so
     * a historical workout is never annotated with a number derived from later lifts.
     *
     * Each exercise's list must be ordered OLDEST FIRST — by `computedAt`, then row id
     * as the tie-break, matching VelocityOneRepMaxRepository.getLatestPassing's
     * ordering — because the adapter takes the last entry that predates the session.
     */
    data class VelocityOneRepMaxPoint(val computedAt: Long, val estimatedPerCableKg: Float)

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

    /**
     * Programmed-set count for portal analytics. Repeated retry attempts that share a
     * stable logical-set identity collapse to one set; sessions without that identity
     * keep the legacy one-session-one-set behavior.
     */
    internal fun programmedSetCount(sessions: List<SessionWithReps>): Int {
        if (sessions.none { it.logicalSetIdentity != null }) return sessions.size
        val grouped = linkedSetOf<String>()
        var ungrouped = 0
        sessions.forEach { session ->
            val identity = session.logicalSetIdentity
            if (identity == null) {
                ungrouped += 1
            } else {
                grouped += "${identity.routineSessionId}\u0000${identity.routineExerciseId}\u0000${identity.setNumber}"
            }
        }
        return grouped.size + ungrouped
    }

    fun toPortalWorkoutSessions(
        sessionsWithReps: List<SessionWithReps>,
        userId: String,
        velocityEstimatesByExerciseId: Map<String, List<VelocityOneRepMaxPoint>> = emptyMap(),
        notesByPortalSessionId: Map<String, String?> = emptyMap(),
        groupPushFloorEpochMs: Long? = null,
    ): List<PortalWorkoutSessionDto> = toPortalWorkoutSessionsWithTelemetry(
        sessionsWithReps,
        userId,
        velocityEstimatesByExerciseId,
        notesByPortalSessionId = notesByPortalSessionId,
        groupPushFloorEpochMs = groupPushFloorEpochMs,
    ).sessions

    /**
     * Build portal workout sessions AND correctly-keyed telemetry in one pass.
     * Telemetry setIds are guaranteed to match the generated set IDs in the exercises.
     *
     * [velocityEstimatesByExerciseId] maps catalog exerciseId → its passing
     * velocity-based 1RM estimates (per-cable kg). The caller (SyncManager) precomputes
     * it from VelocityOneRepMaxRepository in a suspend context; the adapter stays pure
     * and picks the newest estimate that predates each session. Exercises not present
     * in the map get a null velocity estimate.
     *
     * [notesByPortalSessionId] carries the note the portal already holds for a workout
     * (keyed on `routineSessionId ?: id`, the portal session id), read back from the
     * local SessionNotes side-table. The portal writes `notes = EXCLUDED.notes` on
     * every accepted session upsert, so sending null would delete a note written on the
     * website (AF-4).
     *
     * [groupPushFloorEpochMs], when set, is the device time captured just before the
     * push gathered its payload. A grouped (routine) portal session that contains at
     * least one never-sent or locally edited row is sent with
     * `max(domainLastEdit, floor)`, because the portal's `sessions_updated_at` trigger
     * stamps `now()` on every accepted update and its LWW gate then rejects anything
     * older. Set N of a routine starts before that server time whenever rests are short
     * or the device clock lags, so the plain domain timestamp would be rejected forever.
     * Standalone sessions and untouched groups keep the domain last-edit, so a portal
     * edit of a workout this device is not currently changing still wins.
     *
     * [includeTelemetry] is false below the Inferno tier: the per-sample force curves
     * would be discarded by the caller anyway, so they are never built.
     */
    fun toPortalWorkoutSessionsWithTelemetry(
        sessionsWithReps: List<SessionWithReps>,
        userId: String,
        velocityEstimatesByExerciseId: Map<String, List<VelocityOneRepMaxPoint>> = emptyMap(),
        notesByPortalSessionId: Map<String, String?> = emptyMap(),
        groupPushFloorEpochMs: Long? = null,
        includeTelemetry: Boolean = true,
    ): PortalSessionBuildResult {
        // Group: null routineSessionId = standalone, non-null = routine group
        val standalone = sessionsWithReps.filter { it.session.routineSessionId == null }
        val routineGroups = sessionsWithReps
            .filter { it.session.routineSessionId != null }
            .groupBy { it.session.routineSessionId!! }

        val resultSessions = mutableListOf<PortalWorkoutSessionDto>()
        val resultTelemetry = mutableListOf<PortalRepTelemetryDto>()

        // Standalone sessions: each becomes its own portal workout
        for (swr in standalone) {
            val (session, telemetry) = buildPortalSession(
                listOf(swr),
                userId,
                routineSessionId = null,
                velocityEstimatesByExerciseId = velocityEstimatesByExerciseId,
                notesByPortalSessionId = notesByPortalSessionId,
                // A standalone workout is its own portal session: the portal replaces
                // only its own children, so it never needs the push floor and keeps the
                // strict LWW gate against a later web edit.
                groupPushFloorEpochMs = null,
                includeTelemetry = includeTelemetry,
            )
            resultSessions.add(session)
            resultTelemetry.addAll(telemetry)
        }

        // Routine groups: each group becomes one portal workout
        for ((routineSessionId, group) in routineGroups) {
            val (session, telemetry) = buildPortalSession(
                group,
                userId,
                routineSessionId,
                velocityEstimatesByExerciseId = velocityEstimatesByExerciseId,
                notesByPortalSessionId = notesByPortalSessionId,
                groupPushFloorEpochMs = groupPushFloorEpochMs?.takeIf { group.any { row -> row.isPendingUpload } },
                includeTelemetry = includeTelemetry,
            )
            resultSessions.add(session)
            resultTelemetry.addAll(telemetry)
        }

        return PortalSessionBuildResult(resultSessions, resultTelemetry)
    }

    private fun buildPortalSession(
        sessionsWithReps: List<SessionWithReps>,
        userId: String,
        routineSessionId: String?,
        velocityEstimatesByExerciseId: Map<String, List<VelocityOneRepMaxPoint>> = emptyMap(),
        notesByPortalSessionId: Map<String, String?> = emptyMap(),
        groupPushFloorEpochMs: Long? = null,
        includeTelemetry: Boolean = true,
    ): Pair<PortalWorkoutSessionDto, List<PortalRepTelemetryDto>> {
        val sorted = sessionsWithReps.sortedBy { it.session.timestamp }
        val first = sorted.first().session
        val portalSessionId = routineSessionId ?: first.id

        // Aggregate metrics across all exercises in this workout
        val totalDuration = sorted.sumOf { (it.session.duration / 1000).toInt() } // ms → s
        // Portal stores per-cable volume (KD-8); it does NOT double it. Display shows
        // per-cable first, with the total (× cable_count) only when the cable count is known.
        // - Measured totalVolumeKg is TOTAL (both cables) → divide by the same
        //   valid 1/2 cable count sent on the wire; unknown counts fall back to 1
        // - Fallback weightPerCableKg × totalReps is already per-cable
        val totalVolume = sorted.sumOf { swr ->
            val session = swr.session
            val cables = PortalMappings.cableCountToWire(session.cableCount) ?: 1
            val perCableVolume = session.totalVolumeKg?.let { it / cables }
                ?: (session.weightPerCableKg * session.totalReps)
            perCableVolume.toDouble()
        }.toFloat()
        val totalSets = programmedSetCount(sorted)
        val totalPrs = sorted.count { it.isPr }

        // Build exercise entries with telemetry (setIds are generated inside)
        val exerciseBundles = sorted.mapIndexed { index, swr ->
            buildPortalExerciseWithTelemetry(
                swr,
                portalSessionId,
                index,
                velocityEstimatesByExerciseId,
                includeTelemetry,
            )
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
            // Fall back to a "Just Lift" label for untagged freestyle sessions.
            // Just Lift sessions are saved without a routine or exercise, so both
            // routineName and exerciseName are null until the user tags them.
            // Without this, the portal session title is blank (regression after
            // mobile PR #435 removed Just Lift exercise auto-detection).
            name = first.routineName
                ?: first.exerciseName
                ?: "Just Lift".takeIf { first.isJustLift },
            startedAt = epochToIso8601(first.timestamp),
            // LWW gate: wire the domain last-edit, never encode-time wall clock.
            // Stamping NOW() at push time made every mobile sync win against a
            // later portal edit of the same session id. The one exception is a
            // routine group with unsent local rows, which must beat the server
            // clock the portal stamped on the previous set's push — see
            // groupPushFloorEpochMs on toPortalWorkoutSessionsWithTelemetry.
            updatedAt = epochToIso8601(
                maxOf(
                    domainLastEditEpochMs(sorted.map { it.session }),
                    groupPushFloorEpochMs ?: Long.MIN_VALUE,
                ),
            ),
            // Send back the note the portal already holds; the portal's session
            // upsert writes notes = EXCLUDED.notes unconditionally (AF-4).
            notes = notesByPortalSessionId[portalSessionId],
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
    private fun buildPortalExerciseWithTelemetry(
        swr: SessionWithReps,
        portalSessionId: String,
        orderIndex: Int,
        velocityEstimatesByExerciseId: Map<String, List<VelocityOneRepMaxPoint>> = emptyMap(),
        includeTelemetry: Boolean = true,
    ): ExerciseWithTelemetry {
        val session = swr.session
        // Use stable mobile session ID as exercise ID so repeated syncs
        // upsert the same row instead of creating duplicates (issue #33).
        val exerciseId = session.id
        val setId = generateUUID()

        // Build rep summaries from RepMetricData + RepBiomechanics
        val repSummaries = buildRepSummaries(swr, setId)

        // Build telemetry using the SAME setId. Below the Inferno tier the caller drops
        // telemetry before it reaches the wire, so don't build the points at all: a
        // 50 Hz force curve is by far the largest thing in a push payload.
        val telemetry = mutableListOf<PortalRepTelemetryDto>()
        if (includeTelemetry) {
            for (rep in swr.repMetrics) {
                if (rep.concentricTimestamps.isNotEmpty() || rep.eccentricTimestamps.isNotEmpty()) {
                    telemetry.addAll(toRepTelemetry(rep, setId))
                }
            }
        }

        // One mobile session = one "set" in portal (the entire exercise execution)
        val pr = swr.prRecord ?: swr.prRecords.legacySetPrHint()
        val set = PortalSetDto(
            id = setId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetReps = session.reps,
            actualReps = session.totalReps,
            weightKg = session.weightPerCableKg,
            rpe = session.rpe,
            isPr = swr.isPr || swr.prRecords.isNotEmpty(),
            prType = pr?.prType?.name, // "MAX_WEIGHT" or "MAX_VOLUME"
            prPhase = pr?.phase?.name, // "COMBINED", "CONCENTRIC", "ECCENTRIC"
            prVolume = if (pr?.prType?.name == "MAX_VOLUME") pr.volume else null,
            workoutMode = PortalMappings.workoutModeToSync(session.mode),
            repSummaries = repSummaries,
        )

        val exercise = PortalExerciseDto(
            id = exerciseId,
            sessionId = portalSessionId,
            exerciseId = session.exerciseId, // Catalog exercise ID for identity preservation (#404)
            name = session.exerciseName ?: "Unknown Exercise",
            muscleGroup = swr.muscleGroup,
            orderIndex = orderIndex,
            // Working reps exclude warmup; fall back to totalReps when working
            // is 0 so legacy rows still get an estimate.
            estimatedOneRepMaxKg = OneRepMaxCalculator.estimate(
                session.weightPerCableKg,
                session.workingReps.takeIf { it > 0 } ?: session.totalReps,
            ).takeIf { it > 0f },
            // Velocity-based (VBT) estimate, looked up by catalog exerciseId from
            // the precomputed map. Distinct from the rep-based estimate above; null
            // when this exercise has no exerciseId or no passing velocity estimate.
            // As-of the session, not "latest": a workout from last year must not be
            // annotated with an estimate computed from lifts done after it.
            velocityEstimatedOneRepMaxKg = session.exerciseId
                ?.let { velocityEstimatesByExerciseId[it] }
                ?.lastOrNull { it.computedAt <= session.timestamp }
                ?.estimatedPerCableKg,
            // Same cable count that drives the totalVolume normalisation above;
            // only 1 or 2 go on the wire, anything else is sent as unknown (omitted).
            cableCount = PortalMappings.cableCountToWire(session.cableCount),
            sets = listOf(set),
        )
        return ExerciseWithTelemetry(exercise, telemetry)
    }

    fun toPortalPersonalRecord(
        record: PersonalRecord,
        sessionId: String?,
        muscleGroup: String,
    ): PortalPersonalRecordDto {
        val value = if (record.prType == PRType.MAX_VOLUME) {
            record.volume
        } else {
            record.weightPerCableKg
        }
        return PortalPersonalRecordDto(
            id = record.uuid,
            exerciseName = record.exerciseName.ifBlank { record.exerciseId },
            exerciseId = record.exerciseId,
            muscleGroup = muscleGroup.ifBlank { "General" },
            recordType = record.prType.name,
            value = value,
            volume = record.volume.takeIf { record.prType == PRType.MAX_VOLUME },
            weightKg = record.weightPerCableKg,
            reps = record.reps,
            workoutPhase = record.phase.name,
            sessionId = sessionId,
            achievedAt = epochToIso8601(record.timestamp),
            updatedAt = epochToIso8601(record.updatedAt ?: currentTimeMillis()),
            deletedAt = record.deletedAt?.let(::epochToIso8601),
            localProfileId = record.profileId,
            workoutMode = PortalMappings.workoutModeToSync(record.workoutMode),
        )
    }

    private fun List<PersonalRecord>.legacySetPrHint(): PersonalRecord? = firstOrNull { it.phase == WorkoutPhase.CONCENTRIC && it.prType == PRType.MAX_WEIGHT }
        ?: firstOrNull { it.phase == WorkoutPhase.COMBINED && it.prType == PRType.MAX_WEIGHT }
        ?: firstOrNull { it.prType == PRType.MAX_WEIGHT }
        ?: firstOrNull()

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
                    cable = "A",
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
                        cable = "B",
                    ),
                )
            }
        }

        // Eccentric phase - Cable A
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
                    cable = "A",
                ),
            )
        }

        // Eccentric phase - Cable B
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
                        cable = "B",
                    ),
                )
            }
        }

        return points
    }

    // ─── Routine Mapping ────────────────────────────────────────────

    /** A timed duration accepted by the app's editor and workout runtime. */
    fun sanitizeDurationSeconds(seconds: Int?): Int? =
        RoutineExercise.supportedTimedDurationSeconds(seconds)

    /**
     * The only producer of [PortalRoutineExerciseSyncDto.durationSeconds].
     *  - supported duration -> seconds;
     *  - no duration and [known] -> explicit JSON null, which clears the server value;
     *  - no duration and not [known] -> null, so the key is omitted and the server keeps
     *    its stored duration (a pre-upgrade row may hold a stale NULL).
     */
    fun durationSecondsWire(seconds: Int?, known: Boolean): JsonPrimitive? =
        sanitizeDurationSeconds(seconds)?.let { JsonPrimitive(it) } ?: if (known) JsonNull else null

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
                exerciseId = ex.exercise.id, // Catalog exercise ID (#404)
                name = ex.exercise.name,
                displayName = ex.exercise.displayName, // Disambiguated name (#404)
                muscleGroup = ex.exercise.muscleGroup,
                exerciseEquipment = ex.exercise.equipment, // Equipment snapshot (#404)
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
                perSetReps = ex.setReps.takeIf { it.size > 1 || it.any { r -> r == null } }
                    ?.let { Json.encodeToString(ListSerializer(Int.serializer().nullable), it) },
                isAmrap = ex.isAMRAP,
                // #635: stored flag (catalog/row) with equipment-derivation fallback —
                // never derive directly from equipment, which is unreliable for
                // empty-equipment catalog cable lifts.
                isBodyweight = ex.exercise.isBodyweight,
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
                rackBehaviorOverrides = ex.rackBehaviorOverrides.takeIf { it.isNotEmpty() }
                    ?.let { overrides ->
                        Json.encodeToString(
                            overrides.mapValues { (_, v) -> v.name },
                        )
                    },
                dropSetEnabled = ex.dropSetEnabled,
                dropSetMinWeightKg = ex.dropSetMinWeightKg,
                durationSeconds = durationSecondsWire(ex.duration, ex.durationSyncKnown),
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
            // Preserve the persisted routine timestamp so stale local copies do
            // not masquerade as newer than portal edits during LWW upsert.
            updatedAt = epochToIso8601(routine.updatedAt ?: routine.createdAt),
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
    /** Longest server version string accepted (a timestamptz ISO string is ~32 chars). */
    private const val MAX_CYCLE_SERVER_VERSION_LENGTH = 64

    /**
     * Returns [raw] unchanged when it is a usable portal cycle version (non-blank,
     * at most 64 chars, parseable as an ISO-8601 instant), else null. The value is
     * echoed back as baseUpdatedAt, and the portal rejects the whole push (400) for an
     * unparseable one, so a malformed value must never be stored.
     */
    fun validCycleServerVersion(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > MAX_CYCLE_SERVER_VERSION_LENGTH) return null
        return raw.takeIf { runCatching { kotlin.time.Instant.parse(it) }.isSuccess }
    }

    data class CycleWithContext(
        val cycle: TrainingCycle,
        val progress: CycleProgress? = null,
        val progression: CycleProgression? = null,
        /** Portal `updated_at` (verbatim ISO) last pulled or acknowledged; sent as baseUpdatedAt. */
        val serverUpdatedAt: String? = null,
    )

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
                echoLevelPresent = true,
                echoLevel = day.echoLevel?.name,
                eccentricLoadPercentPresent = true,
                eccentricLoadPercent = day.eccentricLoadPercent,
            )
        }

        return PortalTrainingCycleSyncDto(
            id = cycle.id,
            userId = userId,
            name = cycle.name,
            description = cycle.description,
            templateId = cycle.templateId,
            durationWeeks = durationWeeks,
            workoutDays = workoutDays,
            restDays = restDays,
            currentWeek = cycle.weekNumber,
            status = if (cycle.isActive) "active" else "draft",
            startedAt = progress?.cycleStartDate?.let { epochToIso8601(it) },
            lastUsedAt = progress?.lastCompletedDate?.let { epochToIso8601(it) },
            // LWW gate: persist the domain last-edit. Cycles are pushed on every
            // sync, so encode-time NOW() would blindly overwrite portal edits.
            updatedAt = epochToIso8601(cycle.updatedAt ?: cycle.createdAt),
            progressionSettingsPresent = true,
            progressionSettings = progressionJson,
            deloadSettings = null,
            progressStatePresent = true,
            progressState = progress?.let {
                PortalCycleProgressStateSyncDto(
                    currentDayNumber = it.currentDayNumber,
                    lastCompletedDate = it.lastCompletedDate,
                    cycleStartDate = it.cycleStartDate,
                    lastAdvancedAt = it.lastAdvancedAt,
                    completedDays = it.completedDays.sorted(),
                    missedDays = it.missedDays.sorted(),
                    rotationCount = it.rotationCount,
                )
            },
            days = days,
            baseUpdatedAt = ctx.serverUpdatedAt,
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

    /**
     * Latest domain last-edit among the mobile rows that collapse into one
     * portal workout. Falls back to [WorkoutSession.timestamp] (startedAt)
     * when a row has not yet stored updatedAt.
     */
    internal fun domainLastEditEpochMs(sessions: List<WorkoutSession>): Long =
        sessions.maxOf { it.updatedAt ?: it.timestamp }
}
