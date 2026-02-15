package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.domain.model.VelocityResult
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Core biomechanics analysis engine.
 *
 * Processes per-rep MetricSample data and produces:
 * - Velocity-based training (VBT) metrics (MCV, velocity loss, fatigue management)
 * - Force curve analysis (normalized curve, sticking points, strength profile)
 * - Left/right asymmetry analysis (balance, dominant side)
 *
 * Results are exposed via StateFlow for reactive UI updates.
 *
 * NOTE: Computation should be dispatched to Dispatchers.Default by the caller
 * (ActiveSessionEngine) to avoid blocking the main thread.
 *
 * @param velocityLossThresholdPercent Velocity loss percentage to trigger shouldStopSet (default 20%)
 */
class BiomechanicsEngine(
    private val velocityLossThresholdPercent: Float = 20f
) {
    private val _latestRepResult = MutableStateFlow<BiomechanicsRepResult?>(null)

    /**
     * Latest biomechanics result for the most recently completed rep.
     * Null at set start or after reset.
     */
    val latestRepResult: StateFlow<BiomechanicsRepResult?> = _latestRepResult.asStateFlow()

    private val repResults = mutableListOf<BiomechanicsRepResult>()
    private var firstRepMcv: Float? = null

    /**
     * Process a completed rep's metric samples and produce biomechanics results.
     *
     * Called from ActiveSessionEngine after each rep boundary detection.
     * The caller is responsible for dispatching this to Dispatchers.Default.
     *
     * @param repNumber 1-indexed rep number
     * @param concentricMetrics List of WorkoutMetric samples for this rep's concentric phase
     * @param allRepMetrics All metrics for this rep (concentric + eccentric)
     * @param timestamp Rep completion timestamp
     * @return Combined biomechanics result for this rep
     */
    fun processRep(
        repNumber: Int,
        concentricMetrics: List<WorkoutMetric>,
        allRepMetrics: List<WorkoutMetric>,
        timestamp: Long
    ): BiomechanicsRepResult {
        val velocity = computeVelocity(repNumber, concentricMetrics)
        val forceCurve = computeForceCurve(repNumber, concentricMetrics)
        val asymmetry = computeAsymmetry(repNumber, allRepMetrics)

        val result = BiomechanicsRepResult(
            velocity = velocity,
            forceCurve = forceCurve,
            asymmetry = asymmetry,
            repNumber = repNumber,
            timestamp = timestamp
        )

        repResults.add(result)
        _latestRepResult.value = result
        return result
    }

    /**
     * Get aggregated biomechanics statistics for the current set.
     *
     * @return Set summary with averaged metrics, or null if no reps processed
     */
    fun getSetSummary(): BiomechanicsSetSummary? {
        if (repResults.isEmpty()) return null

        val velocities = repResults.map { it.velocity.meanConcentricVelocityMmS }
        val avgMcv = velocities.average().toFloat()
        val peakVelocity = repResults.maxOf { it.velocity.peakVelocityMmS }

        val totalVelocityLoss = if (repResults.size >= 2) {
            val firstMcv = repResults.first().velocity.meanConcentricVelocityMmS
            val lastMcv = repResults.last().velocity.meanConcentricVelocityMmS
            if (firstMcv > 0) ((firstMcv - lastMcv) / firstMcv * 100f) else null
        } else null

        val zoneDistribution = repResults
            .map { it.velocity.zone }
            .groupingBy { it }
            .eachCount()

        val avgAsymmetry = repResults.map { it.asymmetry.asymmetryPercent }.average().toFloat()

        // Determine overall dominant side by summing loads
        val totalLoadA = repResults.sumOf { it.asymmetry.avgLoadA.toDouble() }.toFloat()
        val totalLoadB = repResults.sumOf { it.asymmetry.avgLoadB.toDouble() }.toFloat()
        val dominantSide = when {
            totalLoadA == 0f && totalLoadB == 0f -> "BALANCED"
            kotlin.math.abs(totalLoadA - totalLoadB) / maxOf(totalLoadA, totalLoadB) < 0.02f -> "BALANCED"
            totalLoadA > totalLoadB -> "A"
            else -> "B"
        }

        // Most common strength profile
        val profileCounts = repResults
            .map { it.forceCurve.strengthProfile }
            .groupingBy { it }
            .eachCount()
        val strengthProfile = profileCounts.maxByOrNull { it.value }?.key ?: StrengthProfile.FLAT

        return BiomechanicsSetSummary(
            repResults = repResults.toList(),
            avgMcvMmS = avgMcv,
            peakVelocityMmS = peakVelocity,
            totalVelocityLossPercent = totalVelocityLoss,
            zoneDistribution = zoneDistribution,
            avgAsymmetryPercent = avgAsymmetry,
            dominantSide = dominantSide,
            strengthProfile = strengthProfile,
            avgForceCurve = null // TODO: Implement averaged force curve in Plan 03
        )
    }

    /**
     * Reset engine state for a new set.
     *
     * Called at set completion or workout reset.
     */
    fun reset() {
        repResults.clear()
        firstRepMcv = null
        _latestRepResult.value = null
    }

    // =========================================================================
    // Stub computation methods - implemented in Plans 02-04
    // Marked as internal so they can be replaced/tested within the same module
    // =========================================================================

    /**
     * Compute velocity-based training metrics for a rep.
     *
     * Plan 02 will implement:
     * - Mean concentric velocity calculation
     * - Peak velocity detection
     * - Velocity loss tracking
     * - Estimated reps remaining (fatigue prediction)
     *
     * @param repNumber 1-indexed rep number
     * @param concentricMetrics Metrics from concentric (lifting) phase only
     * @return VBT result with zone classification and fatigue indicators
     */
    internal fun computeVelocity(repNumber: Int, concentricMetrics: List<WorkoutMetric>): VelocityResult {
        // Stub implementation - returns default values
        // Plan 02 will implement real MCV calculation
        return VelocityResult(
            meanConcentricVelocityMmS = 0f,
            peakVelocityMmS = 0f,
            zone = BiomechanicsVelocityZone.GRIND,
            velocityLossPercent = null,
            estimatedRepsRemaining = null,
            shouldStopSet = false,
            repNumber = repNumber
        )
    }

    /**
     * Compute force curve analysis for a rep.
     *
     * Plan 03 will implement:
     * - Position-to-force mapping
     * - Normalization to 101 points (0-100% ROM)
     * - Sticking point detection
     * - Strength profile classification
     *
     * @param repNumber 1-indexed rep number
     * @param concentricMetrics Metrics from concentric (lifting) phase only
     * @return Force curve result with normalized curve and analysis
     */
    internal fun computeForceCurve(repNumber: Int, concentricMetrics: List<WorkoutMetric>): ForceCurveResult {
        // Stub implementation - returns empty arrays
        // Plan 03 will implement real force curve normalization
        return ForceCurveResult(
            normalizedForceN = FloatArray(0),
            normalizedPositionPct = FloatArray(0),
            stickingPointPct = null,
            strengthProfile = StrengthProfile.FLAT,
            repNumber = repNumber
        )
    }

    /**
     * Compute left/right asymmetry analysis for a rep.
     *
     * Plan 04 will implement:
     * - Average load calculation per cable
     * - Asymmetry percentage calculation
     * - Dominant side determination
     *
     * @param repNumber 1-indexed rep number
     * @param allRepMetrics All metrics for the rep (both phases)
     * @return Asymmetry result with balance analysis
     */
    internal fun computeAsymmetry(repNumber: Int, allRepMetrics: List<WorkoutMetric>): AsymmetryResult {
        // Stub implementation - returns balanced defaults
        // Plan 04 will implement real asymmetry calculation
        return AsymmetryResult(
            asymmetryPercent = 0f,
            dominantSide = "BALANCED",
            avgLoadA = 0f,
            avgLoadB = 0f,
            repNumber = repNumber
        )
    }
}
