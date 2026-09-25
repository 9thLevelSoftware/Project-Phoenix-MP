package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.BleRepositoryMachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import kotlinx.coroutines.CoroutineScope

/** Real coordinator bound to the fake BLE's connected trainer address, arming ON like production. */
fun fakeMachineSafetyCoordinator(
    scope: CoroutineScope,
    bleRepository: BleRepository,
    store: InMemoryMachineSafetyHazardRepository = InMemoryMachineSafetyHazardRepository(),
    persistMachineArming: Boolean = true,
): MachineSafetyCoordinator = MachineSafetyCoordinator(
    repository = store,
    transport = BleRepositoryMachineSafetyTransport(bleRepository),
    scope = scope,
    nowEpochMs = { 0L },
    persistMachineArming = persistMachineArming,
)
