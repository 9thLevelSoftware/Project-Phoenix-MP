package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.ReadinessResult
import com.devil.phoenixproject.domain.model.ReadinessStatus
import com.devil.phoenixproject.domain.model.SessionSummary

/**
 * Pure ACWR-based readiness computation engine.
 * Stateless object with pure functions -- no DB or DI dependencies.
 * All time-dependent functions accept nowMs parameter for testability.
 *
 * Follows the SmartSuggestionsEngine pattern exactly:
 * - Volume formula: weightPerCableKg * 2 * workingReps
 * - Acute window: last 7 days
 * - Chronic window: last 28 days (divided by 4 for weekly average)
 * - ACWR = acute / chronic weekly average
 */
object ReadinessEngine {

    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L
    private const val FOURTEEN_DAYS_MS = 14 * 24 * 60 * 60 * 1000L
    private const val TWENTY_EIGHT_DAYS_MS = 28 * 24 * 60 * 60 * 1000L

    private const val MIN_HISTORY_DAYS = 28
    private const val MIN_RECENT_SESSIONS = 3

    /**
     * Compute readiness from training session history.
     *
     * Returns [ReadinessResult.InsufficientData] when:
     * - Session list is empty
     * - Training history spans less than 28 days
     * - Fewer than 3 sessions exist in the last 14 days
     * - Chronic weekly volume is zero
     *
     * Returns [ReadinessResult.Ready] with score (0-100), status (GREEN/YELLOW/RED),
     * and ACWR details when data is sufficient.
     */
    fun computeReadiness(sessions: List<SessionSummary>, nowMs: Long): ReadinessResult {
        // Guard: empty sessions
        val oldestSession = sessions.minByOrNull { it.timestamp }
            ?: return ReadinessResult.InsufficientData

        // Guard: training history must span 28+ days
        val historyDays = (nowMs - oldestSession.timestamp) / ONE_DAY_MS
        if (historyDays < MIN_HISTORY_DAYS) return ReadinessResult.InsufficientData

        // Guard: need 3+ sessions in last 14 days
        val fourteenDaysAgo = nowMs - FOURTEEN_DAYS_MS
        val recentCount = sessions.count { it.timestamp > fourteenDaysAgo }
        if (recentCount < MIN_RECENT_SESSIONS) return ReadinessResult.InsufficientData

        // Compute acute load (last 7 days volume)
        val sevenDaysAgo = nowMs - SEVEN_DAYS_MS
        val acuteVolume = sessions
            .filter { it.timestamp > sevenDaysAgo }
            .sumOf { (it.weightPerCableKg * 2 * it.workingReps).toDouble() }
            .toFloat()

        // Compute chronic load (28-day total volume, divided by 4 for weekly average)
        val twentyEightDaysAgo = nowMs - TWENTY_EIGHT_DAYS_MS
        val chronicTotalVolume = sessions
            .filter { it.timestamp > twentyEightDaysAgo }
            .sumOf { (it.weightPerCableKg * 2 * it.workingReps).toDouble() }
            .toFloat()
        val chronicWeeklyAvg = chronicTotalVolume / 4f

        // Guard: zero chronic load (prevents division by zero)
        if (chronicWeeklyAvg <= 0f) return ReadinessResult.InsufficientData

        // ACWR ratio
        val acwr = acuteVolume / chronicWeeklyAvg

        // Map ACWR to 0-100 score and status
        val score = mapAcwrToScore(acwr)
        val status = when {
            score >= 70 -> ReadinessStatus.GREEN
            score >= 40 -> ReadinessStatus.YELLOW
            else -> ReadinessStatus.RED
        }

        return ReadinessResult.Ready(
            score = score,
            status = status,
            acwr = acwr,
            acuteVolumeKg = acuteVolume,
            chronicWeeklyAvgKg = chronicWeeklyAvg
        )
    }

    /**
     * Maps ACWR ratio to 0-100 readiness score.
     * Peak readiness at ACWR ~1.0, decreasing for both under- and over-training.
     *
     * Zones:
     * - ACWR < 0.5: ~30 (significant under-training)
     * - ACWR 0.5-0.8: 30-70 (increasing from under-training)
     * - ACWR 0.8-1.3: 70-100 (sweet spot, peak near 1.0)
     * - ACWR 1.3-1.5: 40-70 (overreaching)
     * - ACWR > 1.5: 0-40 (danger zone)
     *
     * Score is always clamped to 0-100.
     */
    internal fun mapAcwrToScore(acwr: Float): Int {
        val score = when {
            acwr < 0.5f -> 30
            acwr < 0.8f -> (30 + (acwr - 0.5f) / 0.3f * 40).toInt()
            acwr <= 1.3f -> {
                // Sweet spot: peak at 1.0, minimum 70 at edges (0.8 and 1.3)
                val distanceFromPeak = kotlin.math.abs(acwr - 1.0f)
                (70 + (1f - distanceFromPeak / 0.3f) * 30).toInt()
            }
            acwr <= 1.5f -> (40 + (1.5f - acwr) / 0.2f * 30).toInt()
            else -> (40 * (1f - ((acwr - 1.5f) / 0.5f).coerceAtMost(1f))).toInt()
        }
        return score.coerceIn(0, 100)
    }
}
