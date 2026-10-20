package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport

/**
 * Scriptable [MachineSafetyTransport]: [connectedTrainerAddress] is the live link, a matching
 * connect request always succeeds and binds it, and [stopBlock] decides what RESET returns.
 */
class FakeMachineSafetyTransport(override var connectedTrainerAddress: String?) : MachineSafetyTransport {
    var stopCalls = 0
    var stopBlock: suspend () -> Result<Unit> = { Result.success(Unit) }

    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
        connectedTrainerAddress = trainerAddress
        return Result.success(Unit)
    }

    override suspend fun stopWorkout(): Result<Unit> {
        stopCalls++
        return stopBlock()
    }
}
