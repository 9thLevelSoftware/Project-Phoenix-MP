package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake VelocityOneRepMaxRepository for testing.
 * Set [latestPassing] to control what [getLatestPassing] returns.
 */
class FakeVelocityOneRepMaxRepository : VelocityOneRepMaxRepository {
    var latestPassing: VelocityOneRepMaxEntity? = null

    override suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity? = latestPassing
    override fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>> = flowOf(emptyList())
    override suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) = Unit
}
