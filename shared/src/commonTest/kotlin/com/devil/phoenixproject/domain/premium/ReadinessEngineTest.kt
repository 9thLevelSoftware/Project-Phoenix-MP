package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ReadinessEngineTest {

    // ---- Helper constants ----

    private val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L
    private val NOW = 1_700_000_000_000L // Fixed reference time

    // ---- Helper factory (follows SmartSuggestionsEngineTest pattern) ----

    private fun session(
        exerciseId: String = "ex1",
        exerciseName: String = "Bench Press",
        muscleGroup: String = "Chest",
        timestamp: Long = NOW,
        weightPerCableKg: Float = 50f,
        totalReps: Int = 10,
        workingReps: Int = 8
    ) = SessionSummary(
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        muscleGroup = muscleGroup,
        timestamp = timestamp,
        weightPerCableKg = weightPerCableKg,
        totalReps = totalReps,
        workingReps = workingReps
    )

    /**
     * Creates a set of sessions spread over the given number of days,
     * all within the last [days] days from NOW. Useful for setting up
     * data sufficiency scenarios.
     */
    private fun sessionsOverDays(
        days: Int,
        count: Int,
        weightPerCableKg: Float = 50f,
        workingReps: Int = 8
    ): List<SessionSummary> {
        return (0 until count).map { i ->
            val dayOffset = (days.toLong() * i) / count
            session(
                timestamp = NOW - dayOffset * ONE_DAY_MS,
                weightPerCableKg = weightPerCableKg,
                workingReps = workingReps
            )
        }
    }

    // ==========================================================
    // Data Sufficiency Guards
    // ==========================================================

    @Test
    fun emptySessionsReturnsInsufficientData() {
        val result = ReadinessEngine.computeReadiness(emptyList(), NOW)
        assertIs<ReadinessResult.InsufficientData>(result)
    }

    @Test
    fun historyLessThan28DaysReturnsInsufficientData() {
        // Sessions spanning only 20 days -- below the 28-day minimum
        val sessions = listOf(
            session(timestamp = NOW - 20 * ONE_DAY_MS),
            session(timestamp = NOW - 15 * ONE_DAY_MS),
            session(timestamp = NOW - 10 * ONE_DAY_MS),
            session(timestamp = NOW - 5 * ONE_DAY_MS),
            session(timestamp = NOW - 1 * ONE_DAY_MS)
        )
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.InsufficientData>(result)
    }

    @Test
    fun fewerThan3RecentSessionsReturnsInsufficientData() {
        // History spans 30+ days but only 2 sessions in last 14 days
        val sessions = listOf(
            session(timestamp = NOW - 35 * ONE_DAY_MS),
            session(timestamp = NOW - 30 * ONE_DAY_MS),
            session(timestamp = NOW - 25 * ONE_DAY_MS),
            session(timestamp = NOW - 20 * ONE_DAY_MS),
            // Only 2 recent sessions (need 3)
            session(timestamp = NOW - 10 * ONE_DAY_MS),
            session(timestamp = NOW - 5 * ONE_DAY_MS)
        )
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.InsufficientData>(result)
    }

    @Test
    fun zeroChronicVolumeReturnsInsufficientData() {
        // Sessions exist but all have 0 working reps -> chronic volume is 0
        val sessions = listOf(
            session(timestamp = NOW - 35 * ONE_DAY_MS, workingReps = 0),
            session(timestamp = NOW - 30 * ONE_DAY_MS, workingReps = 0),
            session(timestamp = NOW - 20 * ONE_DAY_MS, workingReps = 0),
            session(timestamp = NOW - 10 * ONE_DAY_MS, workingReps = 0),
            session(timestamp = NOW - 5 * ONE_DAY_MS, workingReps = 0),
            session(timestamp = NOW - 1 * ONE_DAY_MS, workingReps = 0)
        )
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.InsufficientData>(result)
    }

    // ==========================================================
    // ACWR Sweet Spot (0.8-1.3) -> HIGH readiness (70-100)
    // ==========================================================

    @Test
    fun sweetSpotAcwrReturnsGreenStatus() {
        // Set up sessions so ACWR is near 1.0 (acute ~ chronic weekly avg)
        // 4 weeks of consistent training: sessions every 2 days, same weight
        val sessions = (0 until 20).map { i ->
            session(
                timestamp = NOW - (i * 2L) * ONE_DAY_MS,
                weightPerCableKg = 50f,
                workingReps = 8
            )
        }
        // With consistent training across 4 weeks, ACWR should be near 1.0
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.Ready>(result)
        assertTrue(result.score >= 70, "Sweet spot ACWR should yield score >= 70, got ${result.score}")
        assertEquals(ReadinessStatus.GREEN, result.status)
        assertTrue(result.acwr in 0.8f..1.3f, "ACWR should be in sweet spot, got ${result.acwr}")
    }

    // ==========================================================
    // Under-training (ACWR < 0.8) -> moderate readiness
    // ==========================================================

    @Test
    fun underTrainingAcwrReturnsLowerScore() {
        // History with heavy chronic load but very light recent week
        // Chronic: lots of volume in weeks 2-4; Acute: minimal volume in week 1
        val chronicSessions = (0 until 15).map { i ->
            session(
                timestamp = NOW - (8 + i * 2L) * ONE_DAY_MS, // Days 8-36 ago
                weightPerCableKg = 80f,
                workingReps = 10
            )
        }
        val acuteSessions = listOf(
            // Very light week (1 session, low volume)
            session(
                timestamp = NOW - 2 * ONE_DAY_MS,
                weightPerCableKg = 20f,
                workingReps = 3
            )
        )
        val sessions = chronicSessions + acuteSessions
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.Ready>(result)
        assertTrue(result.acwr < 0.8f, "ACWR should be below 0.8 for under-training, got ${result.acwr}")
        // Under-training still gets a score, but lower than sweet spot
        assertTrue(result.score < 70, "Under-training score should be < 70, got ${result.score}")
    }

    // ==========================================================
    // Overreaching (ACWR 1.3-1.5) -> YELLOW/reduced readiness
    // ==========================================================

    @Test
    fun overreachingAcwrReturnsYellowOrRed() {
        // Light chronic load (few sessions weeks 2-4) but heavy acute week
        val chronicSessions = listOf(
            session(timestamp = NOW - 35 * ONE_DAY_MS, weightPerCableKg = 30f, workingReps = 5),
            session(timestamp = NOW - 28 * ONE_DAY_MS, weightPerCableKg = 30f, workingReps = 5),
            session(timestamp = NOW - 21 * ONE_DAY_MS, weightPerCableKg = 30f, workingReps = 5),
            session(timestamp = NOW - 14 * ONE_DAY_MS, weightPerCableKg = 30f, workingReps = 5)
        )
        // Heavy acute week -- high volume spike
        val acuteSessions = (0 until 6).map { i ->
            session(
                timestamp = NOW - i * ONE_DAY_MS,
                weightPerCableKg = 80f,
                workingReps = 12
            )
        }
        val sessions = chronicSessions + acuteSessions
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.Ready>(result)
        assertTrue(result.acwr > 1.3f, "ACWR should be > 1.3 for overreaching, got ${result.acwr}")
        // Overreaching should yield reduced readiness
        assertTrue(result.score < 70, "Overreaching score should be < 70, got ${result.score}")
    }

    // ==========================================================
    // Danger Zone (ACWR > 1.5) -> RED/low readiness
    // ==========================================================

    @Test
    fun dangerZoneAcwrReturnsRedStatus() {
        // Very light chronic load, extremely heavy acute week
        val chronicSessions = listOf(
            session(timestamp = NOW - 35 * ONE_DAY_MS, weightPerCableKg = 20f, workingReps = 3),
            session(timestamp = NOW - 28 * ONE_DAY_MS, weightPerCableKg = 20f, workingReps = 3),
            session(timestamp = NOW - 21 * ONE_DAY_MS, weightPerCableKg = 20f, workingReps = 3)
        )
        // Massive acute spike
        val acuteSessions = (0 until 7).map { i ->
            session(
                timestamp = NOW - i * ONE_DAY_MS,
                weightPerCableKg = 100f,
                workingReps = 15
            )
        }
        val sessions = chronicSessions + acuteSessions
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.Ready>(result)
        assertTrue(result.acwr > 1.5f, "ACWR should be > 1.5 for danger zone, got ${result.acwr}")
        assertTrue(result.score < 40, "Danger zone score should be < 40, got ${result.score}")
        assertEquals(ReadinessStatus.RED, result.status)
    }

    // ==========================================================
    // mapAcwrToScore direct tests
    // ==========================================================

    @Test
    fun mapAcwrToScoreClampedTo0And100() {
        // Very extreme values should still be in 0-100
        assertTrue(ReadinessEngine.mapAcwrToScore(0f) in 0..100)
        assertTrue(ReadinessEngine.mapAcwrToScore(3f) in 0..100)
        assertTrue(ReadinessEngine.mapAcwrToScore(10f) in 0..100)
    }

    @Test
    fun mapAcwrToScoreSweetSpotIsHigh() {
        val score10 = ReadinessEngine.mapAcwrToScore(1.0f)
        assertTrue(score10 >= 70, "ACWR 1.0 should map to high score, got $score10")
        assertTrue(score10 <= 100, "ACWR 1.0 should map to score <= 100, got $score10")
    }

    @Test
    fun mapAcwrToScoreMonotonicInSweetSpot() {
        // Score should peak near 1.0 -- scores at edges of sweet spot should be >= 70
        val score08 = ReadinessEngine.mapAcwrToScore(0.8f)
        val score10 = ReadinessEngine.mapAcwrToScore(1.0f)
        val score13 = ReadinessEngine.mapAcwrToScore(1.3f)
        assertTrue(score08 >= 70, "ACWR 0.8 should be in sweet spot zone, got $score08")
        assertTrue(score10 >= score08, "ACWR 1.0 should score >= 0.8, got $score10 vs $score08")
        assertTrue(score13 >= 70, "ACWR 1.3 should be in sweet spot zone, got $score13")
    }

    @Test
    fun mapAcwrToScoreDecreasesInDangerZone() {
        val scoreSweet = ReadinessEngine.mapAcwrToScore(1.0f)
        val scoreOver = ReadinessEngine.mapAcwrToScore(1.5f)
        val scoreDanger = ReadinessEngine.mapAcwrToScore(2.0f)
        assertTrue(scoreSweet > scoreOver, "Score should decrease from sweet spot to overreaching")
        assertTrue(scoreOver > scoreDanger, "Score should decrease from overreaching to danger zone")
    }

    // ==========================================================
    // Ready result field verification
    // ==========================================================

    @Test
    fun readyResultIncludesAllFields() {
        val sessions = (0 until 20).map { i ->
            session(
                timestamp = NOW - (i * 2L) * ONE_DAY_MS,
                weightPerCableKg = 50f,
                workingReps = 8
            )
        }
        val result = ReadinessEngine.computeReadiness(sessions, NOW)
        assertIs<ReadinessResult.Ready>(result)
        assertTrue(result.score in 0..100)
        assertTrue(result.acwr > 0f)
        assertTrue(result.acuteVolumeKg >= 0f)
        assertTrue(result.chronicWeeklyAvgKg > 0f)
    }
}
