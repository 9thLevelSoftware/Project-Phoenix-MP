package com.devil.phoenixproject.domain.model

import kotlinx.serialization.Serializable

/**
 * Stable identity for a planned routine-set occurrence in one routine session.
 */
@Serializable
data class LogicalSetKey(
    val routineSessionId: String,
    val routineExerciseId: String,
    val setIndex: Int,
    val setKind: SetType,
)

/**
 * Mutable-in-time state for attempts at one [LogicalSetKey], represented immutably.
 */
@Serializable
data class PlannedSetAttemptState(
    val logicalSetKey: LogicalSetKey,
    val nextAttemptNumber: Int = 1,
    val acceptedDropCount: Int = 0,
) {
    init {
        require(acceptedDropCount in 0..MAX_ACCEPTED_DROPS) {
            "acceptedDropCount must be between 0 and $MAX_ACCEPTED_DROPS"
        }
    }
}

/** The attempt number consumed by a repeat and the resulting immutable state. */
@Serializable
data class RepeatConsumption(
    val attemptNumber: Int,
    val nextState: PlannedSetAttemptState,
)

/**
 * Consume the current attempt number for a repeat.
 */
fun PlannedSetAttemptState.consumeRepeat(acceptedDrop: Boolean): RepeatConsumption = RepeatConsumption(
    attemptNumber = nextAttemptNumber,
    nextState = copy(
        nextAttemptNumber = nextAttemptNumber + 1,
        acceptedDropCount = if (acceptedDrop) {
            (acceptedDropCount + 1).coerceAtMost(MAX_ACCEPTED_DROPS)
        } else {
            acceptedDropCount
        },
    ),
)

private const val MAX_ACCEPTED_DROPS = 2

@Serializable
enum class DropPercentage(val fraction: Float) {
    TEN(0.10f),
    TWENTY(0.20f),
    THIRTY(0.30f),
}

@Serializable
data class DropSetCandidate(
    val percentage: DropPercentage,
    val resolvedWeightPerCableKg: Float,
    val resultingExerciseMultiplier: Float,
) {
    init {
        require(resolvedWeightPerCableKg.isFinite() && resolvedWeightPerCableKg > 0f)
        require(resultingExerciseMultiplier.isFinite() && resultingExerciseMultiplier > 0f)
    }
}

enum class DropSetCandidateInvalidReason {
    INVALID_CONFIGURED_START,
    INVALID_PROGRAMMED_BASE,
    INVALID_MINIMUM,
    BELOW_MINIMUM,
    INVALID_COMMAND,
    NOT_LOWER,
}

sealed interface DropSetCandidateResolution {
    data class Valid(val candidate: DropSetCandidate) : DropSetCandidateResolution
    data class Invalid(val reason: DropSetCandidateInvalidReason) : DropSetCandidateResolution
}

fun interface DropSetFeatureGate {
    fun isEnabled(): Boolean
}

object DisabledDropSetFeatureGate : DropSetFeatureGate {
    override fun isEnabled(): Boolean = false
}

object EnabledDropSetFeatureGate : DropSetFeatureGate {
    override fun isEnabled(): Boolean = true
}

data class DropSetConfiguration(
    val enabled: Boolean,
    val minimumWeightPerCableKg: Float?,
)

enum class DropSetIneligibleReason {
    FEATURE_GATED,
    NOT_STALL_FAILURE,
    DISABLED,
    INVALID_MINIMUM,
    NOT_OLD_SCHOOL,
    NOT_CABLE_WORKING_SET,
    WARMUP,
    ECHO,
    JUST_LIFT,
    BODYWEIGHT,
    DROP_LIMIT_REACHED,
    IDENTITY_MISMATCH,
    NO_VALID_CANDIDATE,
}

data class DropSetOffer(
    val offerId: String,
    val routineIdentity: RoutineExecutionIdentity,
    val candidates: List<DropSetCandidate>,
    val remainingDrops: Int,
)

sealed interface DropSetEligibilityResult {
    data class Eligible(val offer: DropSetOffer) : DropSetEligibilityResult
    data class Ineligible(val reason: DropSetIneligibleReason) : DropSetEligibilityResult
}

@Serializable
data class RoutineExecutionIdentity(
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
    val exerciseIndex: Int,
    val setIndex: Int,
) {
    init {
        require(exerciseIndex >= 0)
        require(setIndex >= 0)
        require(logicalSetKey.routineSessionId == routineSessionId)
        require(logicalSetKey.routineExerciseId == routineExerciseId)
        require(logicalSetKey.setIndex == setIndex)
    }
}

/**
 * A load multiplier applied to one routine-exercise occurrence.
 */
@Serializable
data class ExerciseLoadOverlay(
    val routineExerciseId: String,
    val multiplier: Float = 1f,
)

/**
 * Combine every overlay for one routine-exercise occurrence without mutating its routine definition.
 */
fun Iterable<ExerciseLoadOverlay>.multiplierFor(routineExerciseId: String): Float = filter { it.routineExerciseId == routineExerciseId }.fold(1f) { combined, overlay ->
    combined * overlay.multiplier
}
