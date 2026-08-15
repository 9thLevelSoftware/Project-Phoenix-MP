package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters

internal fun completionFixture(
    lease: ExecutionLease,
    reason: SetEndReason,
) = SetExecutionCompletion(
    lease = lease,
    reason = reason,
    routineIdentity = null,
    attemptNumber = 1,
    acceptedDropCount = 0,
    plannedSetType = if (lease.isAmrap) SetType.AMRAP else SetType.STANDARD,
    programMode = ProgramMode.OldSchool,
    programmedBaseWeightPerCableKg = 20f,
    configuredStartWeightPerCableKg = 20f,
    progressionKg = 0f,
    actualReps = 0,
    targetReps = lease.workingRepTarget.takeIf { !lease.isAmrap && it > 0 },
    isWarmup = false,
    isEcho = false,
    isJustLift = lease.isJustLift,
    isBodyweight = lease.isBodyweight,
    isTimed = lease.isTimedCable,
    isAmrap = lease.isAmrap,
    isCableExercise = lease.requiresMachine && !lease.isBodyweight,
    logicalPreRackCommandTemplate = WorkoutParameters(ProgramMode.OldSchool, reps = 0),
)
