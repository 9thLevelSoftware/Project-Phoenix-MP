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
)

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
fun Iterable<ExerciseLoadOverlay>.multiplierFor(routineExerciseId: String): Float =
    filter { it.routineExerciseId == routineExerciseId }.fold(1f) { combined, overlay ->
        combined * overlay.multiplier
    }
