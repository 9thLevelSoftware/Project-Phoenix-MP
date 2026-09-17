package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyHazardRepository
import com.devil.phoenixproject.data.repository.MachineSafetyLoadResult
import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import kotlinx.coroutines.CoroutineScope

fun fakeMachineSafetyCoordinator(scope: CoroutineScope): MachineSafetyCoordinator =
    MachineSafetyCoordinator(FakeMachineSafetyStore(), FakeMachineSafetyTransport(), scope, { 0L })

private class FakeMachineSafetyStore : MachineSafetyHazardRepository {
    private val rows = linkedMapOf<String, MachineSafetyHazardDocument>()

    override suspend fun load(trainerAddress: String): MachineSafetyLoadResult =
        rows[trainerAddress]?.let(MachineSafetyLoadResult::Loaded) ?: MachineSafetyLoadResult.Missing

    override suspend fun loadAll(): List<MachineSafetyLoadResult> = rows.values.map(MachineSafetyLoadResult::Loaded)
    override suspend fun replace(document: MachineSafetyHazardDocument) { rows[document.trainerAddress] = document }
    override suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean =
        rows[trainerAddress]?.generation == generation && rows.remove(trainerAddress) != null
}

private class FakeMachineSafetyTransport : MachineSafetyTransport {
    override val connectedTrainerAddress: String? = null
    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> = Result.failure(IllegalStateException("not connected"))
    override suspend fun stopWorkout(): Result<Unit> = Result.success(Unit)
}
