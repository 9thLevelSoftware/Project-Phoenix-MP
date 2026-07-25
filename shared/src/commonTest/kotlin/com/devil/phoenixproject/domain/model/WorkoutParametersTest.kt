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
            programMode = ProgramMode.OldSchool,
            reps = 10,
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
            programMode = ProgramMode.OldSchool,
            reps = 0,
            weightPerCableKg = 30f,
            isJustLift = true,
            useAutoStart = true,
            isAMRAP = true,
        )

        assertTrue(params.isJustLift)
        assertTrue(params.useAutoStart)
        assertTrue(params.isAMRAP)
        assertEquals(0, params.reps)
    }

    @Test
    fun `Echo mode params work correctly`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 8,
            selectedExerciseId = "squat-001",
            echoLevel = EchoLevel.HARDEST,
            eccentricLoad = EccentricLoad.LOAD_120,
        )

        assertEquals(ProgramMode.Echo, params.programMode)
        assertEquals(EchoLevel.HARDEST, params.echoLevel)
        assertEquals(EccentricLoad.LOAD_120, params.eccentricLoad)
        assertEquals("squat-001", params.selectedExerciseId)
    }

    @Test
    fun `Echo mode defaults use issue 553 Echo level`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 8,
        )

        assertEquals(EchoLevel.HARDER, params.echoLevel)
        assertEquals(WorkoutMode.Echo(EchoLevel.HARDER), ProgramMode.Echo.toWorkoutMode())
    }

    @Test
    fun `stopAtTop can be configured`() {
        val paramsBottom = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            stopAtTop = false,
        )

        val paramsTop = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            stopAtTop = true,
        )

        assertFalse(paramsBottom.stopAtTop)
        assertTrue(paramsTop.stopAtTop)
    }

    @Test
    fun `warmupReps can be customized`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            warmupReps = 5,
        )

        assertEquals(5, params.warmupReps)
    }

    @Test
    fun `stall detection can be disabled`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            stallDetectionEnabled = false,
        )

        assertFalse(params.stallDetectionEnabled)
    }

    @Test
    fun `last used and PR weights are stored`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            lastUsedWeightKg = 40f,
            prWeightKg = 50f,
        )

        assertEquals(40f, params.lastUsedWeightKg)
        assertEquals(50f, params.prWeightKg)
    }

    @Test
    fun `progressionRegressionKg is stored`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 25f,
            progressionRegressionKg = 2.5f,
        )

        assertEquals(2.5f, params.progressionRegressionKg)
    }

    // Issue #674: Old School eccentric overload tests

    @Test
    fun `hasEccentricOverload true for Old School with LOAD_130`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            eccentricLoad = EccentricLoad.LOAD_130,
        )

        assertTrue(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Old School with LOAD_100`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            eccentricLoad = EccentricLoad.LOAD_100,
        )

        assertFalse(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Echo with LOAD_100`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 10,
            eccentricLoad = EccentricLoad.LOAD_100,
        )

        assertFalse(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Echo with LOAD_130`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 10,
            eccentricLoad = EccentricLoad.LOAD_130,
        )

        // Echo has its own overload mechanism (isEchoMode); hasEccentricOverload is OldSchool-only
        assertFalse(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Pump with LOAD_130 after mode switch`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            eccentricLoad = EccentricLoad.LOAD_130,
        )

        // Mode switch from Old School+130% to Pump must NOT trigger 0x4E dispatch
        assertFalse(params.hasEccentricOverload)
    }
}
