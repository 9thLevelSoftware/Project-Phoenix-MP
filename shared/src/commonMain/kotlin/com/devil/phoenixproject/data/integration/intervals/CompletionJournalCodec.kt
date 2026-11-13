package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.domain.model.VelocityResult
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned minimal persistence codec for staged pending child writes of a workout
 * completion (issue #1384, integration-model §3.2/§3.3).
 *
 * The payload is exactly what is needed to replay
 * [com.devil.phoenixproject.data.repository.WorkoutRepository.commitCompletedSet] after a
 * crash: the session row (including profileId), its raw metric samples, the optional
 * `CompletedSet`, the rep metrics and the rep biomechanics. Nothing else is encoded —
 * no engine state, no leases, no callbacks, no runtime documents and, above all, **never
 * credentials** (there is no field for one, by construction).
 *
 * Domain types (`WorkoutSession`, `CompletedSet`, `WorkoutMetric`, `RepMetricData`,
 * `BiomechanicsRepResult`) are not `@Serializable`, so this file defines explicit
 * persistence DTOs covering every field of those constructors and converts both ways.
 *
 * Versioning: the envelope carries a `v` field. [decode] rejects unknown versions and
 * malformed payloads by returning null — a malformed child must block finalization, never
 * be silently dropped or partially replayed.
 */
object CompletionJournalCodec {

    /** Current payload version. Bump only with a matching decoder branch. */
    const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /** Encodes one pending child write. Deterministic for identical inputs. */
    fun encode(write: PendingChildWrite): String = json.encodeToString(
        PendingChildWriteEnvelopeDto.serializer(),
        PendingChildWriteEnvelopeDto(
            v = CURRENT_VERSION,
            session = PersistedSessionDto.from(write.session),
            metrics = write.metrics.map { PersistedMetricDto.from(it) },
            completedSet = write.completedSet?.let { PersistedCompletedSetDto.from(it) },
            repMetrics = write.repMetrics.map { PersistedRepMetricDto.from(it) },
            repBiomechanics = write.repBiomechanics.map { PersistedBiomechanicsRepDto.from(it) },
        ),
    )

    /**
     * Decodes a pending child write, or null when the payload is malformed or its
     * version is unknown. Unknown versions are rejected, never best-effort parsed.
     */
    fun decode(payload: String): PendingChildWrite? {
        val envelope = runCatching {
            json.decodeFromString(PendingChildWriteEnvelopeDto.serializer(), payload)
        }.getOrNull() ?: return null
        if (envelope.v != CURRENT_VERSION) return null
        return runCatching {
            PendingChildWrite(
                session = envelope.session.toDomain(),
                metrics = envelope.metrics.map { it.toDomain() },
                completedSet = envelope.completedSet?.toDomain(),
                repMetrics = envelope.repMetrics.map { it.toDomain() },
                repBiomechanics = envelope.repBiomechanics.map { it.toDomain() },
            )
        }.getOrNull()
    }
}

/**
 * The immutable persistence input of one completed-set write. This is the replay unit of
 * the finalization journal: [com.devil.phoenixproject.data.repository.WorkoutRepository
 * .commitCompletedSet] can be replayed from exactly this data, with the same ids.
 */
data class PendingChildWrite(
    val session: WorkoutSession,
    val metrics: List<WorkoutMetric>,
    val completedSet: CompletedSet?,
    val repMetrics: List<RepMetricData>,
    val repBiomechanics: List<BiomechanicsRepResult>,
)

@Serializable
internal data class PendingChildWriteEnvelopeDto(
    val v: Int,
    val session: PersistedSessionDto,
    val metrics: List<PersistedMetricDto> = emptyList(),
    val completedSet: PersistedCompletedSetDto? = null,
    val repMetrics: List<PersistedRepMetricDto> = emptyList(),
    val repBiomechanics: List<PersistedBiomechanicsRepDto> = emptyList(),
)

/** Full persistence projection of [WorkoutSession] (every column the set commit writes). */
@Serializable
internal data class PersistedSessionDto(
    val id: String,
    val timestamp: Long,
    val mode: String,
    val reps: Int,
    val weightPerCableKg: Float,
    val progressionKg: Float,
    val duration: Long,
    val totalReps: Int,
    val warmupReps: Int,
    val workingReps: Int,
    val isJustLift: Boolean,
    val stopAtTop: Boolean,
    val eccentricLoad: Int,
    val echoLevel: Int,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val routineSessionId: String? = null,
    val routineName: String? = null,
    val routineId: String? = null,
    val safetyFlags: Int = 0,
    val deloadWarningCount: Int = 0,
    val romViolationCount: Int = 0,
    val spotterActivations: Int = 0,
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val cableCount: Int? = null,
    val displayMultiplier: Int? = null,
    val externalAddedLoadKg: Float = 0f,
    val counterweightKg: Float = 0f,
    val rackItemsJson: String = "[]",
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null,
    val avgMcvMmS: Float? = null,
    val avgAsymmetryPercent: Float? = null,
    val totalVelocityLossPercent: Float? = null,
    val dominantSide: String? = null,
    val strengthProfile: String? = null,
    val formScore: Int? = null,
    val profileId: String = "default",
    val updatedAt: Long? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val avgRrIntervalMs: Float? = null,
    val rmssdMs: Float? = null,
    val hrr60Bpm: Int? = null,
    val hrr300Bpm: Int? = null,
    val maxTorsoPitchDeg: Float? = null,
    val maxTorsoRollDeg: Float? = null,
    val routineModifierApplied: Boolean = true,
) {
    fun toDomain() = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = reps,
        weightPerCableKg = weightPerCableKg,
        progressionKg = progressionKg,
        duration = duration,
        totalReps = totalReps,
        warmupReps = warmupReps,
        workingReps = workingReps,
        isJustLift = isJustLift,
        stopAtTop = stopAtTop,
        eccentricLoad = eccentricLoad,
        echoLevel = echoLevel,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = routineName,
        routineId = routineId,
        routineModifierApplied = routineModifierApplied,
        safetyFlags = safetyFlags,
        deloadWarningCount = deloadWarningCount,
        romViolationCount = romViolationCount,
        spotterActivations = spotterActivations,
        peakForceConcentricA = peakForceConcentricA,
        peakForceConcentricB = peakForceConcentricB,
        peakForceEccentricA = peakForceEccentricA,
        peakForceEccentricB = peakForceEccentricB,
        avgForceConcentricA = avgForceConcentricA,
        avgForceConcentricB = avgForceConcentricB,
        avgForceEccentricA = avgForceEccentricA,
        avgForceEccentricB = avgForceEccentricB,
        heaviestLiftKg = heaviestLiftKg,
        totalVolumeKg = totalVolumeKg,
        cableCount = cableCount,
        displayMultiplier = displayMultiplier,
        externalAddedLoadKg = externalAddedLoadKg,
        counterweightKg = counterweightKg,
        rackItemsJson = rackItemsJson,
        estimatedCalories = estimatedCalories,
        warmupAvgWeightKg = warmupAvgWeightKg,
        workingAvgWeightKg = workingAvgWeightKg,
        burnoutAvgWeightKg = burnoutAvgWeightKg,
        peakWeightKg = peakWeightKg,
        rpe = rpe,
        avgMcvMmS = avgMcvMmS,
        avgAsymmetryPercent = avgAsymmetryPercent,
        totalVelocityLossPercent = totalVelocityLossPercent,
        dominantSide = dominantSide,
        strengthProfile = strengthProfile,
        formScore = formScore,
        profileId = profileId,
        updatedAt = updatedAt,
        avgHeartRateBpm = avgHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        minHeartRateBpm = minHeartRateBpm,
        avgRrIntervalMs = avgRrIntervalMs,
        rmssdMs = rmssdMs,
        hrr60Bpm = hrr60Bpm,
        hrr300Bpm = hrr300Bpm,
        maxTorsoPitchDeg = maxTorsoPitchDeg,
        maxTorsoRollDeg = maxTorsoRollDeg,
    )

    companion object {
        fun from(session: WorkoutSession) = PersistedSessionDto(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            reps = session.reps,
            weightPerCableKg = session.weightPerCableKg,
            progressionKg = session.progressionKg,
            duration = session.duration,
            totalReps = session.totalReps,
            warmupReps = session.warmupReps,
            workingReps = session.workingReps,
            isJustLift = session.isJustLift,
            stopAtTop = session.stopAtTop,
            eccentricLoad = session.eccentricLoad,
            echoLevel = session.echoLevel,
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = session.routineSessionId,
            routineName = session.routineName,
            routineId = session.routineId,
            safetyFlags = session.safetyFlags,
            deloadWarningCount = session.deloadWarningCount,
            romViolationCount = session.romViolationCount,
            spotterActivations = session.spotterActivations,
            peakForceConcentricA = session.peakForceConcentricA,
            peakForceConcentricB = session.peakForceConcentricB,
            peakForceEccentricA = session.peakForceEccentricA,
            peakForceEccentricB = session.peakForceEccentricB,
            avgForceConcentricA = session.avgForceConcentricA,
            avgForceConcentricB = session.avgForceConcentricB,
            avgForceEccentricA = session.avgForceEccentricA,
            avgForceEccentricB = session.avgForceEccentricB,
            heaviestLiftKg = session.heaviestLiftKg,
            totalVolumeKg = session.totalVolumeKg,
            cableCount = session.cableCount,
            displayMultiplier = session.displayMultiplier,
            externalAddedLoadKg = session.externalAddedLoadKg,
            counterweightKg = session.counterweightKg,
            rackItemsJson = session.rackItemsJson,
            estimatedCalories = session.estimatedCalories,
            warmupAvgWeightKg = session.warmupAvgWeightKg,
            workingAvgWeightKg = session.workingAvgWeightKg,
            burnoutAvgWeightKg = session.burnoutAvgWeightKg,
            peakWeightKg = session.peakWeightKg,
            rpe = session.rpe,
            avgMcvMmS = session.avgMcvMmS,
            avgAsymmetryPercent = session.avgAsymmetryPercent,
            totalVelocityLossPercent = session.totalVelocityLossPercent,
            dominantSide = session.dominantSide,
            strengthProfile = session.strengthProfile,
            formScore = session.formScore,
            profileId = session.profileId,
            updatedAt = session.updatedAt,
            avgHeartRateBpm = session.avgHeartRateBpm,
            maxHeartRateBpm = session.maxHeartRateBpm,
            minHeartRateBpm = session.minHeartRateBpm,
            avgRrIntervalMs = session.avgRrIntervalMs,
            rmssdMs = session.rmssdMs,
            hrr60Bpm = session.hrr60Bpm,
            hrr300Bpm = session.hrr300Bpm,
            maxTorsoPitchDeg = session.maxTorsoPitchDeg,
            maxTorsoRollDeg = session.maxTorsoRollDeg,
            routineModifierApplied = session.routineModifierApplied,
        )
    }
}

@Serializable
internal data class PersistedMetricDto(
    val timestamp: Long,
    val loadA: Float,
    val loadB: Float,
    val positionA: Float,
    val positionB: Float,
    val ticks: Long = 0L,
    val velocityA: Double = 0.0,
    val velocityB: Double = 0.0,
    val status: Int = 0,
    val heartRateBpm: Int? = null,
    val rrIntervalMs: Float? = null,
    val torsoPitchDeg: Float? = null,
    val torsoRollDeg: Float? = null,
    val dynamicAccMg: Int? = null,
    val chestAccXMg: Int? = null,
    val chestAccYMg: Int? = null,
    val chestAccZMg: Int? = null,
) {
    fun toDomain() = WorkoutMetric(
        timestamp = timestamp,
        loadA = loadA,
        loadB = loadB,
        positionA = positionA,
        positionB = positionB,
        ticks = ticks,
        velocityA = velocityA,
        velocityB = velocityB,
        status = status,
        heartRateBpm = heartRateBpm,
        rrIntervalMs = rrIntervalMs,
        torsoPitchDeg = torsoPitchDeg,
        torsoRollDeg = torsoRollDeg,
        dynamicAccMg = dynamicAccMg,
        chestAccXMg = chestAccXMg,
        chestAccYMg = chestAccYMg,
        chestAccZMg = chestAccZMg,
    )

    companion object {
        fun from(metric: WorkoutMetric) = PersistedMetricDto(
            timestamp = metric.timestamp,
            loadA = metric.loadA,
            loadB = metric.loadB,
            positionA = metric.positionA,
            positionB = metric.positionB,
            ticks = metric.ticks,
            velocityA = metric.velocityA,
            velocityB = metric.velocityB,
            status = metric.status,
            heartRateBpm = metric.heartRateBpm,
            rrIntervalMs = metric.rrIntervalMs,
            torsoPitchDeg = metric.torsoPitchDeg,
            torsoRollDeg = metric.torsoRollDeg,
            dynamicAccMg = metric.dynamicAccMg,
            chestAccXMg = metric.chestAccXMg,
            chestAccYMg = metric.chestAccYMg,
            chestAccZMg = metric.chestAccZMg,
        )
    }
}

@Serializable
internal data class PersistedCompletedSetDto(
    val id: String,
    val sessionId: String,
    val plannedSetId: String? = null,
    val setNumber: Int,
    val setType: String,
    val actualReps: Int,
    val actualWeightKg: Float,
    val loggedRpe: Int? = null,
    val isPr: Boolean = false,
    val completedAt: Long,
    val setEndReason: String = SetEndReason.UNKNOWN.name,
    val routineExerciseId: String? = null,
    val attemptNumber: Int = 1,
) {
    fun toDomain(): CompletedSet {
        val type = SetType.entries.firstOrNull { it.name == setType } ?: return malformed()
        val endReason = SetEndReason.fromPersisted(setEndReason)
        return CompletedSet(
            id = id,
            sessionId = sessionId,
            plannedSetId = plannedSetId,
            setNumber = setNumber,
            setType = type,
            actualReps = actualReps,
            actualWeightKg = actualWeightKg,
            loggedRpe = loggedRpe,
            isPr = isPr,
            completedAt = completedAt,
            setEndReason = endReason,
            routineExerciseId = routineExerciseId,
            attemptNumber = attemptNumber,
        )
    }

    private fun malformed(): Nothing = throw IllegalStateException("unknown set type $setType")

    companion object {
        fun from(set: CompletedSet) = PersistedCompletedSetDto(
            id = set.id,
            sessionId = set.sessionId,
            plannedSetId = set.plannedSetId,
            setNumber = set.setNumber,
            setType = set.setType.name,
            actualReps = set.actualReps,
            actualWeightKg = set.actualWeightKg,
            loggedRpe = set.loggedRpe,
            isPr = set.isPr,
            completedAt = set.completedAt,
            setEndReason = set.setEndReason.name,
            routineExerciseId = set.routineExerciseId,
            attemptNumber = set.attemptNumber,
        )
    }
}

@Serializable
internal data class PersistedRepMetricDto(
    val repNumber: Int,
    val isWarmup: Boolean,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val concentricDurationMs: Long,
    val concentricPositions: List<Float>,
    val concentricLoadsA: List<Float>,
    val concentricLoadsB: List<Float>,
    val concentricVelocities: List<Float>,
    val concentricTimestamps: List<Long>,
    val eccentricDurationMs: Long,
    val eccentricPositions: List<Float>,
    val eccentricLoadsA: List<Float>,
    val eccentricLoadsB: List<Float>,
    val eccentricVelocities: List<Float>,
    val eccentricTimestamps: List<Long>,
    val peakForceA: Float,
    val peakForceB: Float,
    val avgForceConcentricA: Float,
    val avgForceConcentricB: Float,
    val avgForceEccentricA: Float,
    val avgForceEccentricB: Float,
    val peakVelocity: Float,
    val avgVelocityConcentric: Float,
    val avgVelocityEccentric: Float,
    val rangeOfMotionMm: Float,
    val peakPowerWatts: Float,
    val avgPowerWatts: Float,
) {
    fun toDomain() = RepMetricData(
        repNumber = repNumber,
        isWarmup = isWarmup,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        durationMs = durationMs,
        concentricDurationMs = concentricDurationMs,
        concentricPositions = concentricPositions.toFloatArray(),
        concentricLoadsA = concentricLoadsA.toFloatArray(),
        concentricLoadsB = concentricLoadsB.toFloatArray(),
        concentricVelocities = concentricVelocities.toFloatArray(),
        concentricTimestamps = concentricTimestamps.toLongArray(),
        eccentricDurationMs = eccentricDurationMs,
        eccentricPositions = eccentricPositions.toFloatArray(),
        eccentricLoadsA = eccentricLoadsA.toFloatArray(),
        eccentricLoadsB = eccentricLoadsB.toFloatArray(),
        eccentricVelocities = eccentricVelocities.toFloatArray(),
        eccentricTimestamps = eccentricTimestamps.toLongArray(),
        peakForceA = peakForceA,
        peakForceB = peakForceB,
        avgForceConcentricA = avgForceConcentricA,
        avgForceConcentricB = avgForceConcentricB,
        avgForceEccentricA = avgForceEccentricA,
        avgForceEccentricB = avgForceEccentricB,
        peakVelocity = peakVelocity,
        avgVelocityConcentric = avgVelocityConcentric,
        avgVelocityEccentric = avgVelocityEccentric,
        rangeOfMotionMm = rangeOfMotionMm,
        peakPowerWatts = peakPowerWatts,
        avgPowerWatts = avgPowerWatts,
    )

    companion object {
        fun from(metric: RepMetricData) = PersistedRepMetricDto(
            repNumber = metric.repNumber,
            isWarmup = metric.isWarmup,
            startTimestamp = metric.startTimestamp,
            endTimestamp = metric.endTimestamp,
            durationMs = metric.durationMs,
            concentricDurationMs = metric.concentricDurationMs,
            concentricPositions = metric.concentricPositions.toList(),
            concentricLoadsA = metric.concentricLoadsA.toList(),
            concentricLoadsB = metric.concentricLoadsB.toList(),
            concentricVelocities = metric.concentricVelocities.toList(),
            concentricTimestamps = metric.concentricTimestamps.toList(),
            eccentricDurationMs = metric.eccentricDurationMs,
            eccentricPositions = metric.eccentricPositions.toList(),
            eccentricLoadsA = metric.eccentricLoadsA.toList(),
            eccentricLoadsB = metric.eccentricLoadsB.toList(),
            eccentricVelocities = metric.eccentricVelocities.toList(),
            eccentricTimestamps = metric.eccentricTimestamps.toList(),
            peakForceA = metric.peakForceA,
            peakForceB = metric.peakForceB,
            avgForceConcentricA = metric.avgForceConcentricA,
            avgForceConcentricB = metric.avgForceConcentricB,
            avgForceEccentricA = metric.avgForceEccentricA,
            avgForceEccentricB = metric.avgForceEccentricB,
            peakVelocity = metric.peakVelocity,
            avgVelocityConcentric = metric.avgVelocityConcentric,
            avgVelocityEccentric = metric.avgVelocityEccentric,
            rangeOfMotionMm = metric.rangeOfMotionMm,
            peakPowerWatts = metric.peakPowerWatts,
            avgPowerWatts = metric.avgPowerWatts,
        )
    }
}

@Serializable
internal data class PersistedBiomechanicsRepDto(
    val repNumber: Int,
    val timestamp: Long,
    val velocity: PersistedVelocityDto,
    val forceCurve: PersistedForceCurveDto,
    val asymmetry: PersistedAsymmetryDto,
) {
    fun toDomain() = BiomechanicsRepResult(
        velocity = velocity.toDomain(),
        forceCurve = forceCurve.toDomain(),
        asymmetry = asymmetry.toDomain(),
        repNumber = repNumber,
        timestamp = timestamp,
    )

    companion object {
        fun from(result: BiomechanicsRepResult) = PersistedBiomechanicsRepDto(
            repNumber = result.repNumber,
            timestamp = result.timestamp,
            velocity = PersistedVelocityDto.from(result.velocity),
            forceCurve = PersistedForceCurveDto.from(result.forceCurve),
            asymmetry = PersistedAsymmetryDto.from(result.asymmetry),
        )
    }
}

@Serializable
internal data class PersistedVelocityDto(
    val meanConcentricVelocityMmS: Float,
    val peakVelocityMmS: Float,
    val zone: String,
    val velocityLossPercent: Float? = null,
    val estimatedRepsRemaining: Int? = null,
    val shouldStopSet: Boolean = false,
    val repNumber: Int,
) {
    fun toDomain() = VelocityResult(
        meanConcentricVelocityMmS = meanConcentricVelocityMmS,
        peakVelocityMmS = peakVelocityMmS,
        zone = BiomechanicsVelocityZone.entries.firstOrNull { it.name == zone }
            ?: throw IllegalStateException("unknown velocity zone $zone"),
        velocityLossPercent = velocityLossPercent,
        estimatedRepsRemaining = estimatedRepsRemaining,
        shouldStopSet = shouldStopSet,
        repNumber = repNumber,
    )

    companion object {
        fun from(velocity: VelocityResult) = PersistedVelocityDto(
            meanConcentricVelocityMmS = velocity.meanConcentricVelocityMmS,
            peakVelocityMmS = velocity.peakVelocityMmS,
            zone = velocity.zone.name,
            velocityLossPercent = velocity.velocityLossPercent,
            estimatedRepsRemaining = velocity.estimatedRepsRemaining,
            shouldStopSet = velocity.shouldStopSet,
            repNumber = velocity.repNumber,
        )
    }
}

@Serializable
internal data class PersistedForceCurveDto(
    val normalizedForceN: List<Float>,
    val normalizedPositionPct: List<Float>,
    val stickingPointPct: Float? = null,
    val strengthProfile: String,
    val repNumber: Int,
) {
    fun toDomain() = ForceCurveResult(
        normalizedForceN = normalizedForceN.toFloatArray(),
        normalizedPositionPct = normalizedPositionPct.toFloatArray(),
        stickingPointPct = stickingPointPct,
        strengthProfile = StrengthProfile.entries.firstOrNull { it.name == strengthProfile }
            ?: throw IllegalStateException("unknown strength profile $strengthProfile"),
        repNumber = repNumber,
    )

    companion object {
        fun from(curve: ForceCurveResult) = PersistedForceCurveDto(
            normalizedForceN = curve.normalizedForceN.toList(),
            normalizedPositionPct = curve.normalizedPositionPct.toList(),
            stickingPointPct = curve.stickingPointPct,
            strengthProfile = curve.strengthProfile.name,
            repNumber = curve.repNumber,
        )
    }
}

@Serializable
internal data class PersistedAsymmetryDto(
    val asymmetryPercent: Float,
    val dominantSide: String,
    val avgLoadA: Float,
    val avgLoadB: Float,
    val repNumber: Int,
) {
    fun toDomain() = AsymmetryResult(
        asymmetryPercent = asymmetryPercent,
        dominantSide = dominantSide,
        avgLoadA = avgLoadA,
        avgLoadB = avgLoadB,
        repNumber = repNumber,
    )

    companion object {
        fun from(asymmetry: AsymmetryResult) = PersistedAsymmetryDto(
            asymmetryPercent = asymmetry.asymmetryPercent,
            dominantSide = asymmetry.dominantSide,
            avgLoadA = asymmetry.avgLoadA,
            avgLoadB = asymmetry.avgLoadB,
            repNumber = asymmetry.repNumber,
        )
    }
}
