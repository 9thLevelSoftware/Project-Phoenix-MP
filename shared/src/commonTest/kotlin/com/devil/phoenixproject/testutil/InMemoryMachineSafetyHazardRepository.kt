package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyHazardRepository
import com.devil.phoenixproject.data.repository.MachineSafetyLoadResult

/** In-memory durable safety hazard store, one row per trainer address (mirrors the SQL table's key). */
class InMemoryMachineSafetyHazardRepository : MachineSafetyHazardRepository {
    val rows = linkedMapOf<String, MachineSafetyHazardDocument>()

    /** Optional suspension point before a conditional delete, to model a slow durable store. */
    var beforeDelete: (suspend () -> Unit)? = null

    var deleteFailure: Throwable? = null

    override suspend fun load(trainerAddress: String): MachineSafetyLoadResult =
        rows[trainerAddress]?.let(MachineSafetyLoadResult::Loaded) ?: MachineSafetyLoadResult.Missing

    override suspend fun loadAll(): List<MachineSafetyLoadResult> = rows.values.map(MachineSafetyLoadResult::Loaded)

    override suspend fun replace(document: MachineSafetyHazardDocument) {
        rows[document.trainerAddress] = document
    }

    override suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean {
        beforeDelete?.invoke()
        deleteFailure?.let { throw it }
        return rows[trainerAddress]?.generation == generation && rows.remove(trainerAddress) != null
    }
}
