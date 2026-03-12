package com.devil.phoenixproject.framework.core

import com.devil.phoenixproject.domain.model.WorkoutParameters

/** Minimal state contract for connection-loss alert logic. */
interface WorkoutStateProvider {
    val isWorkoutActiveForConnectionAlert: Boolean
}

/** Delegate for routine flows that need workout lifecycle/BLE control. */
interface WorkoutLifecycleDelegate {
    fun resetRepCounter()
    fun startWorkout(skipCountdown: Boolean = false)
    suspend fun sendStopCommand()
    suspend fun stopMachineWorkout()
    fun updateWorkoutParameters(params: WorkoutParameters)
}
