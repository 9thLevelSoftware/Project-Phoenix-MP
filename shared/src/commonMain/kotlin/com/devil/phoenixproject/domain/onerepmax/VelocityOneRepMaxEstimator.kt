package com.devil.phoenixproject.domain.onerepmax

import com.devil.phoenixproject.domain.assessment.AssessmentConfig
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.assessment.LoadVelocityPoint
import kotlin.math.roundToInt

/** One completed-set data point fed into velocity-1RM estimation. Load is per-cable; MCV in mm/s. */
data class WorkoutVelocityPoint(
    val loadPerCableKg: Float,
    val mcvMmS: Float,
    val timestampMs: Long,
    val workingReps: Int,
)

/** Result of a velocity-based 1RM estimate. Estimate is per-cable kg. */
data class VelocityOneRepMaxResult(
    val estimatedPerCableKg: Float,
    val mvtUsedMs: Float,
    val r2: Float,
    val distinctLoads: Int,
    val passedQualityGate: Boolean,
)

/**
 * Builds load-velocity points from completed sets and fits a 1RM via the existing
 * [AssessmentEngine]. Pure: no I/O. The caller supplies an already-windowed list of
 * working-set points and the resolved MVT.
 */
class VelocityOneRepMaxEstimator(private val assessmentEngine: AssessmentEngine) {

    fun estimate(points: List<WorkoutVelocityPoint>, mvtMs: Float): VelocityOneRepMaxResult? {
        // Keep only usable points, then dedup by load bucket keeping the most recent.
        val deduped = points
            .filter { it.loadPerCableKg > 0f && it.mcvMmS > 0f && it.workingReps > 0 }
            .groupBy { bucketOf(it.loadPerCableKg) }
            .map { (_, group) -> group.maxByOrNull { it.timestampMs }!! }

        val distinctLoads = deduped.size
        if (distinctLoads < MIN_DISTINCT_LOADS) return null

        val lvPoints = deduped.map {
            LoadVelocityPoint(loadKg = it.loadPerCableKg, meanVelocityMs = it.mcvMmS / 1000f)
        }

        // minSets=MIN_DISTINCT_LOADS matches our gate; oneRmVelocityMs is the resolved MVT.
        val config = AssessmentConfig(minSets = MIN_DISTINCT_LOADS, oneRmVelocityMs = mvtMs)
        val assessment = assessmentEngine.estimateOneRepMax(lvPoints, config) ?: return null

        val passed = distinctLoads >= MIN_DISTINCT_LOADS && assessment.r2 >= R2_PASS_THRESHOLD
        return VelocityOneRepMaxResult(
            estimatedPerCableKg = assessment.estimatedOneRepMaxKg,
            mvtUsedMs = mvtMs,
            r2 = assessment.r2,
            distinctLoads = distinctLoads,
            passedQualityGate = passed,
        )
    }

    private fun bucketOf(loadKg: Float): Int = (loadKg / LOAD_BUCKET_KG).roundToInt()

    companion object {
        const val R2_PASS_THRESHOLD = 0.8f
        const val MIN_DISTINCT_LOADS = 3
        const val LOAD_BUCKET_KG = 0.5f
    }
}
