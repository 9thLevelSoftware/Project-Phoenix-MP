package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.UserPreferences

internal fun shouldEmitRepCountAnnouncement(
    prefs: UserPreferences,
    repNumber: Int,
): Boolean = prefs.audioRepCountEnabled && repNumber in 1..25

internal fun currentProfileTestSoundEvents(prefs: UserPreferences): List<HapticEvent> = listOfNotNull(
    HapticEvent.REP_COMPLETED,
    HapticEvent.WARMUP_COMPLETE,
    HapticEvent.REP_COUNT_ANNOUNCED(5).takeIf { shouldEmitRepCountAnnouncement(prefs, 5) },
    HapticEvent.WORKOUT_COMPLETE,
)
