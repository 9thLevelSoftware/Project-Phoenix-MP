package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.RepMetricData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Repository for per-rep metric data CRUD operations.
 *
 * IMPORTANT: No subscription tier checks here. Data is captured for ALL users (GATE-04).
 * Feature gating happens at the UI/feature layer via FeatureGate.
 */
interface RepMetricRepository {
    suspend fun saveRepMetrics(sessionId: String, metrics: List<RepMetricData>)
    suspend fun getRepMetrics(sessionId: String): List<RepMetricData>
    suspend fun deleteRepMetrics(sessionId: String)
    suspend fun getRepMetricCount(sessionId: String): Long
}

/**
 * SQLDelight implementation of RepMetricRepository.
 *
 * Handles JSON serialization of FloatArray/LongArray curve data to/from TEXT columns.
 * Arrays are stored as JSON arrays (e.g., "[1.0,2.0,3.0]") in the database.
 */
class SqlDelightRepMetricRepository(
    private val db: VitruvianDatabase
) : RepMetricRepository {

    private val queries = db.vitruvianDatabaseQueries

    override suspend fun saveRepMetrics(sessionId: String, metrics: List<RepMetricData>) {
        withContext(Dispatchers.IO) {
            metrics.forEach { metric ->
                queries.insertRepMetric(
                    sessionId = sessionId,
                    repNumber = metric.repNumber.toLong(),
                    isWarmup = if (metric.isWarmup) 1L else 0L,
                    startTimestamp = metric.startTimestamp,
                    endTimestamp = metric.endTimestamp,
                    durationMs = metric.durationMs,
                    concentricDurationMs = metric.concentricDurationMs,
                    concentricPositions = metric.concentricPositions.toJsonString(),
                    concentricLoadsA = metric.concentricLoadsA.toJsonString(),
                    concentricLoadsB = metric.concentricLoadsB.toJsonString(),
                    concentricVelocities = metric.concentricVelocities.toJsonString(),
                    concentricTimestamps = metric.concentricTimestamps.toJsonString(),
                    eccentricDurationMs = metric.eccentricDurationMs,
                    eccentricPositions = metric.eccentricPositions.toJsonString(),
                    eccentricLoadsA = metric.eccentricLoadsA.toJsonString(),
                    eccentricLoadsB = metric.eccentricLoadsB.toJsonString(),
                    eccentricVelocities = metric.eccentricVelocities.toJsonString(),
                    eccentricTimestamps = metric.eccentricTimestamps.toJsonString(),
                    peakForceA = metric.peakForceA.toDouble(),
                    peakForceB = metric.peakForceB.toDouble(),
                    avgForceConcentricA = metric.avgForceConcentricA.toDouble(),
                    avgForceConcentricB = metric.avgForceConcentricB.toDouble(),
                    avgForceEccentricA = metric.avgForceEccentricA.toDouble(),
                    avgForceEccentricB = metric.avgForceEccentricB.toDouble(),
                    peakVelocity = metric.peakVelocity.toDouble(),
                    avgVelocityConcentric = metric.avgVelocityConcentric.toDouble(),
                    avgVelocityEccentric = metric.avgVelocityEccentric.toDouble(),
                    rangeOfMotionMm = metric.rangeOfMotionMm.toDouble(),
                    peakPowerWatts = metric.peakPowerWatts.toDouble(),
                    avgPowerWatts = metric.avgPowerWatts.toDouble(),
                    updatedAt = null,
                    serverId = null
                )
            }
        }
    }

    override suspend fun getRepMetrics(sessionId: String): List<RepMetricData> {
        return withContext(Dispatchers.IO) {
            queries.selectRepMetricsBySession(sessionId).executeAsList().map { row ->
                RepMetricData(
                    repNumber = row.repNumber.toInt(),
                    isWarmup = row.isWarmup != 0L,
                    startTimestamp = row.startTimestamp,
                    endTimestamp = row.endTimestamp,
                    durationMs = row.durationMs,
                    concentricDurationMs = row.concentricDurationMs,
                    concentricPositions = row.concentricPositions.toFloatArrayFromJson(),
                    concentricLoadsA = row.concentricLoadsA.toFloatArrayFromJson(),
                    concentricLoadsB = row.concentricLoadsB.toFloatArrayFromJson(),
                    concentricVelocities = row.concentricVelocities.toFloatArrayFromJson(),
                    concentricTimestamps = row.concentricTimestamps.toLongArrayFromJson(),
                    eccentricDurationMs = row.eccentricDurationMs,
                    eccentricPositions = row.eccentricPositions.toFloatArrayFromJson(),
                    eccentricLoadsA = row.eccentricLoadsA.toFloatArrayFromJson(),
                    eccentricLoadsB = row.eccentricLoadsB.toFloatArrayFromJson(),
                    eccentricVelocities = row.eccentricVelocities.toFloatArrayFromJson(),
                    eccentricTimestamps = row.eccentricTimestamps.toLongArrayFromJson(),
                    peakForceA = row.peakForceA.toFloat(),
                    peakForceB = row.peakForceB.toFloat(),
                    avgForceConcentricA = row.avgForceConcentricA.toFloat(),
                    avgForceConcentricB = row.avgForceConcentricB.toFloat(),
                    avgForceEccentricA = row.avgForceEccentricA.toFloat(),
                    avgForceEccentricB = row.avgForceEccentricB.toFloat(),
                    peakVelocity = row.peakVelocity.toFloat(),
                    avgVelocityConcentric = row.avgVelocityConcentric.toFloat(),
                    avgVelocityEccentric = row.avgVelocityEccentric.toFloat(),
                    rangeOfMotionMm = row.rangeOfMotionMm.toFloat(),
                    peakPowerWatts = row.peakPowerWatts.toFloat(),
                    avgPowerWatts = row.avgPowerWatts.toFloat()
                )
            }
        }
    }

    override suspend fun deleteRepMetrics(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteRepMetricsBySession(sessionId)
        }
    }

    override suspend fun getRepMetricCount(sessionId: String): Long {
        return withContext(Dispatchers.IO) {
            queries.countRepMetricsBySession(sessionId).executeAsOne()
        }
    }
}

// ==================== JSON Serialization Helpers ====================
// Simple manual JSON array serialization for FloatArray and LongArray.
// No kotlinx.serialization dependency needed for primitive arrays.

/**
 * Convert FloatArray to a JSON array string.
 * Example: [1.0, 2.5, 3.7] -> "[1.0,2.5,3.7]"
 */
internal fun FloatArray.toJsonString(): String {
    if (isEmpty()) return "[]"
    return joinToString(separator = ",", prefix = "[", postfix = "]")
}

/**
 * Convert LongArray to a JSON array string.
 * Example: [100, 200, 300] -> "[100,200,300]"
 */
internal fun LongArray.toJsonString(): String {
    if (isEmpty()) return "[]"
    return joinToString(separator = ",", prefix = "[", postfix = "]")
}

/**
 * Parse a JSON array string back to FloatArray.
 * Example: "[1.0,2.5,3.7]" -> [1.0, 2.5, 3.7]
 */
internal fun String.toFloatArrayFromJson(): FloatArray {
    val trimmed = trim().removeSurrounding("[", "]").trim()
    if (trimmed.isEmpty()) return floatArrayOf()
    return trimmed.split(",").map { it.trim().toFloat() }.toFloatArray()
}

/**
 * Parse a JSON array string back to LongArray.
 * Example: "[100,200,300]" -> [100, 200, 300]
 */
internal fun String.toLongArrayFromJson(): LongArray {
    val trimmed = trim().removeSurrounding("[", "]").trim()
    if (trimmed.isEmpty()) return longArrayOf()
    return trimmed.split(",").map { it.trim().toLong() }.toLongArray()
}
