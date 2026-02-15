package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.detection.CableUsage
import com.devil.phoenixproject.domain.detection.ExerciseSignature
import com.devil.phoenixproject.domain.detection.VelocityShape
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of [ExerciseSignatureRepository].
 *
 * Maps between domain [ExerciseSignature] and the SQLDelight ExerciseSignature table.
 * Enum values (VelocityShape, CableUsage) are stored as strings.
 */
class SqlDelightExerciseSignatureRepository(
    db: VitruvianDatabase
) : ExerciseSignatureRepository {

    private val queries = db.vitruvianDatabaseQueries
    private val log = Logger.withTag("ExerciseSignatureRepo")

    override suspend fun getSignaturesByExercise(exerciseId: String): List<ExerciseSignature> {
        return withContext(Dispatchers.IO) {
            queries.selectSignaturesByExercise(exerciseId)
                .executeAsList()
                .map { row -> mapToSignature(row) }
        }
    }

    override suspend fun getAllSignaturesAsMap(): Map<String, ExerciseSignature> {
        return withContext(Dispatchers.IO) {
            queries.selectAllSignatures()
                .executeAsList()
                .groupBy { it.exerciseId }
                .mapValues { (_, signatures) ->
                    // Take the highest-confidence signature per exercise
                    // (already ordered by updatedAt DESC, but we want max confidence)
                    signatures
                        .map { mapToSignature(it) }
                        .maxByOrNull { it.confidence }
                        ?: mapToSignature(signatures.first())
                }
        }
    }

    override suspend fun saveSignature(exerciseId: String, signature: ExerciseSignature) {
        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()
            queries.insertExerciseSignature(
                exerciseId = exerciseId,
                romMm = signature.romMm.toDouble(),
                durationMs = signature.durationMs,
                symmetryRatio = signature.symmetryRatio.toDouble(),
                velocityProfile = signature.velocityProfile.name,
                cableConfig = signature.cableConfig.name,
                sampleCount = signature.sampleCount.toLong(),
                confidence = signature.confidence.toDouble(),
                createdAt = now,
                updatedAt = now
            )
            log.d { "Saved signature for exercise $exerciseId (ROM=${signature.romMm}mm, conf=${signature.confidence})" }
        }
    }

    override suspend fun updateSignature(id: Long, signature: ExerciseSignature) {
        withContext(Dispatchers.IO) {
            queries.updateExerciseSignature(
                romMm = signature.romMm.toDouble(),
                durationMs = signature.durationMs,
                symmetryRatio = signature.symmetryRatio.toDouble(),
                velocityProfile = signature.velocityProfile.name,
                cableConfig = signature.cableConfig.name,
                sampleCount = signature.sampleCount.toLong(),
                confidence = signature.confidence.toDouble(),
                updatedAt = currentTimeMillis(),
                id = id
            )
            log.d { "Updated signature $id (ROM=${signature.romMm}mm, samples=${signature.sampleCount})" }
        }
    }

    override suspend fun deleteSignaturesByExercise(exerciseId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteSignaturesByExercise(exerciseId)
            log.d { "Deleted all signatures for exercise $exerciseId" }
        }
    }

    /**
     * Map SQLDelight row to domain ExerciseSignature.
     */
    private fun mapToSignature(
        row: com.devil.phoenixproject.database.ExerciseSignature
    ): ExerciseSignature {
        return ExerciseSignature(
            romMm = row.romMm.toFloat(),
            durationMs = row.durationMs,
            symmetryRatio = row.symmetryRatio.toFloat(),
            velocityProfile = VelocityShape.valueOf(row.velocityProfile),
            cableConfig = CableUsage.valueOf(row.cableConfig),
            sampleCount = row.sampleCount.toInt(),
            confidence = row.confidence.toFloat()
        )
    }
}
