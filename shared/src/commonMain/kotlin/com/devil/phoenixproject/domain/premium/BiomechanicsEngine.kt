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
     * Implements VBT-01 through VBT-05:
     * - Mean concentric velocity calculation (MCV)
     * - Peak velocity detection
     * - Zone classification (EXPLOSIVE/FAST/MODERATE/SLOW/GRIND)
     * - Velocity loss tracking relative to first rep
     * - Estimated reps remaining (fatigue prediction)
     * - Auto-stop recommendation when loss exceeds threshold
     *
     * @param repNumber 1-indexed rep number
     * @param concentricMetrics Metrics from concentric (lifting) phase only
     * @return VBT result with zone classification and fatigue indicators
     */
    internal fun computeVelocity(repNumber: Int, concentricMetrics: List<WorkoutMetric>): VelocityResult {
        // VBT-01: Calculate Mean Concentric Velocity (MCV)
        // For each sample, take max(abs(velocityA), abs(velocityB)) since cables move together
        val sampleVelocities = concentricMetrics.map { metric ->
            maxOf(
                kotlin.math.abs(metric.velocityA.toFloat()),
                kotlin.math.abs(metric.velocityB.toFloat())
            )
        }

        val mcv = if (sampleVelocities.isEmpty()) 0f else sampleVelocities.average().toFloat()

        // Peak velocity: highest sample velocity in the rep
        val peakVelocity = sampleVelocities.maxOrNull() ?: 0f

        // VBT-02: Zone classification
        val zone = BiomechanicsVelocityZone.fromMcv(mcv)

        // VBT-03: Velocity loss tracking
        // First rep establishes baseline
        if (firstRepMcv == null) {
            firstRepMcv = mcv
        }

        // For rep 1: velocity loss is null
        // For rep 2+: calculate loss relative to first rep
        val velocityLossPercent: Float? = if (repNumber == 1) {
            null
        } else {
            firstRepMcv?.let { baseline ->
                if (baseline > 0f) {
                    // Calculate loss percentage, clamp to 0-100 range
                    // If current rep is faster than first rep, loss is 0
                    ((baseline - mcv) / baseline * 100f).coerceIn(0f, 100f)
                } else {
                    null
                }
            }
        }

        // VBT-04: Rep projection
        // Estimate remaining reps based on velocity decay rate
        val estimatedRepsRemaining: Int? = calculateEstimatedRepsRemaining(
            repNumber = repNumber,
            currentLossPercent = velocityLossPercent
        )

        // VBT-05: Auto-stop recommendation
        // Trigger when velocity loss reaches or exceeds threshold
        val shouldStopSet = velocityLossPercent != null &&
            velocityLossPercent >= velocityLossThresholdPercent

        return VelocityResult(
            meanConcentricVelocityMmS = mcv,
            peakVelocityMmS = peakVelocity,
            zone = zone,
            velocityLossPercent = velocityLossPercent,
            estimatedRepsRemaining = estimatedRepsRemaining,
            shouldStopSet = shouldStopSet,
            repNumber = repNumber
        )
    }

    /**
     * Calculate estimated reps remaining until velocity loss threshold is reached.
     *
     * Uses linear projection based on average velocity loss per rep.
     *
     * @param repNumber Current rep number (1-indexed)
     * @param currentLossPercent Current velocity loss percentage (null for rep 1)
     * @return Estimated reps remaining (0-99), or null if projection is not valid
     */
    private fun calculateEstimatedRepsRemaining(
        repNumber: Int,
        currentLossPercent: Float?
    ): Int? {
        // Can't project from rep 1 (no decay data yet)
        if (repNumber < 2 || currentLossPercent == null) return null

        // If velocity is increasing (negative loss) or zero loss, projection is nonsensical
        if (currentLossPercent <= 0f) return null

        // Calculate average loss per rep
        // currentLossPercent is the total loss from rep 1 to current rep
        // We've completed (repNumber - 1) reps since rep 1
        val avgLossPerRep = currentLossPercent / (repNumber - 1)

        // Project how many more reps until we hit the threshold
        val remainingLossToThreshold = velocityLossThresholdPercent - currentLossPercent

        // If we're already at or past threshold, no reps remaining
        if (remainingLossToThreshold <= 0f) return 0

        // Calculate remaining reps (round down)
        val repsRemaining = (remainingLossToThreshold / avgLossPerRep).toInt()

        // Clamp to 0-99 range
        return repsRemaining.coerceIn(0, 99)
    }

    /**
     * Compute force curve analysis for a rep.
     *
     * Implements FORCE-01 through FORCE-04:
     * - FORCE-01: Force-position curve construction from load and position data
     * - FORCE-02: ROM normalization to 101 equally-spaced points (0-100%)
     * - FORCE-03: Sticking point detection (minimum force position, excluding edges)
     * - FORCE-04: Strength profile classification (Ascending, Descending, Bell-shaped, Flat)
     *
     * @param repNumber 1-indexed rep number
     * @param concentricMetrics Metrics from concentric (lifting) phase only
     * @return Force curve result with normalized curve and analysis
     */
    internal fun computeForceCurve(repNumber: Int, concentricMetrics: List<WorkoutMetric>): ForceCurveResult {
        // FORCE-01: Minimum 3 samples required for meaningful curve
        if (concentricMetrics.size < 3) {
            return ForceCurveResult(
                normalizedForceN = FloatArray(0),
                normalizedPositionPct = FloatArray(0),
                stickingPointPct = null,
                strengthProfile = StrengthProfile.FLAT,
                repNumber = repNumber
            )
        }

        // Extract raw force-position pairs
        // Force = loadA + loadB, Position = max(positionA, positionB)
        val rawPairs = concentricMetrics.map { metric ->
            val position = maxOf(metric.positionA, metric.positionB)
            val force = metric.loadA + metric.loadB
            Pair(position, force)
        }.sortedBy { it.first } // Sort by position ascending (concentric direction)

        // Calculate ROM range
        val minPos = rawPairs.first().first
        val maxPos = rawPairs.last().first
        val romRange = maxPos - minPos

        // Guard: Need at least 1mm ROM for meaningful normalization
        if (romRange < 1f) {
            return ForceCurveResult(
                normalizedForceN = FloatArray(0),
                normalizedPositionPct = FloatArray(0),
                stickingPointPct = null,
                strengthProfile = StrengthProfile.FLAT,
                repNumber = repNumber
            )
        }

        // FORCE-02: Normalize to 101 points (0%, 1%, ..., 100%)
        val normalizedForce = FloatArray(101)
        val normalizedPosition = FloatArray(101) { it.toFloat() }

        for (pct in 0..100) {
            val targetPos = minPos + (romRange * pct / 100f)
            normalizedForce[pct] = interpolateForce(rawPairs, targetPos)
        }

        // FORCE-03: Sticking point detection
        // Find minimum force in range [5..95] (exclude edge noise)
        val stickingPointPct = findStickingPoint(normalizedForce)

        // FORCE-04: Strength profile classification
        val strengthProfile = classifyStrengthProfile(normalizedForce)

        return ForceCurveResult(
            normalizedForceN = normalizedForce,
            normalizedPositionPct = normalizedPosition,
            stickingPointPct = stickingPointPct,
            strengthProfile = strengthProfile,
            repNumber = repNumber
        )
    }

    /**
     * Linearly interpolate force at a target position using raw force-position pairs.
     *
     * @param rawPairs Sorted list of (position, force) pairs
     * @param targetPosition Position to interpolate force at
     * @return Interpolated force value
     */
    private fun interpolateForce(rawPairs: List<Pair<Float, Float>>, targetPosition: Float): Float {
        // Handle edge cases
        if (rawPairs.isEmpty()) return 0f
        if (rawPairs.size == 1) return rawPairs[0].second

        // Find bracketing points
        val lowerIndex = rawPairs.indexOfLast { it.first <= targetPosition }
        val upperIndex = rawPairs.indexOfFirst { it.first >= targetPosition }

        // If target is before first point, use first point
        if (lowerIndex == -1) return rawPairs[0].second
        // If target is after last point, use last point
        if (upperIndex == -1) return rawPairs.last().second

        // If same index, we're at an exact point
        if (lowerIndex == upperIndex) return rawPairs[lowerIndex].second

        // Linear interpolation
        val lowerPair = rawPairs[lowerIndex]
        val upperPair = rawPairs[upperIndex]
        val posDiff = upperPair.first - lowerPair.first

        // Avoid division by zero
        if (posDiff == 0f) return lowerPair.second

        val t = (targetPosition - lowerPair.first) / posDiff
        return lowerPair.second + t * (upperPair.second - lowerPair.second)
    }

    /**
     * Find sticking point (minimum force position) in normalized curve.
     *
     * Excludes first 5% and last 5% of ROM to avoid transition noise.
     *
     * @param normalizedForce 101-point normalized force array
     * @return Position percentage of minimum force (5-95), or null if curve too short
     */
    private fun findStickingPoint(normalizedForce: FloatArray): Float? {
        if (normalizedForce.size < 101) return null

        // Search range: 5% to 95% (indices 5 to 95 inclusive)
        var minForce = Float.MAX_VALUE
        var minIndex = -1

        for (i in 5..95) {
            if (normalizedForce[i] < minForce) {
                minForce = normalizedForce[i]
                minIndex = i
            }
        }

        return if (minIndex >= 0) minIndex.toFloat() else null
    }

    /**
     * Classify strength profile based on force distribution across ROM thirds.
     *
     * Divides curve into 3 equal segments (0-33%, 34-66%, 67-100%) and compares
     * average force in each segment using 15% threshold.
     *
     * @param normalizedForce 101-point normalized force array
     * @return Strength profile classification
     */
    private fun classifyStrengthProfile(normalizedForce: FloatArray): StrengthProfile {
        if (normalizedForce.size < 101) return StrengthProfile.FLAT

        // Split into thirds
        // Bottom: 0-33 (34 points, indices 0-33)
        // Middle: 34-66 (33 points, indices 34-66)
        // Top: 67-100 (34 points, indices 67-100)
        val bottomAvg = normalizedForce.slice(0..33).average().toFloat()
        val middleAvg = normalizedForce.slice(34..66).average().toFloat()
        val topAvg = normalizedForce.slice(67..100).average().toFloat()

        // 15% threshold for "significantly higher"
        val threshold = 1.15f

        return when {
            // ASCENDING: top > bottom * 1.15 AND top > middle
            topAvg > bottomAvg * threshold && topAvg > middleAvg -> StrengthProfile.ASCENDING

            // DESCENDING: bottom > top * 1.15 AND bottom > middle
            bottomAvg > topAvg * threshold && bottomAvg > middleAvg -> StrengthProfile.DESCENDING

            // BELL_SHAPED: middle > bottom * 1.15 AND middle > top * 1.15
            middleAvg > bottomAvg * threshold && middleAvg > topAvg * threshold -> StrengthProfile.BELL_SHAPED

            // FLAT: none of the above
            else -> StrengthProfile.FLAT
        }
    }

    /**
     * Compute left/right asymmetry analysis for a rep.
     *
     * ASYM-01: Calculates asymmetry percentage from loadA/loadB averages.
     * ASYM-02: Identifies dominant side (A, B, or BALANCED if < 2%).
     *
     * Formula: asymmetryPercent = abs(avgLoadA - avgLoadB) / max(avgLoadA, avgLoadB) * 100
     *
     * @param repNumber 1-indexed rep number
     * @param allRepMetrics All metrics for the rep (both phases)
     * @return Asymmetry result with balance analysis
     */
    internal fun computeAsymmetry(repNumber: Int, allRepMetrics: List<WorkoutMetric>): AsymmetryResult {
        // Empty input: return balanced defaults
        if (allRepMetrics.isEmpty()) {
            return AsymmetryResult(
                asymmetryPercent = 0f,
                dominantSide = "BALANCED",
                avgLoadA = 0f,
                avgLoadB = 0f,
                repNumber = repNumber
            )
        }

        // Calculate average load per cable across all samples
        val avgLoadA = allRepMetrics.map { it.loadA }.average().toFloat()
        val avgLoadB = allRepMetrics.map { it.loadB }.average().toFloat()
        val maxLoad = maxOf(avgLoadA, avgLoadB)

        // Zero load on both cables: return balanced
        if (maxLoad <= 0f) {
            return AsymmetryResult(
                asymmetryPercent = 0f,
                dominantSide = "BALANCED",
                avgLoadA = avgLoadA,
                avgLoadB = avgLoadB,
                repNumber = repNumber
            )
        }

        // ASYM-01: Calculate asymmetry percentage, clamped to 0-100%
        val asymmetry = (kotlin.math.abs(avgLoadA - avgLoadB) / maxLoad * 100f).coerceIn(0f, 100f)

        // ASYM-02: Determine dominant side
        // Below 2% is considered balanced (measurement noise threshold)
        val dominantSide = when {
            asymmetry < 2f -> "BALANCED"
            avgLoadA > avgLoadB -> "A"
            avgLoadB > avgLoadA -> "B"
            else -> "BALANCED"  // Exactly equal
        }

        return AsymmetryResult(
            asymmetryPercent = asymmetry,
            dominantSide = dominantSide,
            avgLoadA = avgLoadA,
            avgLoadB = avgLoadB,
            repNumber = repNumber
        )
    }
}
