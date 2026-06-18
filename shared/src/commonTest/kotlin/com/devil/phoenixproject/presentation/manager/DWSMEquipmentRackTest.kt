package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class DWSMEquipmentRackTest {
    @Test
    fun `selected counterweight adjusts non Echo set start packet without changing programmed weight`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT)),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )
        harness.dwsm.updateActiveRackSelection(listOf("assist"))

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val command = harness.fakeBleRepo.commandsReceived.single()
        assertEquals(30f, readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        assertEquals(40f, harness.dwsm.coordinator.workoutParameters.value.weightPerCableKg)
        harness.cleanup()
    }

    @Test
    fun `runtime behavior override adjusts non Echo set start packet`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE)),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )
        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        harness.dwsm.updateActiveRackBehaviorOverrides(
            mapOf("vest" to RackItemBehavior.COUNTERWEIGHT),
        )

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val command = harness.fakeBleRepo.commandsReceived.single()
        assertEquals(30f, readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        assertEquals(40f, harness.dwsm.coordinator.workoutParameters.value.weightPerCableKg)
        assertEquals(
            mapOf("vest" to RackItemBehavior.COUNTERWEIGHT),
            harness.dwsm.coordinator.activeRackBehaviorOverrides.value,
        )
        harness.cleanup()
    }

    @Test
    fun `active rack edits during active set do not send mid set command and saved session uses set start snapshot`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(
                rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT),
                rackItem("vest", 20f, RackItemBehavior.ADDED_RESISTANCE),
            ),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )
        harness.dwsm.updateActiveRackSelection(listOf("assist"))
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        val commandsAfterStart = harness.fakeBleRepo.commandsReceived.size

        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        advanceUntilIdle()

        assertEquals(commandsAfterStart, harness.fakeBleRepo.commandsReceived.size)

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 40f,
                loadB = 40f,
                positionA = 100f,
                positionB = 100f,
                velocityA = 50.0,
                velocityB = 50.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(40f, session.weightPerCableKg)
        assertEquals(0f, session.externalAddedLoadKg)
        assertEquals(10f, session.counterweightKg)
        assertTrue(session.rackItemsJson.contains("assist"))
        assertTrue(!session.rackItemsJson.contains("vest"))
        harness.cleanup()
    }

    @Test
    fun `no rack workout sends programmed packet unchanged`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val command = harness.fakeBleRepo.commandsReceived.single()
        assertEquals(40f, readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        harness.cleanup()
    }

    @Test
    fun `routine set ready applies each exercise rack defaults and allows per set override`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(
                rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE),
                rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT),
            ),
        )
        val routine = Routine(
            id = "routine-rack-defaults",
            name = "Rack Defaults",
            exercises = listOf(
                routineExercise("rex-1", "Bench Press", listOf("vest")),
                routineExercise("rex-2", "Pull Up", listOf("assist")),
            ),
        )

        assertTrue(harness.dwsm.loadRoutineAsync(routine))
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)
        assertEquals(listOf("vest"), harness.dwsm.coordinator.activeRackItemIds.value)

        harness.dwsm.updateActiveRackSelection(listOf("assist"))
        assertEquals(listOf("assist"), harness.dwsm.coordinator.activeRackItemIds.value)

        harness.dwsm.enterSetReady(1, 0)
        assertEquals(listOf("assist"), harness.dwsm.coordinator.activeRackItemIds.value)
        harness.cleanup()
    }

    /**
     * Issue #536 regression guard: a Weighted Vest selected on exercise 0 via the SetReady
     * mid-flow toggle must not leak into exercise 1's live HUD rack snapshot when the user
     * advances via [RoutineFlowManager.jumpToExercise] / [RoutineFlowManager.advanceToNextExercise]
     * (the [RoutineFlowManager.navigateToExerciseInternal] path, which is distinct from the
     * `enterSetReady` path covered by the test above).
     *
     * The vest is added to the previous exercise's [WorkoutParameters.externalAddedLoadKg] /
     * [WorkoutParameters.counterweightKg] / [WorkoutCoordinator._activeRackItemIds]; without
     * a reset on the per-exercise-advance transition, the next exercise's
     * [com.devil.phoenixproject.presentation.manager.ActiveSessionEngine.captureRackLoadSnapshot]
     * resolves the leaked selection and the live HUD renders "Rack: <leaked item> +N lb".
     */
    @Test
    fun `jumpToExercise clears rack selection so weighted vest does not leak to next exercise`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(
                rackItem("vest", 3.63f, RackItemBehavior.ADDED_RESISTANCE),
                rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT),
            ),
        )
        val routine = Routine(
            id = "routine-issue-536",
            name = "Vest Leak Repro",
            exercises = listOf(
                // Exercise 0 has NO schema-level rack defaults — only the mid-flow toggle
                // adds the vest. After advancing, exercise 1's defaults ([]) should win.
                routineExercise("rex-1", "Bayesian Cable Row", emptyList()),
                routineExercise("rex-2", "Bayesian Cable Curl", emptyList()),
            ),
        )

        assertTrue(harness.dwsm.loadRoutineAsync(routine))
        advanceUntilIdle()

        // SetReady for exercise 0, then user mid-flow toggles a vest (the leak source).
        harness.dwsm.enterSetReady(0, 0)
        assertEquals(emptyList(), harness.dwsm.coordinator.activeRackItemIds.value)
        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        assertEquals(listOf("vest"), harness.dwsm.coordinator.activeRackItemIds.value)
        // For a non-bodyweight (cable) exercise the mid-flow toggle updates
        // activeRackItemIds but leaves the load adjustment locked until the next
        // captureRackLoadSnapshot (see ActiveSessionEngine.updateActiveRackSelection).
        // The leak test below targets the rack-ids + workoutParameters.activeRackItemIds
        // round-trip that the live HUD reads; the precomputed-load invariant is covered
        // by the existing "set start snapshot" test.

        // Advance to exercise 1 via the jumpToExercise / navigateToExerciseInternal path.
        // Pre-fix: the previous selection leaked, and the live HUD rendered
        // "Rack: Weighted Vest +8 lb" on exercise 1.
        harness.routineFlowManager.jumpToExercise(1)
        // jumpToExercise launches the rack reset inside its own coroutine
        // (lifecycleDelegate.sendStopCommand + navigateToExerciseInternal); advance
        // virtual time so the state writes land before we assert.
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            harness.dwsm.coordinator.activeRackItemIds.value,
        )
        assertEquals(
            0f,
            harness.dwsm.coordinator._workoutParameters.value.externalAddedLoadKg,
        )
        assertEquals(
            0f,
            harness.dwsm.coordinator._workoutParameters.value.counterweightKg,
        )
        assertEquals(
            emptyList(),
            harness.dwsm.coordinator._workoutParameters.value.activeRackItemIds,
        )
        harness.cleanup()
    }

    /**
     * Issue #536 regression guard (autoplay path): when autoplay advances across exercises
     * via [ActiveSessionEngine.startNextSetOrExercise], rack selection must be re-seeded
     * just like [RoutineFlowManager.enterSetReady] and [RoutineFlowManager.jumpToExercise].
     */
    @Test
    fun `autoplay startNextSet clears rack selection so weighted vest does not leak to next exercise`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(
                rackItem("vest", 3.63f, RackItemBehavior.ADDED_RESISTANCE),
                rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT),
            ),
        )
        harness.fakePrefsManager.setSummaryCountdownSeconds(10)
        val routine = Routine(
            id = "routine-issue-536-autoplay",
            name = "Vest Leak Autoplay Repro",
            exercises = listOf(
                routineExercise("rex-1", "Bayesian Cable Row", emptyList()),
                routineExercise("rex-2", "Bayesian Cable Curl", emptyList()),
            ),
        )

        assertTrue(harness.dwsm.loadRoutineAsync(routine))
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        assertEquals(listOf("vest"), harness.dwsm.coordinator.activeRackItemIds.value)

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = routine.exercises[1].exercise.displayName,
            isLastExercise = false,
            currentSet = 1,
            totalSets = 1,
        )
        harness.dwsm.startNextSet()

        assertEquals(emptyList(), harness.dwsm.coordinator.activeRackItemIds.value)
        assertEquals(0f, harness.dwsm.coordinator._workoutParameters.value.externalAddedLoadKg)
        assertEquals(0f, harness.dwsm.coordinator._workoutParameters.value.counterweightKg)
        assertEquals(emptyList(), harness.dwsm.coordinator._workoutParameters.value.activeRackItemIds)
        harness.cleanup()
    }

    @Test
    fun `single exercise completion persists rack defaults`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE)),
        )
        val exerciseId = "single-rack-exercise"
        val routine = Routine(
            id = "${DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX}rack-defaults",
            name = "Single Exercise",
            exercises = listOf(routineExercise("single-rex", "Single Cable Row", listOf("vest"), exerciseId = exerciseId)),
        )

        assertTrue(harness.dwsm.loadRoutineAsync(routine))
        advanceUntilIdle()
        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 40f,
                loadB = 40f,
                positionA = 100f,
                positionB = 100f,
                velocityA = 50.0,
                velocityB = 50.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        val defaults = harness.fakePrefsManager.getSingleExerciseDefaults(exerciseId)
        assertEquals(listOf("vest"), defaults?.defaultRackItemIds)
        harness.cleanup()
    }

    private fun rackItem(id: String, weightKg: Float, behavior: RackItemBehavior): RackItem = RackItem(
        id = id,
        name = id,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = behavior,
    )

    private fun routineExercise(
        id: String,
        name: String,
        defaultRackItemIds: List<String>,
        exerciseId: String = id,
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = com.devil.phoenixproject.domain.model.Exercise(
            id = exerciseId,
            name = name,
            muscleGroup = "Back",
            muscleGroups = "Back",
            equipment = "Cable",
        ),
        orderIndex = if (id.endsWith("2")) 1 else 0,
        setReps = listOf(8),
        weightPerCableKg = 40f,
        programMode = ProgramMode.OldSchool,
        defaultRackItemIds = defaultRackItemIds,
    )

    private fun readFloatLE(packet: ByteArray, offset: Int): Float {
        val bits = (packet[offset].toInt() and 0xFF) or
            ((packet[offset + 1].toInt() and 0xFF) shl 8) or
            ((packet[offset + 2].toInt() and 0xFF) shl 16) or
            ((packet[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }
}
