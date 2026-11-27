package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutParameters

internal fun isFinalWorkingRep(
    params: WorkoutParameters,
    repNumber: Int,
): Boolean = !params.isJustLift && !params.isAMRAP && params.reps > 0 && repNumber >= params.reps

internal fun shouldEmitRepCountAnnouncement(
    prefs: UserPreferences,
    repNumber: Int,
): Boolean = prefs.audioRepCountEnabled && repNumber in 1..25

internal fun workingRepCueEvents(
    prefs: UserPreferences,
    params: WorkoutParameters,
    repNumber: Int,
): List<HapticEvent> {
    val isFinalRep = isFinalWorkingRep(params, repNumber)
    val announced = shouldEmitRepCountAnnouncement(prefs, repNumber)
    return buildList {
        if (announced) add(HapticEvent.REP_COUNT_ANNOUNCED(repNumber))
        if (isFinalRep && prefs.repSoundEnabled) {
            add(HapticEvent.FINAL_REP)
        } else if (!announced && prefs.repSoundEnabled) {
            add(HapticEvent.REP_COMPLETED)
        }
    }
}

internal fun warmupRepCueEvent(prefs: UserPreferences): HapticEvent? =
    if (prefs.repSoundEnabled) HapticEvent.REP_COMPLETED else null

internal fun currentProfileTestSoundEvents(prefs: UserPreferences): List<HapticEvent> = listOfNotNull(
    HapticEvent.REP_COMPLETED,
    HapticEvent.WARMUP_COMPLETE,
    HapticEvent.REP_COUNT_ANNOUNCED(5).takeIf { shouldEmitRepCountAnnouncement(prefs, 5) },
    HapticEvent.WORKOUT_COMPLETE,
)
