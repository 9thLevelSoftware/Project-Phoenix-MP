package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

data class PersonalMvtEntity(
    val exerciseId: String,
    val profileId: String,
    val personalMvtMs: Float,
    val sampleCount: Int,
)

interface PersonalMvtRepository {
    suspend fun get(exerciseId: String, profileId: String): PersonalMvtEntity?
    suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int)
}

class SqlDelightPersonalMvtRepository(private val db: VitruvianDatabase) : PersonalMvtRepository {
    private val queries = db.vitruvianDatabaseQueries

    override suspend fun get(exerciseId: String, profileId: String): PersonalMvtEntity? =
        withContext(Dispatchers.IO) {
            queries.selectExerciseMvt(exerciseId, profileId).executeAsOneOrNull()?.let {
                PersonalMvtEntity(
                    exerciseId = it.exerciseId,
                    profileId = it.profile_id,
                    personalMvtMs = it.personalMvtMs.toFloat(),
                    sampleCount = it.sampleCount.toInt(),
                )
            }
        }

    override suspend fun upsert(
        exerciseId: String,
        profileId: String,
        personalMvtMs: Float,
        sampleCount: Int,
    ): Unit = withContext(Dispatchers.IO) {
        queries.upsertExerciseMvt(
            exerciseId = exerciseId,
            profileId = profileId,
            personalMvtMs = personalMvtMs.toDouble(),
            sampleCount = sampleCount.toLong(),
            updatedAt = currentTimeMillis(),
        )
        Unit
    }
}
