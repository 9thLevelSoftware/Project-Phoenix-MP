package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyHazardRepository
import com.devil.phoenixproject.data.repository.MachineSafetyLoadResult
import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import kotlinx.coroutines.CoroutineScope

fun fakeMachineSafetyCoordinator(
    scope: CoroutineScope,
    store: FakeMachineSafetyStore = FakeMachineSafetyStore(),
    persistMachineArming: Boolean = false,
): MachineSafetyCoordinator = MachineSafetyCoordinator(
    repository = store,
    transport = FakeMachineSafetyTransport(),
    scope = scope,
    nowEpochMs = { 0L },
    persistMachineArming = persistMachineArming,
)

class FakeMachineSafetyStore : MachineSafetyHazardRepository {
    private val rows = linkedMapOf<String, MachineSafetyHazardDocument>()

    override suspend fun load(trainerAddress: String): MachineSafetyLoadResult =
        rows[trainerAddress]?.let(MachineSafetyLoadResult::Loaded) ?: MachineSafetyLoadResult.Missing

    override suspend fun loadAll(): List<MachineSafetyLoadResult> = rows.values.map(MachineSafetyLoadResult::Loaded)
    override suspend fun replace(document: MachineSafetyHazardDocument) { rows[document.trainerAddress] = document }
    override suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean =
        rows[trainerAddress]?.generation == generation && rows.remove(trainerAddress) != null
}

private class FakeMachineSafetyTransport : MachineSafetyTransport {
    // The host graph's FakeBleRepository has no safety transport adapter. Keep a
    // stable non-null identity so ordinary machine configuration can exercise the
    // production persist-before-command boundary instead of failing closed only
    // because the test transport is disconnected.
    override val connectedTrainerAddress: String? = "AA:BB:CC:DD:EE:FF"
    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> = Result.failure(IllegalStateException("not connected"))
    override suspend fun stopWorkout(): Result<Unit> = Result.success(Unit)
}
