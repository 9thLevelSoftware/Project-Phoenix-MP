package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import kotlinx.coroutines.CoroutineScope

fun fakeMachineSafetyCoordinator(
    scope: CoroutineScope,
    store: InMemoryMachineSafetyHazardRepository = InMemoryMachineSafetyHazardRepository(),
    persistMachineArming: Boolean = true,
): MachineSafetyCoordinator = MachineSafetyCoordinator(
    repository = store,
    transport = FakeMachineSafetyTransport(),
    scope = scope,
    nowEpochMs = { 0L },
    persistMachineArming = persistMachineArming,
)

private class FakeMachineSafetyTransport : MachineSafetyTransport {
    // The host graph's FakeBleRepository has no safety transport adapter. Keep a
    // stable non-null identity so ordinary machine configuration can exercise the
    // production persist-before-command boundary instead of failing closed only
    // because the test transport is disconnected.
    override val connectedTrainerAddress: String? = "AA:BB:CC:DD:EE:FF"
    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> = Result.failure(IllegalStateException("not connected"))
    override suspend fun stopWorkout(): Result<Unit> = Result.success(Unit)
}
