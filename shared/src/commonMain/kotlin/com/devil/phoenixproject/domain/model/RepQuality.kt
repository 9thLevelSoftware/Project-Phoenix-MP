package com.devil.phoenixproject.domain.model

/**
 * Quality score for a single rep, composed of four weighted components.
 *
 * Total possible: 100 points (ROM 30 + Velocity 25 + Eccentric Control 25 + Smoothness 20).
 */
data class RepQualityScore(
    val composite: Int,                // 0-100 overall score
    val romScore: Float,               // 0-30 points
    val velocityScore: Float,          // 0-25 points
    val eccentricControlScore: Float,  // 0-25 points
    val smoothnessScore: Float,        // 0-20 points
    val repNumber: Int                 // 1-indexed within set
)

/**
 * Direction of quality change across reps in a set.
 */
enum class QualityTrend {
    IMPROVING,
    STABLE,
    DECLINING
}

/**
 * Aggregated quality statistics for an entire set.
 */
data class SetQualitySummary(
    val averageScore: Int,
    val bestScore: Int,
    val worstScore: Int,
    val bestRepNumber: Int,
    val worstRepNumber: Int,
    val trend: QualityTrend,
    val repScores: List<RepQualityScore>
)
