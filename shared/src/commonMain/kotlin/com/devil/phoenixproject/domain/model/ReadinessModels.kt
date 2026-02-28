package com.devil.phoenixproject.domain.model

/**
 * Traffic-light readiness status derived from ACWR score.
 * GREEN = ready to train normally, YELLOW = train with caution, RED = consider lighter session.
 */
enum class ReadinessStatus { GREEN, YELLOW, RED }

/**
 * Result of ACWR-based readiness computation.
 * InsufficientData when training history is too short or sparse.
 * Ready when enough data exists to compute a meaningful score.
 */
sealed class ReadinessResult {
    object InsufficientData : ReadinessResult()
    data class Ready(
        val score: Int,                // 0-100 readiness score
        val status: ReadinessStatus,   // GREEN/YELLOW/RED
        val acwr: Float,               // Raw ACWR ratio
        val acuteVolumeKg: Float,      // 7-day total volume
        val chronicWeeklyAvgKg: Float  // 28-day weekly average volume
    ) : ReadinessResult()
}
