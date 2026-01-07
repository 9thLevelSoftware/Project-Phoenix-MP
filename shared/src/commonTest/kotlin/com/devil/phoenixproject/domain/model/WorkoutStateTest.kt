package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkoutStateTest {

    // ========== ConnectionState Tests ==========

    @Test
    fun `ConnectionState Disconnected is singleton`() {
        val state1 = ConnectionState.Disconnected
        val state2 = ConnectionState.Disconnected
        assertEquals(state1, state2)
    }

    @Test
    fun `ConnectionState Scanning is singleton`() {
        val state1 = ConnectionState.Scanning
        val state2 = ConnectionState.Scanning
        assertEquals(state1, state2)
    }

    @Test
    fun `ConnectionState Connected stores device info`() {
        val state = ConnectionState.Connected(
            deviceName = "Vee_Test123",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            hardwareModel = VitruvianModel.VFormTrainer
        )

        assertIs<ConnectionState.Connected>(state)
        assertEquals("Vee_Test123", state.deviceName)
        assertEquals("AA:BB:CC:DD:EE:FF", state.deviceAddress)
        assertEquals(VitruvianModel.VFormTrainer, state.hardwareModel)
    }

    @Test
    fun `ConnectionState Connected defaults to Unknown hardware model`() {
        val state = ConnectionState.Connected(
            deviceName = "Vee_Test",
            deviceAddress = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals(VitruvianModel.Unknown, state.hardwareModel)
    }

    @Test
    fun `ConnectionState Error stores message and throwable`() {
        val exception = RuntimeException("Test exception")
        val state = ConnectionState.Error("Connection failed", exception)

        assertIs<ConnectionState.Error>(state)
        assertEquals("Connection failed", state.message)
        assertEquals(exception, state.throwable)
    }

    @Test
    fun `ConnectionState Error throwable is optional`() {
        val state = ConnectionState.Error("Connection failed")

        assertEquals(null, state.throwable)
    }

    // ========== WorkoutState Tests ==========

    @Test
    fun `WorkoutState Idle is singleton`() {
        assertEquals(WorkoutState.Idle, WorkoutState.Idle)
    }

    @Test
    fun `WorkoutState Countdown stores seconds remaining`() {
        val state = WorkoutState.Countdown(3)
        assertIs<WorkoutState.Countdown>(state)
        assertEquals(3, state.secondsRemaining)
    }

    @Test
    fun `WorkoutState SetSummary stores burnoutReps`() {
        val state = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakPower = 500f,
            averagePower = 300f,
            repCount = 15,
            warmupReps = 3,
            workingReps = 8,
            burnoutReps = 4,
            isEchoMode = true
        )

        assertEquals(4, state.burnoutReps)
    }

    @Test
    fun `WorkoutState SetSummary burnoutReps defaults to zero`() {
        val state = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakPower = 500f,
            averagePower = 300f,
            repCount = 10,
            warmupReps = 2,
            workingReps = 8,
            isEchoMode = true
        )

        assertEquals(0, state.burnoutReps)
    }

    @Test
    fun `WorkoutState Resting stores all properties`() {
        val state = WorkoutState.Resting(
            restSecondsRemaining = 60,
            nextExerciseName = "Squat",
            isLastExercise = false,
            currentSet = 2,
            totalSets = 4,
            isSupersetTransition = true,
            supersetLabel = "A1"
        )

        assertEquals(60, state.restSecondsRemaining)
        assertEquals("Squat", state.nextExerciseName)
        assertFalse(state.isLastExercise)
        assertEquals(2, state.currentSet)
        assertEquals(4, state.totalSets)
        assertTrue(state.isSupersetTransition)
        assertEquals("A1", state.supersetLabel)
    }

    // ========== ProgramMode Tests ==========

    @Test
    fun `ProgramMode has correct mode values`() {
        assertEquals(0, ProgramMode.OldSchool.modeValue)
        assertEquals(2, ProgramMode.Pump.modeValue)
        assertEquals(3, ProgramMode.TUT.modeValue)
        assertEquals(4, ProgramMode.TUTBeast.modeValue)
        assertEquals(6, ProgramMode.EccentricOnly.modeValue)
        assertEquals(10, ProgramMode.Echo.modeValue)
    }

    @Test
    fun `ProgramMode has correct display names`() {
        assertEquals("Old School", ProgramMode.OldSchool.displayName)
        assertEquals("Pump", ProgramMode.Pump.displayName)
        assertEquals("TUT", ProgramMode.TUT.displayName)
        assertEquals("TUT Beast", ProgramMode.TUTBeast.displayName)
        assertEquals("Eccentric Only", ProgramMode.EccentricOnly.displayName)
        assertEquals("Echo", ProgramMode.Echo.displayName)
    }

    @Test
    fun `ProgramMode fromValue returns correct modes`() {
        assertEquals(ProgramMode.OldSchool, ProgramMode.fromValue(0))
        assertEquals(ProgramMode.Pump, ProgramMode.fromValue(2))
        assertEquals(ProgramMode.TUT, ProgramMode.fromValue(3))
        assertEquals(ProgramMode.TUTBeast, ProgramMode.fromValue(4))
        assertEquals(ProgramMode.EccentricOnly, ProgramMode.fromValue(6))
        assertEquals(ProgramMode.Echo, ProgramMode.fromValue(10))
    }

    @Test
    fun `ProgramMode fromValue returns OldSchool for unknown values`() {
        assertEquals(ProgramMode.OldSchool, ProgramMode.fromValue(999))
        assertEquals(ProgramMode.OldSchool, ProgramMode.fromValue(-1))
    }

    // ========== EchoLevel Tests ==========

    @Test
    fun `EchoLevel has correct level values`() {
        assertEquals(0, EchoLevel.HARD.levelValue)
        assertEquals(1, EchoLevel.HARDER.levelValue)
        assertEquals(2, EchoLevel.HARDEST.levelValue)
        assertEquals(3, EchoLevel.EPIC.levelValue)
    }

    // ========== EccentricLoad Tests ==========

    @Test
    fun `EccentricLoad has correct percentages`() {
        assertEquals(0, EccentricLoad.LOAD_0.percentage)
        assertEquals(50, EccentricLoad.LOAD_50.percentage)
        assertEquals(75, EccentricLoad.LOAD_75.percentage)
        assertEquals(100, EccentricLoad.LOAD_100.percentage)
        assertEquals(110, EccentricLoad.LOAD_110.percentage)
        assertEquals(120, EccentricLoad.LOAD_120.percentage)
        assertEquals(130, EccentricLoad.LOAD_130.percentage)
        assertEquals(140, EccentricLoad.LOAD_140.percentage)
        assertEquals(150, EccentricLoad.LOAD_150.percentage)
    }

    // ========== VitruvianModel Tests ==========

    @Test
    fun `VitruvianModel has correct display names`() {
        assertEquals("V-Form Trainer", VitruvianModel.VFormTrainer.displayName)
        assertEquals("Trainer+", VitruvianModel.TrainerPlus.displayName)
        assertEquals("Unknown Vitruvian Device", VitruvianModel.Unknown.displayName)
    }
}
