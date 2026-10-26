package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A runtime-only view for TV workout composition, NOT a replacement in the sync container.
 * Cloud/phone preferences stay intact. Every incoming profile publication (including sync)
 * is manual-start-only before SettingsManager or ActiveSessionEngine can observe it:
 * summaryCountdownSeconds = 0, autoStartRoutine = false, and motionStartEnabled = false.
 * These runtime values do not replace the original stored/cloud controls.
 *
 * Both [updateWorkout] and [mutateWorkout] ignore submitted changes to those three protected
 * fields without reporting an error for the policy override. The [mutateWorkout] transform
 * receives masked current values, not the stored controls; other fields it derives from those
 * inputs are therefore derived from the TV/manual values. Other accepted changes persist through
 * the source repository, preserving the latest stored controls inside its identity-checked
 * mutation. Normal source validation, failure, and cancellation behavior still applies.
 */
class TvManualStartProfileRepository(
    private val source: UserProfileRepository,
    scope: CoroutineScope,
) : UserProfileRepository by source {
    override val activeProfileContext = source.activeProfileContext
        .map(::manualStarts)
        .stateIn(scope, SharingStarted.Eagerly, manualStarts(source.activeProfileContext.value))

    override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences) =
        mutateWorkout(profileId) { value }

    override suspend fun mutateWorkout(
        profileId: String,
        transform: (WorkoutPreferences) -> WorkoutPreferences,
    ) = source.mutateWorkout(profileId) { current ->
        // Preserve the latest stored controls inside the source's identity-checked mutation,
        // never from the asynchronously published TV view or a pre-write source snapshot.
        transform(manualStarts(current)).copy(
            summaryCountdownSeconds = current.summaryCountdownSeconds,
            autoStartRoutine = current.autoStartRoutine,
            motionStartEnabled = current.motionStartEnabled,
        )
    }

    private fun manualStarts(workout: WorkoutPreferences): WorkoutPreferences = workout.copy(
        summaryCountdownSeconds = 0,
        autoStartRoutine = false,
        motionStartEnabled = false,
    )

    private fun manualStarts(context: ActiveProfileContext): ActiveProfileContext = when (context) {
        is ActiveProfileContext.Switching -> context
        is ActiveProfileContext.Ready -> context.copy(preferences = context.preferences.copy(
            workout = context.preferences.workout.copy(value = manualStarts(context.preferences.workout.value)),
        ))
    }
}
