package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Production composition: TV -> actual connection/session managers -> engine -> recording BLE. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSessionControllerTest {
    @Test
    fun connectionRequiresCurrentSelectionAndNeverStartsAWorkout() = runTest {
        val h = DWSMTestHarness(this)
        try {
            val devices = listOf(ScannedDevice("Vee_First", "first", -30), ScannedDevice("Vee_Selected", "selected", -60))
            val peripheral = object : BleRepository by h.fakeBleRepo {
                override val scannedDevices = MutableStateFlow(devices)
            }
            val connection = BleConnectionManager(peripheral, h.settingsManager, h.dwsm, h.coordinator.bleErrorEvents, h.workoutScope)
            val tv = TvSessionController(h.dwsm, connection, h.workoutScope, {})
            runCurrent()
            assertFalse(tv.connectSelectedTrainer("not-in-scan"))
            assertIs<ConnectionState.Disconnected>(h.fakeBleRepo.connectionState.value)
            assertTrue(tv.connectSelectedTrainer("selected"))
            runCurrent()
            assertEquals("selected", assertIs<ConnectionState.Connected>(h.fakeBleRepo.connectionState.value).deviceAddress)
            assertTrue(h.fakeBleRepo.programCommands.isEmpty())
        } finally { h.cleanup() }
    }

    @Test
    fun backgroundCancelsPreparationBeforeItCanApplyLoadAndForegroundDoesNotResume() = runTest {
        val h = DWSMTestHarness(this)
        val release = CompletableDeferred<Unit>()
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true))
            val tv = TvSessionController(h.dwsm, h.bleConnectionManager, h.workoutScope, { release.await() })
            tv.startJustLift()
            runCurrent()
            assertTrue(tv.preparing)
            tv.onBackground()
            release.complete(Unit)
            advanceUntilIdle()
            tv.onForeground()
            advanceTimeBy(15_000)
            runCurrent()
            assertFalse(tv.preparing)
            assertTrue(h.fakeBleRepo.programCommands.isEmpty())
            assertFalse(h.coordinator.workoutState.value is WorkoutState.Active)
        } finally { h.cleanup() }
    }

    @Test
    fun countdownPlayPauseCancelsPendingLoad() = runTest {
        val h = DWSMTestHarness(this)
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 8, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = false))
            val tv = controller(h)
            h.setActiveSummaryCountdownSeconds(0)
            runCurrent()
            // Just Lift and SetReady intentionally skip countdown in the shared core.
            // Exercise the remote seam against a real shared countdown, not a fabricated state.
            h.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertIs<WorkoutState.Countdown>(h.coordinator.workoutState.value)
            tv.handleRemote(TvRemoteInput.PLAY_PAUSE)
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(h.fakeBleRepo.programCommands.isEmpty())
            assertFalse(h.coordinator.workoutState.value is WorkoutState.Active)
            assertFalse(h.coordinator.workoutParameters.value.useAutoStart)
        } finally { h.cleanup() }
    }

    @Test
    fun backgroundDuringMachineConfigurationStopsAppliedLoadAndNeverResumes() = runTest {
        val h = DWSMTestHarness(this)
        val configured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.fakeBleRepo.afterWorkoutCommand = { configured.complete(Unit); release.await() }
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true))
            val tv = controller(h)
            assertTrue(tv.startJustLift())
            runCurrent()
            assertTrue(configured.isCompleted)
            assertIs<WorkoutState.Initializing>(h.coordinator.workoutState.value)
            tv.onBackground()
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertIs<MachineTeardownState.Ready>(h.dwsm.machineTeardownState.value)
            assertTrue(h.fakeBleRepo.stopWorkoutCallCount > 0)
            val commands = h.fakeBleRepo.programCommands.size
            tv.onForeground()
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(commands, h.fakeBleRepo.programCommands.size)
            assertFalse(h.coordinator.workoutState.value is WorkoutState.Active)
        } finally { release.complete(Unit); h.cleanup() }
    }

    @Test
    fun repeatedStopIsIdempotentAndHeldHandlesAndReconnectDoNotResume() = runTest {
        val h = DWSMTestHarness(this)
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true, warmupReps = 0))
            val tv = controller(h)
            tv.startJustLift()
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(h.coordinator.workoutState.value)
            val programs = h.fakeBleRepo.programCommands.size
            tv.stopSet()
            tv.stopSet()
            advanceUntilIdle()
            val stops = h.fakeBleRepo.stopWorkoutCallCount
            tv.stopSet()
            h.fakeBleRepo.setHandleState(HandleState.Grabbed)
            h.fakeBleRepo.simulateDisconnect()
            h.fakeBleRepo.simulateConnect("Vee_Test")
            tv.onForeground()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(stops, h.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(programs, h.fakeBleRepo.programCommands.size)
            assertFalse(h.coordinator.workoutState.value is WorkoutState.Active)
        } finally { h.cleanup() }
    }

    @Test
    fun staleTelemetryStopsThroughActualEngineAndBackWaitsForTeardown() = runTest {
        val h = DWSMTestHarness(this)
        val stop = CompletableDeferred<Unit>()
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true, warmupReps = 0))
            val admission = TvActivityAdmission()
            val tv = TvSessionController(h.dwsm, h.bleConnectionManager, h.workoutScope,
                admitTraining = admission::tryTraining, releaseTraining = admission::releaseTraining,
            ) { h.setActiveSummaryCountdownSeconds(0); h.settingsManager.autoplayEnabled.first { !it } }
            tv.startJustLift()
            advanceUntilIdle()
            h.fakeBleRepo.stopWorkoutBlock = { stop.await(); Result.success(Unit) }
            tv.checkTelemetry(0)
            tv.checkTelemetry(TV_TELEMETRY_TIMEOUT_MS)
            tv.handleRemote(TvRemoteInput.MENU)
            runCurrent()
            assertFalse(tv.exitConfirmationPending)
            assertFalse(tv.confirmExit())
            assertFalse(admission.tryAccountMutation(), "Pending physical stop still owns training")
            stop.complete(Unit)
            advanceUntilIdle()
            assertTrue(tv.exitConfirmationPending)
            assertFalse(admission.tryAccountMutation(), "Ready is not yet confirmed session exit")
            assertTrue(tv.confirmExit())
            advanceUntilIdle()
            assertFalse(tv.sessionOpen)
            assertTrue(admission.tryAccountMutation())
            admission.releaseAccountMutation()
        } finally { stop.complete(Unit); h.cleanup() }
    }

    @Test
    fun bodyweightRoutineLoadsOfflineAndUsesSharedManualRepCompletionWithoutMachineCommands() = runTest {
        val h = DWSMTestHarness(this)
        try {
            val tv = controller(h)
            val routine = Routine("bodyweight", "Pushups", exercises = listOf(
                RoutineExercise("set", Exercise(id = "bw", name = "Pushup", muscleGroup = "Chest", equipment = "BODYWEIGHT"),
                    0, setReps = listOf(8), weightPerCableKg = 0f),
            ))
            h.fakeExerciseRepo.addExercise(routine.exercises.single().exercise)
            assertTrue(tv.beginRoutine(routine))
            advanceUntilIdle()
            assertTrue(tv.startRoutineSet())
            runCurrent()
            assertIs<WorkoutState.Active>(h.coordinator.workoutState.value)
            tv.checkTelemetry(0)
            tv.checkTelemetry(TV_TELEMETRY_TIMEOUT_MS)
            advanceUntilIdle() // Existing untimed-bodyweight timer opens shared rep entry.
            val entry = assertIs<WorkoutState.BodyweightRepEntry>(h.coordinator.workoutState.value)
            assertTrue(h.fakeWorkoutRepo.allSessions().isEmpty())
            assertFalse(tv.confirmBodyweightReps(-1, 0))
            assertFalse(tv.confirmBodyweightReps(7, entry.variants.size))
            assertTrue(tv.confirmBodyweightReps(7, entry.variants.indexOf(entry.selectedVariant)))
            advanceUntilIdle()
            assertEquals(7, assertIs<WorkoutState.SetSummary>(h.coordinator.workoutState.value).repCount)
            assertEquals(7, h.fakeWorkoutRepo.allSessions().single().workingReps)
            assertTrue(tv.continueRoutine())
            advanceUntilIdle()
            assertIs<RoutineFlowState.Complete>(h.coordinator.routineFlowState.value)
            assertTrue(h.fakeBleRepo.programCommands.isEmpty())
            assertEquals(0, h.fakeBleRepo.stopWorkoutCallCount)
        } finally { h.cleanup() }
    }

    @Test
    fun malformedCompletionIsContainedAtRemoteBoundaryWithoutSaveOrFalseSummary() = runTest {
        val h = DWSMTestHarness(this)
        val reset = CompletableDeferred<Result<Unit>>()
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            h.dwsm.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool,
                reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true, warmupReps = 0))
            val tv = controller(h)
            assertTrue(tv.startJustLift())
            advanceUntilIdle()
            h.coordinator._repCount.value = RepCount(workingReps = -1)
            h.fakeBleRepo.stopWorkoutBlock = { reset.await() }
            tv.handleRemote(TvRemoteInput.PLAY_PAUSE)
            runCurrent()
            assertTrue(tv.error?.contains("not saved") == true)
            assertEquals(1, h.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(h.dwsm.machineTeardownState.value)
            assertFalse(tv.startJustLift())
            assertTrue(h.fakeWorkoutRepo.saveSessionAttempts.isEmpty())
            tv.requestExit()
            runCurrent()
            assertFalse(tv.exitConfirmationPending)
            reset.complete(Result.success(Unit))
            advanceUntilIdle()
            assertTrue(tv.exitConfirmationPending)
            assertTrue(tv.confirmExit())
            advanceUntilIdle()
            assertFalse(tv.sessionOpen)
            assertFalse(h.coordinator.workoutState.value is WorkoutState.SetSummary)
            assertTrue(h.fakeWorkoutRepo.saveSessionAttempts.isEmpty())
            assertEquals(1, h.fakeBleRepo.stopWorkoutCallCount)
        } finally { reset.complete(Result.success(Unit)); h.cleanup() }
    }

    @Test
    fun manualProfileViewFeedsRealSettingsWithoutChangingPersistedOrSyncedPreferences() = runTest {
        val h = DWSMTestHarness(this)
        try {
            val source = h.fakeUserProfileRepo
            val before = assertIs<ActiveProfileContext.Ready>(source.activeProfileContext.value)
            val view = TvManualStartProfileRepository(source, h.workoutScope)
            val settings = SettingsManager(h.fakePrefsManager, view, h.workoutScope)
            runCurrent()
            assertFalse(settings.autoplayEnabled.value)
            assertEquals(before, source.activeProfileContext.value)
            source.mutateWorkout(before.profile.id) {
                it.copy(summaryCountdownSeconds = 20, autoStartRoutine = true, motionStartEnabled = true)
            }
            runCurrent()
            assertFalse(settings.autoplayEnabled.value)
            assertFalse(settings.userPreferences.value.motionStartEnabled)
            assertFalse(settings.userPreferences.value.autoStartRoutine)
            assertEquals(20, assertIs<ActiveProfileContext.Ready>(source.activeProfileContext.value).preferences.workout.value.summaryCountdownSeconds)
        } finally { h.cleanup() }
    }

    @Test
    fun twoSetRoutineUsesSharedSummaryManualRestAdvanceAndNeverStartsWithoutSelect() = runTest {
        val h = DWSMTestHarness(this)
        try {
            h.fakeBleRepo.simulateConnect("Vee_Test")
            val tv = controller(h)
            val routine = Routine("tv-cable", "Two sets", exercises = listOf(
                RoutineExercise("set", Exercise(id = "cable", name = "Row", muscleGroup = "Back", equipment = "BAR"),
                    0, setReps = listOf(8, 8), weightPerCableKg = 20f, setRestSeconds = listOf(30, 30)),
            ))
            assertTrue(tv.beginRoutine(routine))
            advanceUntilIdle()
            assertIs<RoutineFlowState.SetReady>(h.coordinator.routineFlowState.value)
            assertTrue(h.fakeBleRepo.programCommands.isEmpty())
            assertTrue(tv.startRoutineSet())
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(h.coordinator.workoutState.value)
            h.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
            h.activeSessionEngine.handleSetCompletion(h.activeSessionEngine.currentExecutionLeaseForTest(), SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()
            assertIs<WorkoutState.SetSummary>(h.coordinator.workoutState.value)
            val programs = h.fakeBleRepo.programCommands.size
            assertTrue(tv.continueRoutine())
            runCurrent()
            // The real core's autoplay-off path consumes the rest plan into SetReady
            // immediately and can retain the old SetSummary. TV must expose manual
            // rest + the new step rather than strand the user on Continue Routine.
            assertEquals(1, assertIs<RoutineFlowState.SetReady>(h.coordinator.routineFlowState.value).setIndex)
            assertTrue(tv.routineReadyToStart)
            assertEquals(30, tv.manualRoutineRestSeconds)
            assertFalse(tv.continueRoutine(), "Repeated Continue must not advance another set.")
            advanceTimeBy(35_000)
            runCurrent()
            assertEquals(programs, h.fakeBleRepo.programCommands.size)
            assertFalse(h.coordinator.workoutState.value is WorkoutState.Active)
            assertTrue(tv.routineReadyToStart)
            assertTrue(tv.startRoutineSet())
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(h.coordinator.workoutState.value)
            h.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
            h.activeSessionEngine.handleSetCompletion(h.activeSessionEngine.currentExecutionLeaseForTest(), SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()
            assertTrue(tv.continueRoutine())
            advanceUntilIdle()
            if (h.coordinator.workoutState.value is WorkoutState.Resting) { tv.continueRoutine(); advanceUntilIdle() }
            assertIs<RoutineFlowState.Complete>(h.coordinator.routineFlowState.value)
            assertEquals(2, h.fakeWorkoutRepo.getAllSessions("default").first().size)
        } finally { h.cleanup() }
    }

    @Test
    fun failedOrCancelledPreparationWithoutCoreSessionReleasesReservation() = runTest {
        for (mode in listOf("failure", "cancel", "cancel-before-launch")) {
            val h = DWSMTestHarness(this)
            val admission = TvActivityAdmission()
            val release = CompletableDeferred<Unit>()
            try {
                val tv = TvSessionController(h.dwsm, h.bleConnectionManager, h.workoutScope,
                    admitTraining = admission::tryTraining, releaseTraining = admission::releaseTraining,
                ) {
                    if (mode == "failure") error("settings unavailable") else release.await()
                }
                assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
                assertFalse(admission.tryAccountMutation())
                if (mode == "cancel-before-launch") tv.onBackground()
                runCurrent()
                if (mode == "cancel") tv.onBackground()
                runCurrent()
                assertFalse(tv.sessionOpen)
                assertTrue(admission.tryAccountMutation())
                admission.releaseAccountMutation()
                assertTrue(h.fakeBleRepo.programCommands.isEmpty())
            } finally { release.complete(Unit); h.cleanup() }
        }
    }

    @Test
    fun routineSetCannotBypassAnAccountReservationEvenWithCoreSetReady() = runTest {
        val h = DWSMTestHarness(this)
        val admission = TvActivityAdmission()
        try {
            h.setActiveSummaryCountdownSeconds(0)
            assertTrue(h.dwsm.loadRoutineAsync(tvBodyweightRoutine()))
            h.dwsm.enterSetReady(0, 0)
            val tv = TvSessionController(h.dwsm, h.bleConnectionManager, h.workoutScope,
                admitTraining = admission::tryTraining, releaseTraining = admission::releaseTraining,
            ) {}
            runCurrent()
            assertTrue(tv.routineReadyToStart)
            assertTrue(admission.tryAccountMutation())
            assertFalse(tv.startRoutineSet())
            admission.releaseAccountMutation()
            assertTrue(tv.startRoutineSet())
        } finally { h.cleanup() }
    }

    private fun controller(h: DWSMTestHarness): TvSessionController {
        val admission = TvActivityAdmission()
        return TvSessionController(
            h.dwsm, h.bleConnectionManager, h.workoutScope,
            admitTraining = admission::tryTraining,
            releaseTraining = admission::releaseTraining,
            prepareManualStarts = {
                h.setActiveSummaryCountdownSeconds(0)
                h.settingsManager.autoplayEnabled.first { !it }
            },
        )
    }
}
