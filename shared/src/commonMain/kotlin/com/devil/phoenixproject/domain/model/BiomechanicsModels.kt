package com.devil.phoenixproject.domain.model

/**
 * Velocity zone for Mean Concentric Velocity (MCV) analysis.
 *
 * This classifies MCV for training load analysis (separate from VelocityZone in LedFeedback.kt
 * which maps absolute velocity to LED colors).
 *
 * Thresholds based on VBT research:
 * - EXPLOSIVE: MCV >= 1.0 m/s (power/speed work)
 * - FAST: MCV >= 0.75 m/s (speed-strength)
 * - MODERATE: MCV >= 0.5 m/s (strength-speed)
 * - SLOW: MCV >= 0.25 m/s (strength)
 * - GRIND: MCV < 0.25 m/s (near maximal effort)
 */
enum class BiomechanicsVelocityZone {
    EXPLOSIVE,
    FAST,
    MODERATE,
    SLOW,
    GRIND;

    companion object {
        /**
         * Classify MCV to velocity zone.
         * @param mcvMmPerSec Mean concentric velocity in mm/s
         * @return Corresponding velocity zone
         */
        fun fromMcv(mcvMmPerSec: Float): BiomechanicsVelocityZone = when {
            mcvMmPerSec >= 1000f -> EXPLOSIVE  // >= 1.0 m/s
            mcvMmPerSec >= 750f -> FAST        // >= 0.75 m/s
            mcvMmPerSec >= 500f -> MODERATE    // >= 0.5 m/s
            mcvMmPerSec >= 250f -> SLOW        // >= 0.25 m/s
            else -> GRIND                       // < 0.25 m/s
        }
    }
}

/**
 * Velocity-based training (VBT) result for a single rep.
 *
 * @property meanConcentricVelocityMmS Mean velocity during concentric (lifting) phase in mm/s
 * @property peakVelocityMmS Maximum velocity reached during concentric phase in mm/s
 * @property zone Velocity zone classification for training load analysis
 * @property velocityLossPercent Percent drop from first rep's MCV (null for rep 1)
 * @property estimatedRepsRemaining Predicted reps before failure (null until rep 2+)
 * @property shouldStopSet True when velocity loss exceeds threshold (fatigue management)
 * @property repNumber 1-indexed rep number
 */
data class VelocityResult(
    val meanConcentricVelocityMmS: Float,
    val peakVelocityMmS: Float,
    val zone: BiomechanicsVelocityZone,
    val velocityLossPercent: Float?,
    val estimatedRepsRemaining: Int?,
    val shouldStopSet: Boolean,
    val repNumber: Int
)

/**
 * Strength profile classification based on force curve shape.
 *
 * - ASCENDING: Force increases through ROM (e.g., deadlift lockout)
 * - DESCENDING: Force decreases through ROM (e.g., bench press lockout)
 * - BELL_SHAPED: Force peaks mid-ROM (e.g., bicep curl)
 * - FLAT: Relatively constant force through ROM
 */
enum class StrengthProfile {
    ASCENDING,
    DESCENDING,
    BELL_SHAPED,
    FLAT
}

/**
 * Force curve analysis result for a single rep.
 *
 * Force is normalized to 101 points (0-100% ROM) for comparison across reps
 * and exercises with different ranges of motion.
 *
 * @property normalizedForceN Force values at each percent ROM (101 points)
 * @property normalizedPositionPct Position percentages (0.0, 1.0, ..., 100.0)
 * @property stickingPointPct ROM position of minimum force (null if curve too short)
 * @property strengthProfile Classification of force curve shape
 * @property repNumber 1-indexed rep number
 */
data class ForceCurveResult(
    val normalizedForceN: FloatArray,
    val normalizedPositionPct: FloatArray,
    val stickingPointPct: Float?,
    val strengthProfile: StrengthProfile,
    val repNumber: Int
) {
    // Custom equals/hashCode for FloatArray fields (data classes don't deep-compare arrays)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ForceCurveResult) return false

        return repNumber == other.repNumber &&
            strengthProfile == other.strengthProfile &&
            stickingPointPct == other.stickingPointPct &&
            normalizedForceN.contentEquals(other.normalizedForceN) &&
            normalizedPositionPct.contentEquals(other.normalizedPositionPct)
    }

    override fun hashCode(): Int {
        var result = normalizedForceN.contentHashCode()
        result = 31 * result + normalizedPositionPct.contentHashCode()
        result = 31 * result + (stickingPointPct?.hashCode() ?: 0)
        result = 31 * result + strengthProfile.hashCode()
        result = 31 * result + repNumber
        return result
    }
}

/**
 * Left/right (A/B cable) asymmetry analysis result for a single rep.
 *
 * @property asymmetryPercent Imbalance percentage (0 = perfect, 100 = one-sided)
 * @property dominantSide "A", "B", or "BALANCED" (if < 2% asymmetry)
 * @property avgLoadA Average load on cable A in kg
 * @property avgLoadB Average load on cable B in kg
 * @property repNumber 1-indexed rep number
 */
data class AsymmetryResult(
    val asymmetryPercent: Float,
    val dominantSide: String,
    val avgLoadA: Float,
    val avgLoadB: Float,
    val repNumber: Int
)

/**
 * Combined biomechanics analysis result for a single rep.
 *
 * @property velocity VBT analysis (MCV, peak velocity, zone, fatigue)
 * @property forceCurve Force curve analysis (normalized curve, sticking point, profile)
 * @property asymmetry Left/right balance analysis
 * @property repNumber 1-indexed rep number
 * @property timestamp Rep completion timestamp (ms since epoch)
 */
data class BiomechanicsRepResult(
    val velocity: VelocityResult,
    val forceCurve: ForceCurveResult,
    val asymmetry: AsymmetryResult,
    val repNumber: Int,
    val timestamp: Long
)

/**
 * Aggregated biomechanics statistics for a complete set.
 *
 * @property repResults All per-rep biomechanics results
 * @property avgMcvMmS Average MCV across all reps in mm/s
 * @property peakVelocityMmS Highest peak velocity in the set in mm/s
 * @property totalVelocityLossPercent MCV drop from first to last rep (null if < 2 reps)
 * @property zoneDistribution Count of reps in each velocity zone
 * @property avgAsymmetryPercent Average asymmetry across all reps
 * @property dominantSide Overall dominant side for the set ("A", "B", or "BALANCED")
 * @property strengthProfile Most common strength profile in the set
 * @property avgForceCurve Averaged force curve across reps (null if no valid curves)
 */
data class BiomechanicsSetSummary(
    val repResults: List<BiomechanicsRepResult>,
    val avgMcvMmS: Float,
    val peakVelocityMmS: Float,
    val totalVelocityLossPercent: Float?,
    val zoneDistribution: Map<BiomechanicsVelocityZone, Int>,
    val avgAsymmetryPercent: Float,
    val dominantSide: String,
    val strengthProfile: StrengthProfile,
    val avgForceCurve: ForceCurveResult?
)
