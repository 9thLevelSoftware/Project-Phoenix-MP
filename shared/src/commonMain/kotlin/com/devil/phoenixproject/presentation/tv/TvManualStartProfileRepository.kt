package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A runtime-only view for TV workout composition, NOT a replacement in the sync container.
 * Cloud/phone preferences stay intact. Every incoming profile publication (including sync)
 * is manual-start-only before SettingsManager or ActiveSessionEngine can observe it.
 */
class TvManualStartProfileRepository(
    private val source: UserProfileRepository,
    scope: CoroutineScope,
) : UserProfileRepository by source {
    override val activeProfileContext = source.activeProfileContext
        .map(::manualStarts)
        .stateIn(scope, SharingStarted.Eagerly, manualStarts(source.activeProfileContext.value))

    private fun manualStarts(context: ActiveProfileContext): ActiveProfileContext = when (context) {
        is ActiveProfileContext.Switching -> context
        is ActiveProfileContext.Ready -> context.copy(preferences = context.preferences.copy(
            workout = context.preferences.workout.copy(value = context.preferences.workout.value.copy(
                summaryCountdownSeconds = 0,
                autoStartRoutine = false,
                motionStartEnabled = false,
            )),
        ))
    }
}
