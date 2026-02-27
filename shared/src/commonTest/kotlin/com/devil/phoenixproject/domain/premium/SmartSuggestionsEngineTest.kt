package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.*
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartSuggestionsEngineTest {

    // ---- Helper constants ----

    private val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private val ONE_HOUR_MS = 60 * 60 * 1000L
    private val NOW = 1_700_000_000_000L // Fixed reference time

    // ---- Helper factory ----

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

    // ==========================================================
    // SUGG-01: Weekly Volume
    // ==========================================================

    @Test
    fun volumeEmptySessions() {
        val report = SmartSuggestionsEngine.computeWeeklyVolume(emptyList(), NOW)
        assertTrue(report.volumes.isEmpty())
    }

    @Test
    fun volumeThreeChestSessions() {
        val sessions = listOf(
            session(timestamp = NOW - ONE_DAY_MS * 1),
            session(timestamp = NOW - ONE_DAY_MS * 2),
            session(timestamp = NOW - ONE_DAY_MS * 3)
        )
        val report = SmartSuggestionsEngine.computeWeeklyVolume(sessions, NOW)
        assertEquals(1, report.volumes.size)
        val chest = report.volumes.first()
        assertEquals("Chest", chest.muscleGroup)
        assertEquals(3, chest.sets)
        assertEquals(24, chest.reps) // 8 working reps * 3
        // totalKg = 50 * 2 * 8 * 3 = 2400
        assertEquals(2400f, chest.totalKg)
    }

    @Test
    fun volumeExcludesOutsideWeek() {
        val sessions = listOf(
            session(timestamp = NOW - ONE_DAY_MS * 1),  // inside
            session(timestamp = NOW - ONE_DAY_MS * 10)  // outside 7-day window
        )
        val report = SmartSuggestionsEngine.computeWeeklyVolume(sessions, NOW)
        assertEquals(1, report.volumes.size)
        assertEquals(1, report.volumes.first().sets)
    }

    @Test
    fun volumeMultipleMuscleGroups() {
        val sessions = listOf(
            session(muscleGroup = "Chest", timestamp = NOW - ONE_DAY_MS),
            session(muscleGroup = "Back", exerciseName = "Row", timestamp = NOW - ONE_DAY_MS * 2)
        )
        val report = SmartSuggestionsEngine.computeWeeklyVolume(sessions, NOW)
        assertEquals(2, report.volumes.size)
        val groups = report.volumes.map { it.muscleGroup }.toSet()
        assertTrue(groups.contains("Chest"))
        assertTrue(groups.contains("Back"))
    }

    // ==========================================================
    // SUGG-02: Balance Analysis
    // ==========================================================

    @Test
    fun balanceAllPushShowsImbalance() {
        // 4 weeks of only chest exercises
        val sessions = (0 until 10).map {
            session(muscleGroup = "Chest", timestamp = NOW - ONE_DAY_MS * it)
        }
        val analysis = SmartSuggestionsEngine.analyzeBalance(sessions, NOW)
        assertTrue(analysis.pushVolume > 0f)
        assertEquals(0f, analysis.pullVolume)
        assertEquals(0f, analysis.legsVolume)
        // Should flag pull and legs as imbalanced
        assertTrue(analysis.imbalances.size >= 2)
        val categories = analysis.imbalances.map { it.category }.toSet()
        assertTrue(categories.contains(MovementCategory.PULL))
        assertTrue(categories.contains(MovementCategory.LEGS))
    }

    @Test
    fun balanceEvenDistributionNoImbalance() {
        val sessions = listOf(
            session(muscleGroup = "Chest", timestamp = NOW - ONE_DAY_MS),
            session(muscleGroup = "Back", exerciseName = "Row", timestamp = NOW - ONE_DAY_MS * 2),
            session(muscleGroup = "Legs", exerciseName = "Squat", timestamp = NOW - ONE_DAY_MS * 3)
        )
        val analysis = SmartSuggestionsEngine.analyzeBalance(sessions, NOW)
        assertTrue(analysis.imbalances.isEmpty())
    }

    @Test
    fun balanceCoreOnlyDoesNotAffectRatio() {
        val sessions = listOf(
            session(muscleGroup = "Chest", timestamp = NOW - ONE_DAY_MS),
            session(muscleGroup = "Back", exerciseName = "Row", timestamp = NOW - ONE_DAY_MS * 2),
            session(muscleGroup = "Legs", exerciseName = "Squat", timestamp = NOW - ONE_DAY_MS * 3),
            session(muscleGroup = "Core", exerciseName = "Plank", timestamp = NOW - ONE_DAY_MS * 4),
            session(muscleGroup = "Core", exerciseName = "Crunch", timestamp = NOW - ONE_DAY_MS * 5)
        )
        val analysis = SmartSuggestionsEngine.analyzeBalance(sessions, NOW)
        // Core sessions should not cause imbalance in push/pull/legs
        assertTrue(analysis.imbalances.isEmpty())
    }

    // ==========================================================
    // SUGG-03: Neglected Exercises
    // ==========================================================

    @Test
    fun neglectedExerciseFlagged() {
        val sessions = listOf(
            session(exerciseId = "ex1", timestamp = NOW - ONE_DAY_MS * 20) // 20 days ago
        )
        val neglected = SmartSuggestionsEngine.findNeglectedExercises(sessions, NOW)
        assertEquals(1, neglected.size)
        assertEquals("ex1", neglected.first().exerciseId)
        assertEquals(20, neglected.first().daysSinceLastPerformed)
    }

    @Test
    fun recentExerciseNotFlagged() {
        val sessions = listOf(
            session(exerciseId = "ex1", timestamp = NOW - ONE_DAY_MS * 5) // 5 days ago
        )
        val neglected = SmartSuggestionsEngine.findNeglectedExercises(sessions, NOW)
        assertTrue(neglected.isEmpty())
    }

    @Test
    fun multipleNeglectedSortedByDaysDesc() {
        val sessions = listOf(
            session(exerciseId = "ex1", exerciseName = "Bench", timestamp = NOW - ONE_DAY_MS * 20),
            session(exerciseId = "ex2", exerciseName = "Squat", muscleGroup = "Legs", timestamp = NOW - ONE_DAY_MS * 30),
            session(exerciseId = "ex3", exerciseName = "Row", muscleGroup = "Back", timestamp = NOW - ONE_DAY_MS * 5) // recent
        )
        val neglected = SmartSuggestionsEngine.findNeglectedExercises(sessions, NOW)
        assertEquals(2, neglected.size)
        assertEquals("ex2", neglected[0].exerciseId) // 30 days - most neglected
        assertEquals("ex1", neglected[1].exerciseId) // 20 days
    }

    @Test
    fun neglectedUsesLatestSessionForExercise() {
        // Exercise done 20 days ago AND 5 days ago -> uses 5 days (most recent)
        val sessions = listOf(
            session(exerciseId = "ex1", timestamp = NOW - ONE_DAY_MS * 20),
            session(exerciseId = "ex1", timestamp = NOW - ONE_DAY_MS * 5)
        )
        val neglected = SmartSuggestionsEngine.findNeglectedExercises(sessions, NOW)
        assertTrue(neglected.isEmpty()) // 5 days < 14 day threshold
    }

    // ==========================================================
    // SUGG-04: Plateau Detection
    // ==========================================================

    @Test
    fun plateauDetectedFiveSameWeight() {
        val sessions = (0 until 5).map { i ->
            session(
                exerciseId = "ex1",
                weightPerCableKg = 50f,
                timestamp = NOW - ONE_DAY_MS * (5 - i)
            )
        }
        val plateaus = SmartSuggestionsEngine.detectPlateaus(sessions)
        assertEquals(1, plateaus.size)
        assertEquals("ex1", plateaus.first().exerciseId)
        assertEquals(50f, plateaus.first().currentWeightKg)
        assertTrue(plateaus.first().sessionCount >= 4)
    }

    @Test
    fun noPlateauWithThreeSessions() {
        val sessions = (0 until 3).map { i ->
            session(
                exerciseId = "ex1",
                weightPerCableKg = 50f,
                timestamp = NOW - ONE_DAY_MS * (3 - i)
            )
        }
        val plateaus = SmartSuggestionsEngine.detectPlateaus(sessions)
        assertTrue(plateaus.isEmpty())
    }

    @Test
    fun noPlateauWhenWeightIncreasing() {
        val sessions = (0 until 5).map { i ->
            session(
                exerciseId = "ex1",
                weightPerCableKg = 50f + i * 2.5f, // Increasing weight
                timestamp = NOW - ONE_DAY_MS * (5 - i)
            )
        }
        val plateaus = SmartSuggestionsEngine.detectPlateaus(sessions)
        assertTrue(plateaus.isEmpty())
    }

    @Test
    fun plateauToleratesSmallVariation() {
        // Weight varies within 0.5kg tolerance
        val weights = listOf(50f, 50.5f, 50f, 50.25f, 50f)
        val sessions = weights.mapIndexed { i, w ->
            session(
                exerciseId = "ex1",
                weightPerCableKg = w,
                timestamp = NOW - ONE_DAY_MS * (5 - i)
            )
        }
        val plateaus = SmartSuggestionsEngine.detectPlateaus(sessions)
        assertEquals(1, plateaus.size) // Should still detect plateau
    }

    // ==========================================================
    // SUGG-05: Time-of-Day Analysis
    // ==========================================================

    @Test
    fun timeOfDayAllMorning() {
        // Sessions at 8am UTC (MORNING window: 7-10)
        val baseTime = NOW - (NOW % ONE_DAY_MS) // midnight UTC
        val sessions = (0 until 5).map { i ->
            session(timestamp = baseTime - ONE_DAY_MS * i + 8 * ONE_HOUR_MS) // 8am each day
        }
        val analysis = SmartSuggestionsEngine.analyzeTimeOfDay(sessions, TimeZone.UTC)
        assertEquals(TimeWindow.MORNING, analysis.optimalWindow)
        assertEquals(5, analysis.windowCounts[TimeWindow.MORNING])
    }

    @Test
    fun timeOfDayMixedWithClearWinner() {
        val baseTime = NOW - (NOW % ONE_DAY_MS)
        val sessions = listOf(
            // 4 evening sessions (high volume)
            session(timestamp = baseTime + 17 * ONE_HOUR_MS, weightPerCableKg = 80f),
            session(timestamp = baseTime - ONE_DAY_MS + 17 * ONE_HOUR_MS, weightPerCableKg = 80f),
            session(timestamp = baseTime - 2 * ONE_DAY_MS + 17 * ONE_HOUR_MS, weightPerCableKg = 80f),
            session(timestamp = baseTime - 3 * ONE_DAY_MS + 17 * ONE_HOUR_MS, weightPerCableKg = 80f),
            // 1 morning session (lower volume)
            session(timestamp = baseTime + 8 * ONE_HOUR_MS, weightPerCableKg = 40f)
        )
        val analysis = SmartSuggestionsEngine.analyzeTimeOfDay(sessions, TimeZone.UTC)
        assertEquals(TimeWindow.EVENING, analysis.optimalWindow)
    }

    @Test
    fun timeOfDayTooFewSessionsNotOptimal() {
        val baseTime = NOW - (NOW % ONE_DAY_MS)
        val sessions = listOf(
            // 2 morning sessions (below 3-session threshold)
            session(timestamp = baseTime + 8 * ONE_HOUR_MS, weightPerCableKg = 100f),
            session(timestamp = baseTime - ONE_DAY_MS + 8 * ONE_HOUR_MS, weightPerCableKg = 100f),
            // 3 evening sessions (meets threshold)
            session(timestamp = baseTime + 17 * ONE_HOUR_MS, weightPerCableKg = 40f),
            session(timestamp = baseTime - ONE_DAY_MS + 17 * ONE_HOUR_MS, weightPerCableKg = 40f),
            session(timestamp = baseTime - 2 * ONE_DAY_MS + 17 * ONE_HOUR_MS, weightPerCableKg = 40f)
        )
        val analysis = SmartSuggestionsEngine.analyzeTimeOfDay(sessions, TimeZone.UTC)
        // Morning has higher volume per session but only 2 sessions -> not eligible
        // Evening has 3 sessions -> eligible
        assertEquals(TimeWindow.EVENING, analysis.optimalWindow)
    }

    @Test
    fun timeOfDayEmptySessions() {
        val analysis = SmartSuggestionsEngine.analyzeTimeOfDay(emptyList())
        assertNull(analysis.optimalWindow)
        assertTrue(analysis.windowVolumes.isEmpty() || analysis.windowVolumes.values.all { it == 0f })
    }

    // ==========================================================
    // Classification
    // ==========================================================

    @Test
    fun classifyMuscleGroups() {
        assertEquals(MovementCategory.PUSH, SmartSuggestionsEngine.classifyMuscleGroup("Chest"))
        assertEquals(MovementCategory.PUSH, SmartSuggestionsEngine.classifyMuscleGroup("Shoulders"))
        assertEquals(MovementCategory.PUSH, SmartSuggestionsEngine.classifyMuscleGroup("Triceps"))
        assertEquals(MovementCategory.PULL, SmartSuggestionsEngine.classifyMuscleGroup("Back"))
        assertEquals(MovementCategory.PULL, SmartSuggestionsEngine.classifyMuscleGroup("Biceps"))
        assertEquals(MovementCategory.LEGS, SmartSuggestionsEngine.classifyMuscleGroup("Legs"))
        assertEquals(MovementCategory.LEGS, SmartSuggestionsEngine.classifyMuscleGroup("Glutes"))
        assertEquals(MovementCategory.CORE, SmartSuggestionsEngine.classifyMuscleGroup("Core"))
        assertEquals(MovementCategory.CORE, SmartSuggestionsEngine.classifyMuscleGroup("Full Body"))
    }

    @Test
    fun classifyCaseInsensitive() {
        assertEquals(MovementCategory.PUSH, SmartSuggestionsEngine.classifyMuscleGroup("chest"))
        assertEquals(MovementCategory.PULL, SmartSuggestionsEngine.classifyMuscleGroup("BACK"))
        assertEquals(MovementCategory.LEGS, SmartSuggestionsEngine.classifyMuscleGroup("legs"))
    }

    @Test
    fun classifyUnknownDefaultsToCore() {
        assertEquals(MovementCategory.CORE, SmartSuggestionsEngine.classifyMuscleGroup("Unknown"))
        assertEquals(MovementCategory.CORE, SmartSuggestionsEngine.classifyMuscleGroup(""))
    }

    // ========== classifyTimeWindow timezone fix (BOARD-01) ==========

    @Test
    fun classifyTimeWindowUsesLocalTime() {
        // 2023-11-15 08:00:00 UTC = 19:00 in AEDT (UTC+11, November DST)
        val utc8am = 1_700_035_200_000L
        val utcPlus10 = TimeZone.of("Australia/Melbourne")
        // In AEDT, 8am UTC is 7pm local -> EVENING (15..19)
        val result = SmartSuggestionsEngine.classifyTimeWindow(utc8am, utcPlus10)
        assertEquals(TimeWindow.EVENING, result)
    }

    @Test
    fun classifyTimeWindowMorningInUtc() {
        // 2023-11-15 08:00:00 UTC
        val utc8am = 1_700_035_200_000L
        val utc = TimeZone.UTC
        val result = SmartSuggestionsEngine.classifyTimeWindow(utc8am, utc)
        assertEquals(TimeWindow.MORNING, result)
    }

    @Test
    fun classifyTimeWindowNightCrossover() {
        // 2023-11-15 23:00:00 UTC = 10:00 next day in UTC+11
        val utc11pm = 1_700_089_200_000L
        val utcPlus11 = TimeZone.of("Pacific/Noumea")
        // In UTC+11, 11pm UTC is 10am next day -> AFTERNOON (10..14)
        val result = SmartSuggestionsEngine.classifyTimeWindow(utc11pm, utcPlus11)
        assertEquals(TimeWindow.AFTERNOON, result)
    }
}
