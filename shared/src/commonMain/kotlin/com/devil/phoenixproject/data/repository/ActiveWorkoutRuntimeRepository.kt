package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlinx.serialization.Serializable

@Serializable
data class RestoredWorkoutCommandTemplateSnapshot(
    val programModeName: String,
    val reps: Int,
    val weightPerCableKg: Float,
    val activeRackItemIds: List<String>,
    val externalAddedLoadKg: Float,
    val counterweightKg: Float,
    val progressionRegressionKg: Float,
    val isJustLift: Boolean,
    val useAutoStart: Boolean,
    val stopAtTop: Boolean,
    val warmupReps: Int,
    val selectedExerciseId: String?,
    val isAMRAP: Boolean,
    val lastUsedWeightKg: Float?,
    val prWeightKg: Float?,
    val stallDetectionEnabled: Boolean,
    val repCountTimingName: String,
    val echoLevelName: String,
    val eccentricLoadName: String,
    val justLiftRestSeconds: Int,
    // Issue #1392 — frozen power-loss target config, persisted as names/ints.
    // Read strictly: an unknown target-mode name resolves to inert REP_TARGET —
    // POWER never arms from an unknown or excluded mode. Old documents decode
    // with these defaults (REP), preserving their legacy meaning.
    val powerLossTargetModeName: String = "REP_TARGET",
    val powerLossThresholdPercent: Int = 20,
    val powerLossMaxWorkingReps: Int = 20,
    val powerLossRequireTwoConsecutive: Boolean = false,
    val powerLossSavedNominalReps: Int? = null,
) {
    init {
        require(programModeName in PROGRAM_MODE_NAMES)
        require(reps >= 0)
        require(weightPerCableKg.isFinite() && weightPerCableKg >= 0f)
        require(activeRackItemIds.all(String::isNotBlank))
        require(activeRackItemIds.distinct().size == activeRackItemIds.size)
        require(externalAddedLoadKg.isFinite())
        require(counterweightKg.isFinite())
        require(progressionRegressionKg.isFinite())
        require(warmupReps >= 0)
        require(selectedExerciseId == null || selectedExerciseId.isNotBlank())
        require(lastUsedWeightKg == null || (lastUsedWeightKg.isFinite() && lastUsedWeightKg >= 0f))
        require(prWeightKg == null || (prWeightKg.isFinite() && prWeightKg >= 0f))
        require(repCountTimingName in RepCountTiming.entries.map { it.name })
        require(echoLevelName in EchoLevel.entries.map { it.name })
        require(eccentricLoadName in EccentricLoad.entries.map { it.name })
        require(justLiftRestSeconds >= 0)
    }

    fun toWorkoutParameters(): WorkoutParameters = WorkoutParameters(
        programMode = programModeFromSnapshotName(programModeName),
        reps = reps,
        weightPerCableKg = weightPerCableKg,
        activeRackItemIds = activeRackItemIds,
        externalAddedLoadKg = externalAddedLoadKg,
        counterweightKg = counterweightKg,
        progressionRegressionKg = progressionRegressionKg,
        isJustLift = isJustLift,
        useAutoStart = useAutoStart,
        stopAtTop = stopAtTop,
        warmupReps = warmupReps,
        selectedExerciseId = selectedExerciseId,
        isAMRAP = isAMRAP,
        lastUsedWeightKg = lastUsedWeightKg,
        prWeightKg = prWeightKg,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTiming = RepCountTiming.valueOf(repCountTimingName),
        echoLevel = EchoLevel.valueOf(echoLevelName),
        eccentricLoad = EccentricLoad.valueOf(eccentricLoadName),
        justLiftRestSeconds = justLiftRestSeconds,
        // Issue #1392: strict persisted-enum read — unknown target-mode names
        // resolve to inert REP_TARGET (P5 propagation seam).
        powerLoss = com.devil.phoenixproject.presentation.powerloss.powerLossConfigFromPersisted(
            persistedTargetMode = powerLossTargetModeName,
            thresholdPercent = powerLossThresholdPercent,
            maxWorkingReps = powerLossMaxWorkingReps,
            requireTwoConsecutive = powerLossRequireTwoConsecutive,
            savedNominalReps = powerLossSavedNominalReps,
        ),
    )

    companion object {
        fun from(parameters: WorkoutParameters): RestoredWorkoutCommandTemplateSnapshot = RestoredWorkoutCommandTemplateSnapshot(
            programModeName = parameters.programMode.toSnapshotName(),
            reps = parameters.reps,
            weightPerCableKg = parameters.weightPerCableKg,
            activeRackItemIds = parameters.activeRackItemIds.toList(),
            externalAddedLoadKg = parameters.externalAddedLoadKg,
            counterweightKg = parameters.counterweightKg,
            progressionRegressionKg = parameters.progressionRegressionKg,
            isJustLift = parameters.isJustLift,
            useAutoStart = parameters.useAutoStart,
            stopAtTop = parameters.stopAtTop,
            warmupReps = parameters.warmupReps,
            selectedExerciseId = parameters.selectedExerciseId,
            isAMRAP = parameters.isAMRAP,
            lastUsedWeightKg = parameters.lastUsedWeightKg,
            prWeightKg = parameters.prWeightKg,
            stallDetectionEnabled = parameters.stallDetectionEnabled,
            repCountTimingName = parameters.repCountTiming.name,
            echoLevelName = parameters.echoLevel.name,
            eccentricLoadName = parameters.eccentricLoad.name,
            justLiftRestSeconds = parameters.justLiftRestSeconds,
            // Issue #1392: the frozen power-loss target config round-trips through
            // the runtime snapshot (local-only; never synced).
            powerLossTargetModeName = parameters.powerLoss.targetMode.name,
            powerLossThresholdPercent = parameters.powerLoss.thresholdPercent,
            powerLossMaxWorkingReps = parameters.powerLoss.maxWorkingReps,
            powerLossRequireTwoConsecutive = parameters.powerLoss.requireTwoConsecutive,
            powerLossSavedNominalReps = parameters.powerLoss.savedNominalReps,
        )
    }
}

@Serializable
data class RestoredRetrySourceAuthoritySnapshot(
    val sourceStableSessionId: String,
    val sourceExecutionId: String,
    val profileId: String,
    val routineIdentity: RoutineExecutionIdentity,
    val reasonName: String,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val plannedSetTypeName: String,
    val programModeName: String,
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
    val commandTemplate: RestoredWorkoutCommandTemplateSnapshot,
) {
    init {
        require(sourceStableSessionId.isNotBlank())
        require(sourceExecutionId.isNotBlank())
        require(profileId == routineIdentity.profileId)
        require(reasonName in SetEndReason.entries.map { it.name })
        require(reasonName != SetEndReason.UNKNOWN.name)
        require(attemptNumber > 0)
        require(acceptedDropCount in 0..2)
        require(plannedSetTypeName in SetType.entries.map { it.name })
        require(routineIdentity.logicalSetKey.setKind.name == plannedSetTypeName)
        require(programModeName in PROGRAM_MODE_NAMES)
        require(commandTemplate.programModeName == programModeName)
        require(programmedBaseWeightPerCableKg.isFinite() && programmedBaseWeightPerCableKg >= 0f)
        require(configuredStartWeightPerCableKg.isFinite() && configuredStartWeightPerCableKg >= 0f)
        require(progressionKg.isFinite())
        require(actualReps >= 0)
        require(targetReps == null || targetReps > 0)
        require(physicalCableCount == null || physicalCableCount > 0)
    }
}

@Serializable
data class RestoredTeardownSeedSnapshot(
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

private val PROGRAM_MODE_NAMES = setOf(
    "OLD_SCHOOL",
    "PUMP",
    "TUT",
    "TUT_BEAST",
    "ECCENTRIC_ONLY",
    "ECHO",
)

/**
 * Issue #1392 (packet P4 remainder) — versioned power-loss runtime snapshot.
 *
 * Carried inside [ActiveWorkoutRuntimeDocument] (its own explicit codec version
 * on top of the document's). STRICT READER: an unknown [codecVersion], unknown
 * target enum, or unknown restart-explanation enum throws in `init`, so the
 * strict runtime codec rejects the whole document (CORRUPT_JSON) — an unknown
 * value must never coerce into active behavior and must never arm POWER
 * (spec §7). Absent snapshot = inert (REP semantics, nulls — never zeros).
 *
 * The snapshot exists to carry the explanatory UX state across restart; the
 * armed policy itself is NEVER resumed from it (see [PowerLossRestartRecovery]):
 * the accumulator/baseline/streak are always rebaselined, and restoring never
 * issues machine commands.
 */
@Serializable
data class RestoredPowerLossRuntimeSnapshot(
    val codecVersion: Int = CURRENT_CODEC_VERSION,
    /** [TargetMode] constant name; strict. */
    val targetModeName: String = TargetMode.REP_TARGET.name,
    val thresholdPercent: Int = 0,
    val maxWorkingReps: Int = 0,
    val requireTwoConsecutive: Boolean = false,
    /** Absent nominal stays null — never a fabricated zero. */
    val savedNominalReps: Int? = null,
    /** Reducer baseline (best valid working rep) at teardown; null = no baseline. */
    val baselineMcpWatts: Float? = null,
    val referenceWorkingOrdinal: Int? = null,
    /** Two-consecutive qualifying streak at teardown. */
    val qualifyingStreak: Int = 0,
    /** Sealed observations consumed by the accumulator at teardown. */
    val sealedObservationCount: Int = 0,
    /** Observations the set expected; incomplete sums explain the rebaseline. */
    val expectedObservationCount: Int = 0,
    /** True only when the sample stream was verifiably complete at teardown. */
    val samplesComplete: Boolean = false,
    /** [PowerLossRestartExplanation] constant name; the explanatory UX state. */
    val restartExplanationName: String = PowerLossRestartExplanation.INCOMPLETE_SAMPLES.name,
) {
    init {
        require(codecVersion == CURRENT_CODEC_VERSION) {
            "Unsupported power-loss runtime snapshot codec version $codecVersion"
        }
        require(targetModeName in TargetMode.entries.map { it.name }) {
            "Unknown power-loss target mode '$targetModeName'"
        }
        require(restartExplanationName in PowerLossRestartExplanation.entries.map { it.name }) {
            "Unknown power-loss restart explanation '$restartExplanationName'"
        }
        require(thresholdPercent >= 0 && maxWorkingReps >= 0)
        require(qualifyingStreak >= 0 && sealedObservationCount >= 0 && expectedObservationCount >= 0)
        require(savedNominalReps == null || savedNominalReps >= 1)
    }

    companion object {
        const val CURRENT_CODEC_VERSION: Int = 1
    }
}

/** Explanatory UX state carried by [RestoredPowerLossRuntimeSnapshot]. */
enum class PowerLossRestartExplanation {
    /** No power-loss runtime state was persisted (legacy/REP runtime). */
    NO_SNAPSHOT,

    /** The set was interrupted mid-flight; its sample stream cannot continue. */
    INTERRUPTED_MID_SET,

    /** The snapshot's power-loss samples were incomplete at restart. */
    INCOMPLETE_SAMPLES,
}

/**
 * The restart/rebaseline outcome shown in UX: never armed, with which parts of
 * the policy state were reset and why.
 */
data class PowerLossRestartRecoveryState(
    /** ALWAYS false: an armed policy never resumes across a restart. */
    val resumeArmed: Boolean,
    val baselineReset: Boolean,
    val streakReset: Boolean,
    val accumulatorReset: Boolean,
    val explanation: PowerLossRestartExplanation,
)

/**
 * Issue #1392 restart/rebaseline contract (spec §7): on restart with incomplete
 * power-loss samples the armed policy must NEVER resume. The accumulator,
 * baseline and streak are reset (rebaseline) and the explanatory UX state comes
 * from the snapshot. Restoring issues NO machine commands — this object is pure.
 */
object PowerLossRestartRecovery {
    fun rebaselineOnRestart(snapshot: RestoredPowerLossRuntimeSnapshot?): PowerLossRestartRecoveryState {
        if (snapshot == null) {
            return PowerLossRestartRecoveryState(
                resumeArmed = false,
                baselineReset = false,
                streakReset = false,
                accumulatorReset = false,
                explanation = PowerLossRestartExplanation.NO_SNAPSHOT,
            )
        }
        return PowerLossRestartRecoveryState(
            resumeArmed = false,
            baselineReset = snapshot.baselineMcpWatts != null || snapshot.referenceWorkingOrdinal != null,
            streakReset = snapshot.qualifyingStreak > 0,
            accumulatorReset = snapshot.sealedObservationCount > 0,
            explanation = if (snapshot.samplesComplete) {
                PowerLossRestartExplanation.INTERRUPTED_MID_SET
            } else {
                PowerLossRestartExplanation.INCOMPLETE_SAMPLES
            },
        )
    }
}

fun ProgramMode.toSnapshotName(): String = when (this) {
    ProgramMode.OldSchool -> "OLD_SCHOOL"
    ProgramMode.Pump -> "PUMP"
    ProgramMode.TUT -> "TUT"
    ProgramMode.TUTBeast -> "TUT_BEAST"
    ProgramMode.EccentricOnly -> "ECCENTRIC_ONLY"
    ProgramMode.Echo -> "ECHO"
}

fun programModeFromSnapshotName(name: String): ProgramMode = when (name) {
    "OLD_SCHOOL" -> ProgramMode.OldSchool
    "PUMP" -> ProgramMode.Pump
    "TUT" -> ProgramMode.TUT
    "TUT_BEAST" -> ProgramMode.TUTBeast
    "ECCENTRIC_ONLY" -> ProgramMode.EccentricOnly
    "ECHO" -> ProgramMode.Echo
    else -> throw IllegalArgumentException("Unsupported runtime program mode")
}

@Serializable
data class ActiveWorkoutRuntimeDocument(
    val version: Int = CURRENT_VERSION,
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val sourceExecutionId: String,
    val sourceStableSessionId: String,
    val sourceAttemptNumber: Int,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String? = null,
    val sourceExerciseIndex: Int,
    val sourceSetIndex: Int,
    val sourceAuthority: RestoredRetrySourceAuthoritySnapshot,
    val teardownSeed: RestoredTeardownSeedSnapshot,
    val exerciseLoadOverlays: List<ExerciseLoadOverlay> = emptyList(),
    val attemptStates: List<PlannedSetAttemptState> = emptyList(),
    val restTransitionPlan: RestTransitionPlan? = null,
    val restDeadlineEpochMs: Long? = null,
    val pausedRestRemainingSeconds: Int? = null,
    val isRestPaused: Boolean = false,
    val originalRestDurationSeconds: Int,
    /**
     * Issue #1392: versioned power-loss runtime snapshot (null for REP/legacy
     * documents). Additive and optional so pre-#1392 documents decode unchanged;
     * restoring NEVER resumes the armed policy from it (see
     * [PowerLossRestartRecovery]) and never issues machine commands.
     */
    val powerLoss: RestoredPowerLossRuntimeSnapshot? = null,
) {
    init {
        require(version == CURRENT_VERSION)
        require(profileId.isNotBlank())
        require(routineId.isNotBlank())
        require(routineSessionId.isNotBlank())
        require(routineExerciseId.isNotBlank())
        require(sourceExecutionId.isNotBlank())
        require(sourceStableSessionId.isNotBlank())
        require(sourceAttemptNumber > 0)
        require(plannedSetId == null || plannedSetId.isNotBlank())
        require(sourceExerciseIndex >= 0)
        require(sourceSetIndex >= 0)
        require(logicalSetKey.routineSessionId == routineSessionId)
        require(logicalSetKey.routineExerciseId == routineExerciseId)
        require(logicalSetKey.setIndex == sourceSetIndex)
        require(logicalSetKey.setIndex >= 0)
        require(sourceAuthority.sourceStableSessionId == sourceStableSessionId)
        require(sourceAuthority.sourceExecutionId == sourceExecutionId)
        require(sourceAuthority.profileId == profileId)
        require(sourceAuthority.routineIdentity.profileId == profileId)
        require(sourceAuthority.routineIdentity.routineId == routineId)
        require(sourceAuthority.routineIdentity.routineSessionId == routineSessionId)
        require(sourceAuthority.routineIdentity.routineExerciseId == routineExerciseId)
        require(sourceAuthority.routineIdentity.logicalSetKey == logicalSetKey)
        require(sourceAuthority.routineIdentity.plannedSetId == plannedSetId)
        require(sourceAuthority.routineIdentity.exerciseIndex == sourceExerciseIndex)
        require(sourceAuthority.routineIdentity.setIndex == sourceSetIndex)
        require(sourceAuthority.attemptNumber == sourceAttemptNumber)
        require(teardownSeed.sourceExecutionId.toString() == sourceExecutionId)
        require(teardownSeed.sourceStableSessionId == sourceStableSessionId)
        require(teardownSeed.profileId == profileId)
        require(teardownSeed.requiresMachine == sourceAuthority.isCableExercise)
        require(exerciseLoadOverlays.all { it.routineExerciseId.isNotBlank() && it.multiplier.isFinite() && it.multiplier > 0f })
        require(
            attemptStates.all {
                it.logicalSetKey.routineSessionId == routineSessionId &&
                    it.logicalSetKey.routineExerciseId.isNotBlank() &&
                    it.logicalSetKey.setIndex >= 0 &&
                    it.nextAttemptNumber > 0
            },
        )
        require(originalRestDurationSeconds >= 0)
        require(restDeadlineEpochMs == null || pausedRestRemainingSeconds == null)
        if (isRestPaused) {
            require(restDeadlineEpochMs == null)
            require(pausedRestRemainingSeconds != null)
            require(pausedRestRemainingSeconds in 0..originalRestDurationSeconds)
        } else {
            require(pausedRestRemainingSeconds == null)
        }
        restTransitionPlan?.let { plan ->
            require(plan.sourceExecutionId == sourceExecutionId)
            require(plan.logicalSetKey == logicalSetKey)
            when (plan) {
                is RestTransitionPlan.AcceptedRetry -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.sourceCoordinates == sourceCoordinates)
                }

                is RestTransitionPlan.NormalAdvance -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.sourceCoordinates == sourceCoordinates)
                }

                is RestTransitionPlan.UnresolvedDropOffer -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.normalAdvance.sourceCoordinates == sourceCoordinates)
                }

                is RestTransitionPlan.Declined -> {
                    require(plan.normalAdvance.plannedSetId == plannedSetId)
                    require(plan.normalAdvance.sourceCoordinates == sourceCoordinates)
                }
            }
        }
    }

    private val sourceCoordinates: RestTransitionPlan.Coordinates
        get() = RestTransitionPlan.Coordinates(sourceExerciseIndex, sourceSetIndex)

    companion object {
        const val CURRENT_VERSION: Int = 2
    }
}

interface ActiveWorkoutRuntimeRepository {
    suspend fun discover(profileId: String, routineId: String): ActiveWorkoutRuntimeDiscoveryResult
    suspend fun load(profileId: String, routineSessionId: String): ActiveWorkoutRuntimeLoadResult
    suspend fun replace(profileId: String, routineSessionId: String, document: ActiveWorkoutRuntimeDocument)
    suspend fun delete(profileId: String, routineSessionId: String)
    suspend fun deleteIfRevisionMatches(
        profileId: String,
        routineSessionId: String,
        expectedRevision: ActiveWorkoutRuntimeRowRevision,
    ): Boolean
}

data class ActiveWorkoutRuntimeLookupKey(
    val profileId: String,
    val routineSessionId: String,
) {
    init {
        require(profileId.isNotBlank())
        require(routineSessionId.isNotBlank())
    }
}

sealed interface ActiveWorkoutRuntimeDiscoveryResult {
    data object Missing : ActiveWorkoutRuntimeDiscoveryResult

    data class Found(
        val lookupKey: ActiveWorkoutRuntimeLookupKey,
        val loadResult: ActiveWorkoutRuntimeLoadResult,
    ) : ActiveWorkoutRuntimeDiscoveryResult
}

sealed interface ActiveWorkoutRuntimeResumeResult {
    data object RestoredRest : ActiveWorkoutRuntimeResumeResult
    data class ManualSetReady(
        val exerciseIndex: Int,
        val setIndex: Int,
    ) : ActiveWorkoutRuntimeResumeResult {
        init {
            require(exerciseIndex >= 0)
            require(setIndex >= 0)
        }
    }
    data object FreshStart : ActiveWorkoutRuntimeResumeResult
    data object Missing : ActiveWorkoutRuntimeResumeResult
    data object RetryableFailure : ActiveWorkoutRuntimeResumeResult
    data object Superseded : ActiveWorkoutRuntimeResumeResult
}

sealed interface ActiveWorkoutRuntimeLoadResult {
    data object Missing : ActiveWorkoutRuntimeLoadResult
    data class Loaded(
        val document: ActiveWorkoutRuntimeDocument,
        val rowRevision: ActiveWorkoutRuntimeRowRevision,
    ) : ActiveWorkoutRuntimeLoadResult

    data class Rejected(
        val reason: ActiveWorkoutRuntimeRejection,
        val rowRevision: ActiveWorkoutRuntimeRowRevision,
        val attribution: ActiveWorkoutRuntimeAttributionEnvelope? = null,
    ) : ActiveWorkoutRuntimeLoadResult
}

/**
 * The deliberately small, non-executable identity envelope that discovery may
 * retain for an attributable rejected runtime row. It is never sufficient for hydration.
 */
data class ActiveWorkoutRuntimeAttributionEnvelope(
    val profileId: String,
    val routineId: String,
    val routineSessionId: String?,
    val routineExerciseId: String?,
    val sourceExerciseIndex: Int?,
    val sourceSetIndex: Int?,
)

data class ActiveWorkoutRuntimeRowRevision(
    val documentVersion: Long,
    val updatedAtEpochMs: Long,
    /** Exact encoded row identity. Keep in memory only; never log this value. */
    val encodedPayloadIdentity: String,
)

enum class ActiveWorkoutRuntimeRejection {
    CORRUPT_JSON,
    UNSUPPORTED_VERSION,
}
