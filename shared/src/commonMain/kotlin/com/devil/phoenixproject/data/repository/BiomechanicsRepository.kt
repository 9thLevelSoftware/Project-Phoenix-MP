package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Repository for per-rep biomechanics data CRUD operations.
 *
 * IMPORTANT: No subscription tier checks here. Data is captured for ALL users (GATE-04).
 * Feature gating happens at the UI/feature layer via FeatureGate.
 */
interface BiomechanicsRepository {
    suspend fun saveRepBiomechanics(sessionId: String, results: List<BiomechanicsRepResult>)
    suspend fun getRepBiomechanics(sessionId: String): List<BiomechanicsRepResult>
    suspend fun deleteRepBiomechanics(sessionId: String)
}

/**
 * SQLDelight implementation of BiomechanicsRepository.
 *
 * Handles JSON serialization of FloatArray curve data to/from TEXT columns.
 * Reuses toJsonString()/toFloatArrayFromJson() from RepMetricRepository.
 */
class SqlDelightBiomechanicsRepository(
    private val db: VitruvianDatabase
) : BiomechanicsRepository {

    private val queries = db.vitruvianDatabaseQueries

    override suspend fun saveRepBiomechanics(sessionId: String, results: List<BiomechanicsRepResult>) {
        withContext(Dispatchers.IO) {
            results.forEach { result ->
                queries.insertRepBiomechanics(
                    sessionId = sessionId,
                    repNumber = result.repNumber.toLong(),
                    // VBT metrics
                    mcvMmS = result.velocity.meanConcentricVelocityMmS.toDouble(),
                    peakVelocityMmS = result.velocity.peakVelocityMmS.toDouble(),
                    velocityZone = result.velocity.zone.name,
                    velocityLossPercent = result.velocity.velocityLossPercent?.toDouble(),
                    estimatedRepsRemaining = result.velocity.estimatedRepsRemaining?.toLong(),
                    shouldStopSet = if (result.velocity.shouldStopSet) 1L else 0L,
                    // Force curve
                    normalizedForceN = result.forceCurve.normalizedForceN.toJsonString(),
                    normalizedPositionPct = result.forceCurve.normalizedPositionPct.toJsonString(),
                    stickingPointPct = result.forceCurve.stickingPointPct?.toDouble(),
                    strengthProfile = result.forceCurve.strengthProfile.name,
                    // Asymmetry
                    asymmetryPercent = result.asymmetry.asymmetryPercent.toDouble(),
                    dominantSide = result.asymmetry.dominantSide,
                    avgLoadA = result.asymmetry.avgLoadA.toDouble(),
                    avgLoadB = result.asymmetry.avgLoadB.toDouble(),
                    // Metadata
                    timestamp = result.timestamp
                )
            }
        }
    }

    override suspend fun getRepBiomechanics(sessionId: String): List<BiomechanicsRepResult> {
        return withContext(Dispatchers.IO) {
            queries.selectRepBiomechanicsBySession(sessionId).executeAsList().map { row ->
                val velocityZone = try {
                    BiomechanicsVelocityZone.valueOf(row.velocityZone)
                } catch (_: IllegalArgumentException) {
                    BiomechanicsVelocityZone.MODERATE
                }

                val strengthProfile = try {
                    StrengthProfile.valueOf(row.strengthProfile)
                } catch (_: IllegalArgumentException) {
                    StrengthProfile.FLAT
                }

                BiomechanicsRepResult(
                    velocity = VelocityResult(
                        meanConcentricVelocityMmS = row.mcvMmS.toFloat(),
                        peakVelocityMmS = row.peakVelocityMmS.toFloat(),
                        zone = velocityZone,
                        velocityLossPercent = row.velocityLossPercent?.toFloat(),
                        estimatedRepsRemaining = row.estimatedRepsRemaining?.toInt(),
                        shouldStopSet = row.shouldStopSet != 0L,
                        repNumber = row.repNumber.toInt()
                    ),
                    forceCurve = ForceCurveResult(
                        normalizedForceN = row.normalizedForceN.toFloatArrayFromJson(),
                        normalizedPositionPct = row.normalizedPositionPct.toFloatArrayFromJson(),
                        stickingPointPct = row.stickingPointPct?.toFloat(),
                        strengthProfile = strengthProfile,
                        repNumber = row.repNumber.toInt()
                    ),
                    asymmetry = AsymmetryResult(
                        asymmetryPercent = row.asymmetryPercent.toFloat(),
                        dominantSide = row.dominantSide,
                        avgLoadA = row.avgLoadA.toFloat(),
                        avgLoadB = row.avgLoadB.toFloat(),
                        repNumber = row.repNumber.toInt()
                    ),
                    repNumber = row.repNumber.toInt(),
                    timestamp = row.timestamp
                )
            }
        }
    }

    override suspend fun deleteRepBiomechanics(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteRepBiomechanicsBySession(sessionId)
        }
    }
}
