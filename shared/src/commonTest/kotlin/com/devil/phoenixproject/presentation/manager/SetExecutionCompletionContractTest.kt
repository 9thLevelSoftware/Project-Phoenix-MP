package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SetExecutionCompletionContractTest {
    @Test
    fun failsClosedForImpossibleAttemptDropAndIdentityFacts() {
        assertFailsWith<IllegalArgumentException> { completion(attemptNumber = 0) }
        assertFailsWith<IllegalArgumentException> { completion(acceptedDropCount = 3) }
        assertFailsWith<IllegalArgumentException> {
            completion(identity = identity().copy(profileId = "wrong-profile"))
        }
        assertFailsWith<IllegalArgumentException> {
            completion(plannedSetType = SetType.AMRAP)
        }
        assertFailsWith<IllegalArgumentException> {
            identity().copy(setIndex = 1)
        }
    }

    private fun completion(
        attemptNumber: Int = 1,
        acceptedDropCount: Int = 0,
        identity: RoutineExecutionIdentity? = identity(),
        plannedSetType: SetType = SetType.STANDARD,
    ) = SetExecutionCompletion(
        lease = ExecutionLease(1, "session", "profile", true, 8, false, false, false, false),
        reason = SetEndReason.STALL_FAILURE,
        routineIdentity = identity,
        attemptNumber = attemptNumber,
        acceptedDropCount = acceptedDropCount,
        plannedSetType = plannedSetType,
        programMode = ProgramMode.OldSchool,
        programmedBaseWeightPerCableKg = 50f,
        configuredStartWeightPerCableKg = 50f,
        progressionKg = 0f,
        actualReps = 5,
        targetReps = 8,
        isWarmup = false,
        isEcho = false,
        isJustLift = false,
        isBodyweight = false,
        isTimed = false,
        isAmrap = false,
        isCableExercise = true,
    )

    private fun identity() = RoutineExecutionIdentity(
        profileId = "profile",
        routineId = "routine",
        routineSessionId = "routine-session",
        routineExerciseId = "occurrence",
        logicalSetKey = LogicalSetKey("routine-session", "occurrence", 0, SetType.STANDARD),
        plannedSetId = "planned",
        exerciseIndex = 0,
        setIndex = 0,
    )
}
