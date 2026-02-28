package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.GhostRepComparison
import com.devil.phoenixproject.domain.model.GhostSessionCandidate
import com.devil.phoenixproject.domain.model.GhostVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostRacingEngineTest {

    // ---- Helper factory ----

    private fun candidate(
        id: String = "session-1",
        exerciseName: String? = "Bench Press",
        weightPerCableKg: Float = 50f,
        workingReps: Int = 8,
        avgMcvMmS: Float = 400f
    ) = GhostSessionCandidate(
        id = id,
        exerciseName = exerciseName,
        weightPerCableKg = weightPerCableKg,
        workingReps = workingReps,
        avgMcvMmS = avgMcvMmS
    )

    // ==========================================================
    // findBestSession tests
    // ==========================================================

    @Test
    fun findBestSessionReturnsNullWhenCandidatesEmpty() {
        val result = GhostRacingEngine.findBestSession(
            candidates = emptyList(),
            exerciseId = "ex1",
            mode = "CONCENTRIC",
            weightPerCableKg = 50f
        )
        assertNull(result)
    }

    @Test
    fun findBestSessionReturnsHighestAvgMcvWhenMultipleCandidatesWithinTolerance() {
        val candidates = listOf(
            candidate(id = "s1", weightPerCableKg = 48f, avgMcvMmS = 350f),
            candidate(id = "s2", weightPerCableKg = 52f, avgMcvMmS = 450f),
            candidate(id = "s3", weightPerCableKg = 50f, avgMcvMmS = 400f)
        )
        val result = GhostRacingEngine.findBestSession(
            candidates = candidates,
            exerciseId = "ex1",
            mode = "CONCENTRIC",
            weightPerCableKg = 50f
        )
        assertNotNull(result)
        assertEquals("s2", result.id)
        assertEquals(450f, result.avgMcvMmS)
    }

    @Test
    fun findBestSessionReturnsNullWhenAllCandidatesOutsideWeightTolerance() {
        val candidates = listOf(
            candidate(id = "s1", weightPerCableKg = 30f, avgMcvMmS = 500f),
            candidate(id = "s2", weightPerCableKg = 70f, avgMcvMmS = 600f)
        )
        val result = GhostRacingEngine.findBestSession(
            candidates = candidates,
            exerciseId = "ex1",
            mode = "CONCENTRIC",
            weightPerCableKg = 50f,
            weightToleranceKg = 5f
        )
        assertNull(result)
    }

    @Test
    fun findBestSessionFiltersCorrectlyByWeightTolerance() {
        val candidates = listOf(
            candidate(id = "s1", weightPerCableKg = 44f, avgMcvMmS = 600f), // 6kg off -> outside 5kg tolerance
            candidate(id = "s2", weightPerCableKg = 55f, avgMcvMmS = 400f), // 5kg off -> within tolerance
            candidate(id = "s3", weightPerCableKg = 50f, avgMcvMmS = 350f)  // exact match
        )
        val result = GhostRacingEngine.findBestSession(
            candidates = candidates,
            exerciseId = "ex1",
            mode = "CONCENTRIC",
            weightPerCableKg = 50f,
            weightToleranceKg = 5f
        )
        assertNotNull(result)
        // s1 is excluded (6kg off), s2 has highest avgMcv of remaining
        assertEquals("s2", result.id)
    }

    // ==========================================================
    // compareRep tests
    // ==========================================================

    @Test
    fun compareRepReturnsAheadWhenCurrentExceedsGhostByMoreThan5Percent() {
        // Ghost = 400, current = 430 -> delta = 7.5% -> AHEAD
        val verdict = GhostRacingEngine.compareRep(currentMcvMmS = 430f, ghostMcvMmS = 400f)
        assertEquals(GhostVerdict.AHEAD, verdict)
    }

    @Test
    fun compareRepReturnsBehindWhenCurrentBelowGhostByMoreThan5Percent() {
        // Ghost = 400, current = 370 -> delta = -7.5% -> BEHIND
        val verdict = GhostRacingEngine.compareRep(currentMcvMmS = 370f, ghostMcvMmS = 400f)
        assertEquals(GhostVerdict.BEHIND, verdict)
    }

    @Test
    fun compareRepReturnsTiedWhenDeltaWithinPlusMinus5Percent() {
        // Ghost = 400, current = 410 -> delta = 2.5% -> TIED
        val verdict = GhostRacingEngine.compareRep(currentMcvMmS = 410f, ghostMcvMmS = 400f)
        assertEquals(GhostVerdict.TIED, verdict)

        // Ghost = 400, current = 390 -> delta = -2.5% -> TIED
        val verdict2 = GhostRacingEngine.compareRep(currentMcvMmS = 390f, ghostMcvMmS = 400f)
        assertEquals(GhostVerdict.TIED, verdict2)
    }

    @Test
    fun compareRepReturnsAheadWhenGhostMcvIsZeroOrNegative() {
        // Guard against bad data -- treat zero/negative ghost as always ahead
        val verdict1 = GhostRacingEngine.compareRep(currentMcvMmS = 400f, ghostMcvMmS = 0f)
        assertEquals(GhostVerdict.AHEAD, verdict1)

        val verdict2 = GhostRacingEngine.compareRep(currentMcvMmS = 400f, ghostMcvMmS = -10f)
        assertEquals(GhostVerdict.AHEAD, verdict2)
    }

    // ==========================================================
    // computeSetDelta tests
    // ==========================================================

    @Test
    fun computeSetDeltaReturnsAheadWhenTotalDeltaPositive() {
        val comparisons = listOf(
            GhostRepComparison(repNumber = 1, currentMcvMmS = 430f, ghostMcvMmS = 400f, deltaMcvMmS = 30f, verdict = GhostVerdict.AHEAD),
            GhostRepComparison(repNumber = 2, currentMcvMmS = 420f, ghostMcvMmS = 400f, deltaMcvMmS = 20f, verdict = GhostVerdict.AHEAD),
            GhostRepComparison(repNumber = 3, currentMcvMmS = 390f, ghostMcvMmS = 400f, deltaMcvMmS = -10f, verdict = GhostVerdict.TIED)
        )
        val summary = GhostRacingEngine.computeSetDelta(comparisons)
        assertEquals(GhostVerdict.AHEAD, summary.overallVerdict)
        assertTrue(summary.totalDeltaMcvMmS > 0f)
        assertEquals(3, summary.repsCompared)
    }

    @Test
    fun computeSetDeltaReturnsBehindWhenTotalDeltaNegative() {
        val comparisons = listOf(
            GhostRepComparison(repNumber = 1, currentMcvMmS = 370f, ghostMcvMmS = 400f, deltaMcvMmS = -30f, verdict = GhostVerdict.BEHIND),
            GhostRepComparison(repNumber = 2, currentMcvMmS = 380f, ghostMcvMmS = 400f, deltaMcvMmS = -20f, verdict = GhostVerdict.BEHIND),
            GhostRepComparison(repNumber = 3, currentMcvMmS = 410f, ghostMcvMmS = 400f, deltaMcvMmS = 10f, verdict = GhostVerdict.TIED)
        )
        val summary = GhostRacingEngine.computeSetDelta(comparisons)
        assertEquals(GhostVerdict.BEHIND, summary.overallVerdict)
        assertTrue(summary.totalDeltaMcvMmS < 0f)
    }

    @Test
    fun computeSetDeltaCountsRepsCorrectly() {
        val comparisons = listOf(
            GhostRepComparison(repNumber = 1, currentMcvMmS = 430f, ghostMcvMmS = 400f, deltaMcvMmS = 30f, verdict = GhostVerdict.AHEAD),
            GhostRepComparison(repNumber = 2, currentMcvMmS = 370f, ghostMcvMmS = 400f, deltaMcvMmS = -30f, verdict = GhostVerdict.BEHIND),
            GhostRepComparison(repNumber = 3, currentMcvMmS = 400f, ghostMcvMmS = 400f, deltaMcvMmS = 0f, verdict = GhostVerdict.TIED),
            GhostRepComparison(repNumber = 4, currentMcvMmS = 420f, ghostMcvMmS = 0f, deltaMcvMmS = 0f, verdict = GhostVerdict.BEYOND)
        )
        val summary = GhostRacingEngine.computeSetDelta(comparisons)
        assertEquals(1, summary.repsAhead)
        assertEquals(1, summary.repsBehind)
        assertEquals(1, summary.repsBeyondGhost)
        assertEquals(3, summary.repsCompared) // BEYOND excluded from comparison count
    }

    @Test
    fun computeSetDeltaExcludesBeyondRepsFromDeltaCalculations() {
        val comparisons = listOf(
            GhostRepComparison(repNumber = 1, currentMcvMmS = 430f, ghostMcvMmS = 400f, deltaMcvMmS = 30f, verdict = GhostVerdict.AHEAD),
            GhostRepComparison(repNumber = 2, currentMcvMmS = 420f, ghostMcvMmS = 400f, deltaMcvMmS = 20f, verdict = GhostVerdict.AHEAD),
            // BEYOND reps should NOT contribute to delta calculations
            GhostRepComparison(repNumber = 3, currentMcvMmS = 500f, ghostMcvMmS = 0f, deltaMcvMmS = 500f, verdict = GhostVerdict.BEYOND),
            GhostRepComparison(repNumber = 4, currentMcvMmS = 480f, ghostMcvMmS = 0f, deltaMcvMmS = 480f, verdict = GhostVerdict.BEYOND)
        )
        val summary = GhostRacingEngine.computeSetDelta(comparisons)
        // Only reps 1 and 2 contribute: 30 + 20 = 50 total, 25 avg
        assertEquals(50f, summary.totalDeltaMcvMmS)
        assertEquals(25f, summary.avgDeltaMcvMmS)
        assertEquals(2, summary.repsCompared)
        assertEquals(2, summary.repsBeyondGhost)
    }
}
