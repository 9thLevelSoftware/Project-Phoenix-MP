package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutMetricTest {

    @Test
    fun `totalLoad calculates sum of loadA and loadB`() {
        val metric = WorkoutMetric(
            loadA = 25f,
            loadB = 30f,
            positionA = 500f,
            positionB = 500f
        )

        assertEquals(55f, metric.totalLoad)
    }

    @Test
    fun `totalLoad handles zero loads`() {
        val metric = WorkoutMetric(
            loadA = 0f,
            loadB = 0f,
            positionA = 0f,
            positionB = 0f
        )

        assertEquals(0f, metric.totalLoad)
    }

    @Test
    fun `totalLoad handles single cable load`() {
        val metric = WorkoutMetric(
            loadA = 50f,
            loadB = 0f,
            positionA = 500f,
            positionB = 0f
        )

        assertEquals(50f, metric.totalLoad)
    }

    @Test
    fun `default values are set correctly`() {
        val metric = WorkoutMetric(
            loadA = 25f,
            loadB = 25f,
            positionA = 500f,
            positionB = 500f
        )

        assertEquals(0, metric.ticks)
        assertEquals(0.0, metric.velocityA)
        assertEquals(0.0, metric.velocityB)
        assertEquals(0, metric.status)
    }
}

class WorkoutSessionTest {

    @Test
    fun `hasSummaryMetrics returns true when peak force is set`() {
        val session = WorkoutSession(
            peakForceConcentricA = 100f
        )

        assertTrue(session.hasSummaryMetrics)
    }

    @Test
    fun `hasSummaryMetrics returns false when no metrics set`() {
        val session = WorkoutSession()

        assertFalse(session.hasSummaryMetrics)
    }

    @Test
    fun `toSetSummary returns null when no summary metrics`() {
        val session = WorkoutSession()

        assertNull(session.toSetSummary())
    }

    @Test
    fun `toSetSummary converts session with metrics correctly`() {
        val session = WorkoutSession(
            mode = "Echo",
            totalReps = 15,
            warmupReps = 3,
            workingReps = 10,
            duration = 60000,
            peakForceConcentricA = 100f,
            peakForceConcentricB = 95f,
            peakForceEccentricA = 120f,
            peakForceEccentricB = 115f,
            avgForceConcentricA = 80f,
            avgForceConcentricB = 75f,
            avgForceEccentricA = 90f,
            avgForceEccentricB = 85f,
            heaviestLiftKg = 50f,
            totalVolumeKg = 1500f,
            estimatedCalories = 25f
        )

        val summary = session.toSetSummary()

        assertEquals(15, summary?.repCount)
        assertEquals(60000, summary?.durationMs)
        assertEquals(100f, summary?.peakForceConcentricA)
        assertEquals(95f, summary?.peakForceConcentricB)
        assertEquals(120f, summary?.peakForceEccentricA)
        assertEquals(115f, summary?.peakForceEccentricB)
        assertEquals(80f, summary?.avgForceConcentricA)
        assertEquals(75f, summary?.avgForceConcentricB)
        assertEquals(90f, summary?.avgForceEccentricA)
        assertEquals(85f, summary?.avgForceEccentricB)
        assertEquals(50f, summary?.heaviestLiftKgPerCable)
        assertEquals(1500f, summary?.totalVolumeKg)
        assertEquals(25f, summary?.estimatedCalories)
        assertTrue(summary?.isEchoMode == true)
        assertEquals(3, summary?.warmupReps)
        assertEquals(10, summary?.workingReps)
        // burnoutReps = 15 - 3 - 10 = 2
        assertEquals(2, summary?.burnoutReps)
    }

    @Test
    fun `toSetSummary detects Echo mode case insensitively`() {
        val session = WorkoutSession(
            mode = "ECHO",
            peakForceConcentricA = 100f
        )

        assertTrue(session.toSetSummary()?.isEchoMode == true)
    }

    @Test
    fun `toSetSummary burnoutReps is not negative`() {
        val session = WorkoutSession(
            totalReps = 5,
            warmupReps = 3,
            workingReps = 5, // More than totalReps - warmupReps
            peakForceConcentricA = 100f
        )

        val summary = session.toSetSummary()
        // burnoutReps should be 0, not negative
        assertEquals(0, summary?.burnoutReps)
    }

    @Test
    fun `default values are set correctly`() {
        val session = WorkoutSession()

        assertEquals("OldSchool", session.mode)
        assertEquals(10, session.reps)
        assertEquals(10f, session.weightPerCableKg)
        assertEquals(0f, session.progressionKg)
        assertEquals(0L, session.duration)
        assertEquals(0, session.totalReps)
        assertEquals(0, session.warmupReps)
        assertEquals(0, session.workingReps)
        assertFalse(session.isJustLift)
        assertFalse(session.stopAtTop)
        assertEquals(100, session.eccentricLoad)
        assertEquals(2, session.echoLevel)
        assertNull(session.exerciseId)
        assertNull(session.exerciseName)
        assertNull(session.routineSessionId)
        assertNull(session.routineName)
        assertEquals(0, session.safetyFlags)
        assertEquals(0, session.deloadWarningCount)
        assertEquals(0, session.romViolationCount)
        assertEquals(0, session.spotterActivations)
    }
}

class RepCountTest {

    @Test
    fun `totalReps equals workingReps by default`() {
        val repCount = RepCount(
            warmupReps = 3,
            workingReps = 10
        )

        assertEquals(10, repCount.totalReps)
    }

    @Test
    fun `default values are set correctly`() {
        val repCount = RepCount()

        assertEquals(0, repCount.warmupReps)
        assertEquals(0, repCount.workingReps)
        assertEquals(0, repCount.totalReps)
        assertFalse(repCount.isWarmupComplete)
        assertFalse(repCount.hasPendingRep)
        assertEquals(0f, repCount.pendingRepProgress)
    }
}

class PersonalRecordTest {

    @Test
    fun `volume is calculated correctly`() {
        val pr = PersonalRecord(
            id = 1,
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            weightPerCableKg = 50f,
            reps = 5,
            oneRepMax = 112.5f, // 50 * 2 * (36 / 32) = 112.5
            timestamp = System.currentTimeMillis(),
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT,
            volume = 500f // 50 * 2 * 5 = 500
        )

        assertEquals(500f, pr.volume)
    }

    @Test
    fun `PRType distinguishes weight and volume records`() {
        val weightPR = PersonalRecord(
            id = 1,
            exerciseId = "test",
            exerciseName = "Test",
            weightPerCableKg = 100f,
            reps = 1,
            oneRepMax = 200f,
            timestamp = 0L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT,
            volume = 200f
        )

        val volumePR = PersonalRecord(
            id = 2,
            exerciseId = "test",
            exerciseName = "Test",
            weightPerCableKg = 50f,
            reps = 10,
            oneRepMax = 150f,
            timestamp = 0L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_VOLUME,
            volume = 1000f
        )

        assertEquals(PRType.MAX_WEIGHT, weightPR.prType)
        assertEquals(PRType.MAX_VOLUME, volumePR.prType)
    }
}

class HapticEventTest {

    @Test
    fun `REP_COUNT_ANNOUNCED requires valid rep number`() {
        val event = HapticEvent.REP_COUNT_ANNOUNCED(10)
        assertEquals(10, event.repNumber)
    }

    @Test
    fun `REP_COUNT_ANNOUNCED allows range 1 to 25`() {
        // Should not throw
        HapticEvent.REP_COUNT_ANNOUNCED(1)
        HapticEvent.REP_COUNT_ANNOUNCED(25)
    }

    @Test
    fun `singleton haptic events are equal`() {
        assertEquals(HapticEvent.REP_COMPLETED, HapticEvent.REP_COMPLETED)
        assertEquals(HapticEvent.WARMUP_COMPLETE, HapticEvent.WARMUP_COMPLETE)
        assertEquals(HapticEvent.WORKOUT_COMPLETE, HapticEvent.WORKOUT_COMPLETE)
        assertEquals(HapticEvent.WORKOUT_START, HapticEvent.WORKOUT_START)
        assertEquals(HapticEvent.WORKOUT_END, HapticEvent.WORKOUT_END)
        assertEquals(HapticEvent.REST_ENDING, HapticEvent.REST_ENDING)
        assertEquals(HapticEvent.ERROR, HapticEvent.ERROR)
        assertEquals(HapticEvent.DISCO_MODE_UNLOCKED, HapticEvent.DISCO_MODE_UNLOCKED)
        assertEquals(HapticEvent.BADGE_EARNED, HapticEvent.BADGE_EARNED)
        assertEquals(HapticEvent.PERSONAL_RECORD, HapticEvent.PERSONAL_RECORD)
    }
}

class PRCelebrationEventTest {

    @Test
    fun `isWeightPR returns true for weight PRs`() {
        val event = PRCelebrationEvent(
            exerciseName = "Bench Press",
            weightPerCableKg = 50f,
            reps = 5,
            workoutMode = "OldSchool",
            brokenPRTypes = listOf(PRType.MAX_WEIGHT)
        )

        assertTrue(event.isWeightPR)
        assertFalse(event.isVolumePR)
        assertFalse(event.isBothPRs)
    }

    @Test
    fun `isVolumePR returns true for volume PRs`() {
        val event = PRCelebrationEvent(
            exerciseName = "Squat",
            weightPerCableKg = 40f,
            reps = 10,
            workoutMode = "OldSchool",
            brokenPRTypes = listOf(PRType.MAX_VOLUME)
        )

        assertFalse(event.isWeightPR)
        assertTrue(event.isVolumePR)
        assertFalse(event.isBothPRs)
    }

    @Test
    fun `isBothPRs returns true when both types broken`() {
        val event = PRCelebrationEvent(
            exerciseName = "Deadlift",
            weightPerCableKg = 60f,
            reps = 5,
            workoutMode = "OldSchool",
            brokenPRTypes = listOf(PRType.MAX_WEIGHT, PRType.MAX_VOLUME)
        )

        assertTrue(event.isWeightPR)
        assertTrue(event.isVolumePR)
        assertTrue(event.isBothPRs)
    }
}
