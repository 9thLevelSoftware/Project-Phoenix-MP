package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.GhostRepComparison
import com.devil.phoenixproject.domain.model.GhostSessionCandidate
import com.devil.phoenixproject.domain.model.GhostSetSummary
import com.devil.phoenixproject.domain.model.GhostVerdict

/**
 * Pure stateless computation engine for ghost racing.
 * Handles session matching (find best ghost), per-rep velocity comparison,
 * and set-level delta aggregation.
 *
 * Follows the ReadinessEngine/RpgAttributeEngine pattern:
 * - Stateless object with pure functions
 * - No DI dependencies, no suspend functions
 * - All inputs provided as parameters
 */
object GhostRacingEngine {

    /**
     * Find the best ghost session candidate from a list of candidates.
     * Filters by weight tolerance and selects the one with the highest avgMcvMmS.
     *
     * @param candidates List of session candidates from DB query
     * @param exerciseId Exercise to match (pre-filtered by DB query)
     * @param mode Workout mode (pre-filtered by DB query)
     * @param weightPerCableKg Current session weight
     * @param weightToleranceKg Maximum weight difference allowed (default 5kg)
     * @return Best matching candidate or null if none qualify
     */
    fun findBestSession(
        candidates: List<GhostSessionCandidate>,
        exerciseId: String,
        mode: String,
        weightPerCableKg: Float,
        weightToleranceKg: Float = 5f
    ): GhostSessionCandidate? {
        TODO("RED phase stub")
    }

    /**
     * Compare a single rep's velocity against the ghost session's corresponding rep.
     *
     * @param currentMcvMmS Current rep's mean concentric velocity in mm/s
     * @param ghostMcvMmS Ghost session's corresponding rep velocity in mm/s
     * @return AHEAD if >5% faster, BEHIND if >5% slower, TIED if within tolerance
     */
    fun compareRep(currentMcvMmS: Float, ghostMcvMmS: Float): GhostVerdict {
        TODO("RED phase stub")
    }

    /**
     * Aggregate per-rep comparisons into a set-level summary.
     * BEYOND reps are excluded from delta calculations (no ghost data to compare).
     *
     * @param comparisons List of per-rep comparison results
     * @return Summary with totals, averages, counts, and overall verdict
     */
    fun computeSetDelta(comparisons: List<GhostRepComparison>): GhostSetSummary {
        TODO("RED phase stub")
    }
}
