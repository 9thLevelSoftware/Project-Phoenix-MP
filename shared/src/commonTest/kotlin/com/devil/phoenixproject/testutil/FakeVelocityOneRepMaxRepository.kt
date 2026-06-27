package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake VelocityOneRepMaxRepository for testing.
 * Set [latestPassing] to control what [getLatestPassing] returns; it is only returned
 * when the requested exerciseId/profileId match the configured entity's ids.
 */
class FakeVelocityOneRepMaxRepository : VelocityOneRepMaxRepository {
    var latestPassing: VelocityOneRepMaxEntity? = null
    var allPassing: List<VelocityOneRepMaxEntity> = emptyList()

    override suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity? =
        latestPassing?.takeIf { it.exerciseId == exerciseId && it.profileId == profileId }
    override suspend fun getAllPassing(profileId: String): List<VelocityOneRepMaxEntity> =
        allPassing.filter { it.profileId == profileId }
    override fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>> = flowOf(emptyList())
    // Note: no-op — set allPassing/latestPassing directly for read tests.
    override suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) = Unit
}
