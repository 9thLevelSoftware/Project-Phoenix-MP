package com.devil.phoenixproject.presentation.tv

import kotlinx.coroutines.flow.MutableStateFlow

/** TV-only admission. Shared identity, sync, workout and physical-stop rules remain authoritative. */
internal class TvActivityAdmission {
    private enum class Activity { Idle, AccountMutation, Training }
    private val activity = MutableStateFlow(Activity.Idle)

    fun tryAccountMutation(): Boolean = activity.compareAndSet(Activity.Idle, Activity.AccountMutation)

    fun releaseAccountMutation() {
        activity.compareAndSet(Activity.AccountMutation, Activity.Idle)
    }

    // One controller owns training until confirmed exit; later sets reuse that ownership.
    fun tryTraining(): Boolean = activity.compareAndSet(Activity.Idle, Activity.Training) ||
        activity.value == Activity.Training

    fun releaseTraining() {
        activity.compareAndSet(Activity.Training, Activity.Idle)
    }
}
