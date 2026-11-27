package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.presentation.manager.ExerciseCountdownCuePolicy
import com.devil.phoenixproject.presentation.manager.isFinalWorkingRep
import com.devil.phoenixproject.presentation.manager.warmupRepCueEvent
import com.devil.phoenixproject.presentation.manager.workingRepCueEvents
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #100: Tests for audio feedback improvements including FINAL_REP event,
 * sound selection, and preference gating logic.
 *
 * These tests verify:
 * 1. FINAL_REP event exists and is distinct from REP_COMPLETED
 * 2. RepCounterFromMachine emits correct event types at final rep
 * 3. Preference gating for warmup, working, and countdown events
 * 4. COUNTDOWN_TICK equality semantics
 */
class HapticEventAudioTest {

    @Test
    fun `all HapticEvent singleton types are distinct`() {
        val singletons: List<HapticEvent> = listOf(
            HapticEvent.REP_COMPLETED,
            HapticEvent.FINAL_REP,
            HapticEvent.WARMUP_COMPLETE,
            HapticEvent.WORKOUT_COMPLETE,
            HapticEvent.WORKOUT_START,
            HapticEvent.WORKOUT_END,
            HapticEvent.REST_ENDING,
            HapticEvent.ERROR,
            HapticEvent.DISCO_MODE_UNLOCKED,
            HapticEvent.BADGE_EARNED,
            HapticEvent.PERSONAL_RECORD,
            HapticEvent.VELOCITY_THRESHOLD_REACHED,
            HapticEvent.WARMUP_TO_WORKING,
            HapticEvent.AUTO_START_READY,
        )
        // Each pair should be distinct
        for (i in singletons.indices) {
            for (j in singletons.indices) {
                if (i != j) {
                    assertNotEquals(
                        singletons[i],
                        singletons[j],
                        "Expected ${singletons[i]} != ${singletons[j]}",
                    )
                }
            }
        }
    }

    // ========== COUNTDOWN_TICK Parameterized Tests ==========

    @Test
    fun `COUNTDOWN_TICK events with same seconds are equal`() {
        assertEquals(HapticEvent.COUNTDOWN_TICK(10), HapticEvent.COUNTDOWN_TICK(10))
        assertEquals(HapticEvent.COUNTDOWN_TICK(1), HapticEvent.COUNTDOWN_TICK(1))
    }

    @Test
    fun `COUNTDOWN_TICK events with different seconds are not equal`() {
        assertNotEquals(HapticEvent.COUNTDOWN_TICK(10), HapticEvent.COUNTDOWN_TICK(5))
    }

    @Test
    fun `COUNTDOWN_TICK is not equal to REP_COMPLETED`() {
        assertNotEquals(HapticEvent.COUNTDOWN_TICK(5) as HapticEvent, HapticEvent.REP_COMPLETED as HapticEvent)
    }
}

/**
 * Issue #100: Final rep detection logic tests against production [isFinalWorkingRep].
 */
class FinalRepDetectionLogicTest {

    private fun params(
        isJustLift: Boolean = false,
        isAMRAP: Boolean = false,
        reps: Int = 10,
    ) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = reps,
        isJustLift = isJustLift,
        isAMRAP = isAMRAP,
    )

    @Test
    fun `isFinalWorkingRep matches expected boundary cases`() {
        val cases = listOf(
            Triple(params(reps = 10), 10, true),
            Triple(params(reps = 10), 11, true),
            Triple(params(reps = 10), 5, false),
            Triple(params(isJustLift = true, reps = 10), 10, false),
            Triple(params(isAMRAP = true, reps = 10), 10, false),
            Triple(params(reps = 0), 5, false),
            Triple(params(reps = 10), 1, false),
            Triple(params(reps = 1), 1, true),
        )
        for ((workoutParams, repNumber, expected) in cases) {
            assertEquals(
                expected,
                isFinalWorkingRep(workoutParams, repNumber),
                "params=$workoutParams, repNumber=$repNumber",
            )
        }
    }
}

/**
 * Issue #100: Production cue policy & ActiveSessionEngine audio preference gate tests.
 */
class AudioPreferenceGateTest {

    private fun params(
        isJustLift: Boolean = false,
        isAMRAP: Boolean = false,
        reps: Int = 10,
    ) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = reps,
        isJustLift = isJustLift,
        isAMRAP = isAMRAP,
    )

    private data class WorkingCueCase(
        val audioRepCountEnabled: Boolean,
        val repSoundEnabled: Boolean,
        val targetReps: Int,
        val repNumber: Int,
        val expected: List<HapticEvent>,
    )

    @Test
    fun `workingRepCueEvents respects audio preferences and final rep and above-25 fallback`() {
        val cases = listOf(
            WorkingCueCase(
                audioRepCountEnabled = true,
                repSoundEnabled = true,
                targetReps = 10,
                repNumber = 10,
                expected = listOf(HapticEvent.REP_COUNT_ANNOUNCED(10), HapticEvent.FINAL_REP),
            ),
            WorkingCueCase(
                audioRepCountEnabled = false,
                repSoundEnabled = true,
                targetReps = 10,
                repNumber = 10,
                expected = listOf(HapticEvent.FINAL_REP),
            ),
            WorkingCueCase(
                audioRepCountEnabled = false,
                repSoundEnabled = true,
                targetReps = 10,
                repNumber = 5,
                expected = listOf(HapticEvent.REP_COMPLETED),
            ),
            WorkingCueCase(
                audioRepCountEnabled = false,
                repSoundEnabled = false,
                targetReps = 10,
                repNumber = 5,
                expected = emptyList(),
            ),
            WorkingCueCase(
                audioRepCountEnabled = false,
                repSoundEnabled = false,
                targetReps = 10,
                repNumber = 10,
                expected = emptyList(),
            ),
            WorkingCueCase(
                audioRepCountEnabled = true,
                repSoundEnabled = false,
                targetReps = 10,
                repNumber = 5,
                expected = listOf(HapticEvent.REP_COUNT_ANNOUNCED(5)),
            ),
            WorkingCueCase(
                audioRepCountEnabled = true,
                repSoundEnabled = true,
                targetReps = 10,
                repNumber = 5,
                expected = listOf(HapticEvent.REP_COUNT_ANNOUNCED(5)),
            ),
            WorkingCueCase(
                audioRepCountEnabled = true,
                repSoundEnabled = true,
                targetReps = 40,
                repNumber = 30,
                expected = listOf(HapticEvent.REP_COMPLETED),
            ),
            WorkingCueCase(
                audioRepCountEnabled = true,
                repSoundEnabled = true,
                targetReps = 30,
                repNumber = 30,
                expected = listOf(HapticEvent.FINAL_REP),
            ),
        )
        for (case in cases) {
            assertEquals(
                case.expected,
                workingRepCueEvents(
                    prefs = UserPreferences(
                        audioRepCountEnabled = case.audioRepCountEnabled,
                        repSoundEnabled = case.repSoundEnabled,
                    ),
                    params = params(reps = case.targetReps),
                    repNumber = case.repNumber,
                ),
                "case=$case",
            )
        }
    }

    @Test
    fun `warmup rep gated by repSoundEnabled`() {
        assertEquals(
            HapticEvent.REP_COMPLETED,
            warmupRepCueEvent(UserPreferences(repSoundEnabled = true)),
        )
        assertNull(warmupRepCueEvent(UserPreferences(repSoundEnabled = false)))
    }

    @Test
    fun `countdown tick gated by ExerciseCountdownCuePolicy`() {
        val cases = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        )
        for ((beepsEnabled, countdownBeepsEnabled, expected) in cases) {
            assertEquals(
                expected,
                ExerciseCountdownCuePolicy.shouldEmitTick(
                    remainingSeconds = 5,
                    isPaused = false,
                    lastTickedSecond = 6,
                    beepsEnabled = beepsEnabled,
                    countdownBeepsEnabled = countdownBeepsEnabled,
                ),
                "beepsEnabled=$beepsEnabled, countdownBeepsEnabled=$countdownBeepsEnabled",
            )
        }
    }

    @Test
    fun `REST_ENDING countdown warning gated by ExerciseCountdownCuePolicy`() {
        val cases = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        )
        for ((beepsEnabled, countdownBeepsEnabled, expected) in cases) {
            assertEquals(
                expected,
                ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                    remainingSeconds = ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS,
                    isPaused = false,
                    restEndingEmitted = false,
                    beepsEnabled = beepsEnabled,
                    countdownBeepsEnabled = countdownBeepsEnabled,
                ),
                "beepsEnabled=$beepsEnabled, countdownBeepsEnabled=$countdownBeepsEnabled",
            )
        }
    }

    @Test
    fun `ActiveSessionEngine emits dual cue on final rep and falls back above 25 and gates warmup and respects AMRAP`() = runTest {
        val harness = DWSMTestHarness(this)
        val events = mutableListOf<HapticEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(events)
        }

        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
            harness.startCableSet(targetReps = 10)
            harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(
                repCountTiming = RepCountTiming.BOTTOM,
                reps = 10,
                isJustLift = false,
                isAMRAP = false,
            )
            harness.setActiveProfilePreferences(
                UserPreferences(audioRepCountEnabled = true, repSoundEnabled = true),
            )
            advanceUntilIdle()

            // Final rep emits BOTH REP_COUNT_ANNOUNCED and FINAL_REP
            events.clear()
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WORKING_COMPLETED, warmupCount = 0, workingCount = 10),
            )
            advanceUntilIdle()
            assertEquals(
                listOf(HapticEvent.REP_COUNT_ANNOUNCED(10), HapticEvent.FINAL_REP),
                events,
            )

            // Rep > 25 falls back to REP_COMPLETED when not final
            harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(reps = 40)
            events.clear()
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WORKING_COMPLETED, warmupCount = 0, workingCount = 30),
            )
            advanceUntilIdle()
            assertEquals(listOf<HapticEvent>(HapticEvent.REP_COMPLETED), events)

            // Warmup gated by repSoundEnabled
            events.clear()
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WARMUP_COMPLETED, warmupCount = 1, workingCount = 0),
            )
            advanceUntilIdle()
            assertEquals(listOf<HapticEvent>(HapticEvent.REP_COMPLETED), events)

            harness.setActiveProfilePreferences(
                UserPreferences(audioRepCountEnabled = true, repSoundEnabled = false),
            )
            advanceUntilIdle()
            events.clear()
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WARMUP_COMPLETED, warmupCount = 2, workingCount = 0),
            )
            advanceUntilIdle()
            assertTrue(events.isEmpty())

            // AMRAP never emits FINAL_REP even when workingCount >= reps
            harness.setActiveProfilePreferences(
                UserPreferences(audioRepCountEnabled = false, repSoundEnabled = true),
            )
            harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(
                reps = 10,
                isAMRAP = true,
            )
            advanceUntilIdle()
            events.clear()
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WORKING_COMPLETED, warmupCount = 0, workingCount = 10),
            )
            advanceUntilIdle()
            assertEquals(listOf<HapticEvent>(HapticEvent.REP_COMPLETED), events)
        } finally {
            collector.cancel()
            harness.cleanup()
        }
    }
}
