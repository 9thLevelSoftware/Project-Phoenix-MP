package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutParametersTest {

    @Test
    fun `default values are set correctly for Program mode`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10
        )

        assertEquals(10, params.reps)
        assertEquals(0f, params.weightPerCableKg)
        assertEquals(0f, params.progressionRegressionKg)
        assertFalse(params.isJustLift)
        assertFalse(params.useAutoStart)
        assertFalse(params.stopAtTop)
        assertEquals(3, params.warmupReps)
        assertNull(params.selectedExerciseId)
        assertFalse(params.isAMRAP)
        assertNull(params.lastUsedWeightKg)
        assertNull(params.prWeightKg)
        assertTrue(params.stallDetectionEnabled)
    }

    @Test
    fun `Just Lift params have correct settings`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 0,
            weightPerCableKg = 30f,
            isJustLift = true,
            useAutoStart = true,
            isAMRAP = true
        )

        assertTrue(params.isJustLift)
        assertTrue(params.useAutoStart)
        assertTrue(params.isAMRAP)
        assertEquals(0, params.reps)
    }

    @Test
    fun `Echo mode params work correctly`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Echo(EchoLevel.HARDEST, EccentricLoad.LOAD_120),
            reps = 8,
            selectedExerciseId = "squat-001"
        )

        val type = params.workoutType
        assertTrue(type is WorkoutType.Echo)
        assertEquals(EchoLevel.HARDEST, type.level)
        assertEquals(EccentricLoad.LOAD_120, type.eccentricLoad)
        assertEquals("squat-001", params.selectedExerciseId)
    }

    @Test
    fun `stopAtTop can be configured`() {
        val paramsBottom = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            stopAtTop = false
        )

        val paramsTop = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            stopAtTop = true
        )

        assertFalse(paramsBottom.stopAtTop)
        assertTrue(paramsTop.stopAtTop)
    }

    @Test
    fun `warmupReps can be customized`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            warmupReps = 5
        )

        assertEquals(5, params.warmupReps)
    }

    @Test
    fun `stall detection can be disabled`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            stallDetectionEnabled = false
        )

        assertFalse(params.stallDetectionEnabled)
    }

    @Test
    fun `last used and PR weights are stored`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            lastUsedWeightKg = 40f,
            prWeightKg = 50f
        )

        assertEquals(40f, params.lastUsedWeightKg)
        assertEquals(50f, params.prWeightKg)
    }

    @Test
    fun `progressionRegressionKg is stored`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            weightPerCableKg = 25f,
            progressionRegressionKg = 2.5f
        )

        assertEquals(2.5f, params.progressionRegressionKg)
    }
}
