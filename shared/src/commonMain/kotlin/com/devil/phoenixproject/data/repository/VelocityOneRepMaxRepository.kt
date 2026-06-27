package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class VelocityOneRepMaxEntity(
    val id: Long,
    val exerciseId: String,
    val estimatedPerCableKg: Float,
    val mvtUsedMs: Float,
    val r2: Float,
    val distinctLoads: Int,
    val passedQualityGate: Boolean,
    val computedAt: Long,
    val profileId: String,
)

interface VelocityOneRepMaxRepository {
    suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String)
    suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity?
    suspend fun getAllPassing(profileId: String): List<VelocityOneRepMaxEntity>
    fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>>
}

class SqlDelightVelocityOneRepMaxRepository(private val db: VitruvianDatabase) : VelocityOneRepMaxRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun map(
        id: Long, exerciseId: String, estimatedPerCableKg: Double, mvtUsedMs: Double, r2: Double,
        distinctLoads: Long, passedQualityGate: Long, computedAt: Long, profile_id: String,
        updatedAt: Long?, serverId: String?, deletedAt: Long?,
    ) = VelocityOneRepMaxEntity(
        id = id, exerciseId = exerciseId, estimatedPerCableKg = estimatedPerCableKg.toFloat(),
        mvtUsedMs = mvtUsedMs.toFloat(), r2 = r2.toFloat(), distinctLoads = distinctLoads.toInt(),
        passedQualityGate = passedQualityGate != 0L, computedAt = computedAt, profileId = profile_id,
    )

    override suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) =
        withContext(Dispatchers.IO) {
            queries.insertVelocityOneRepMax(
                exerciseId = exerciseId,
                estimatedPerCableKg = result.estimatedPerCableKg.toDouble(),
                mvtUsedMs = result.mvtUsedMs.toDouble(),
                r2 = result.r2.toDouble(),
                distinctLoads = result.distinctLoads.toLong(),
                passedQualityGate = if (result.passedQualityGate) 1L else 0L,
                computedAt = computedAt,
                profile_id = profileId,
            )
            Unit
        }

    override suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity? =
        withContext(Dispatchers.IO) {
            queries.selectLatestPassingVelocityOneRepMax(exerciseId, profileId, ::map).executeAsOneOrNull()
        }

    override suspend fun getAllPassing(profileId: String): List<VelocityOneRepMaxEntity> =
        withContext(Dispatchers.IO) {
            queries.selectAllPassingVelocityOneRepMaxByProfile(profileId, ::map).executeAsList()
        }

    override fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>> =
        queries.selectVelocityOneRepMaxByExercise(exerciseId, profileId, ::map).asFlow().mapToList(Dispatchers.IO)
}
