package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.presentation.manager.ExerciseCountdownCuePolicy
import com.devil.phoenixproject.presentation.manager.isFinalWorkingRep
import com.devil.phoenixproject.presentation.manager.warmupRepCueEvent
import com.devil.phoenixproject.presentation.manager.workingRepCueEvents
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ========== FINAL_REP Event Tests ==========

    @Test
    fun `FINAL_REP is a distinct HapticEvent singleton`() {
        val event: HapticEvent = HapticEvent.FINAL_REP
        assertEquals(HapticEvent.FINAL_REP, event)
    }

    @Test
    fun `FINAL_REP is not equal to REP_COMPLETED`() {
        assertNotEquals(HapticEvent.FINAL_REP as HapticEvent, HapticEvent.REP_COMPLETED as HapticEvent)
    }

    @Test
    fun `FINAL_REP is not equal to WORKOUT_COMPLETE`() {
        assertNotEquals(HapticEvent.FINAL_REP as HapticEvent, HapticEvent.WORKOUT_COMPLETE as HapticEvent)
    }

    @Test
    fun `FINAL_REP has consistent hashCode`() {
        assertEquals(HapticEvent.FINAL_REP.hashCode(), HapticEvent.FINAL_REP.hashCode())
    }

    @Test
    fun `FINAL_REP can be used as map key`() {
        val soundMap = mapOf<HapticEvent, String>(
            HapticEvent.REP_COMPLETED to "rep_complete_strong",
            HapticEvent.FINAL_REP to "boopbeepbeep",
            HapticEvent.WORKOUT_COMPLETE to "boopbeepbeep",
        )
        assertEquals("boopbeepbeep", soundMap[HapticEvent.FINAL_REP])
        assertEquals("rep_complete_strong", soundMap[HapticEvent.REP_COMPLETED])
        assertNotEquals(soundMap[HapticEvent.FINAL_REP], soundMap[HapticEvent.REP_COMPLETED])
    }

    // ========== Issue #1356: AUTO_START_READY Event Tests ==========

    @Test
    fun `AUTO_START_READY has stable equality and hashCode`() {
        assertEquals(HapticEvent.AUTO_START_READY, HapticEvent.AUTO_START_READY)
        assertEquals(HapticEvent.AUTO_START_READY.hashCode(), HapticEvent.AUTO_START_READY.hashCode())
    }

    @Test
    fun `AUTO_START_READY is distinct from WORKOUT_START and COUNTDOWN_TICK`() {
        assertNotEquals(HapticEvent.AUTO_START_READY as HapticEvent, HapticEvent.WORKOUT_START as HapticEvent)
        assertNotEquals(HapticEvent.AUTO_START_READY as HapticEvent, HapticEvent.COUNTDOWN_TICK(5) as HapticEvent)
    }

    @Test
    fun `AUTO_START_READY can be used as map key`() {
        val soundMap = mapOf<HapticEvent, String>(
            HapticEvent.WORKOUT_START to "chirpchirp",
            HapticEvent.AUTO_START_READY to "auto_start_ready",
        )
        assertEquals("auto_start_ready", soundMap[HapticEvent.AUTO_START_READY])
        assertEquals("chirpchirp", soundMap[HapticEvent.WORKOUT_START])
        assertNotEquals(soundMap[HapticEvent.AUTO_START_READY], soundMap[HapticEvent.WORKOUT_START])
    }

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
    fun `final rep detected when currentRep equals targetReps`() {
        assertTrue(isFinalWorkingRep(params(reps = 10), repNumber = 10))
    }

    @Test
    fun `final rep detected when currentRep exceeds targetReps`() {
        assertTrue(isFinalWorkingRep(params(reps = 10), repNumber = 11))
    }

    @Test
    fun `non-final rep when currentRep less than targetReps`() {
        assertFalse(isFinalWorkingRep(params(reps = 10), repNumber = 5))
    }

    @Test
    fun `not final rep in Just Lift mode even at target`() {
        assertFalse(isFinalWorkingRep(params(isJustLift = true, reps = 10), repNumber = 10))
    }

    @Test
    fun `not final rep in AMRAP mode even at target`() {
        assertFalse(isFinalWorkingRep(params(isAMRAP = true, reps = 10), repNumber = 10))
    }

    @Test
    fun `not final rep when targetReps is zero`() {
        assertFalse(isFinalWorkingRep(params(reps = 0), repNumber = 5))
    }

    @Test
    fun `first rep is not final when target is more than 1`() {
        assertFalse(isFinalWorkingRep(params(reps = 10), repNumber = 1))
    }

    @Test
    fun `single rep set - rep 1 is final when target is 1`() {
        assertTrue(isFinalWorkingRep(params(reps = 1), repNumber = 1))
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

    @Test
    fun `final rep with audioRepCount emits BOTH announced AND FINAL_REP`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = true, repSoundEnabled = true),
            params = params(reps = 10),
            repNumber = 10,
        )
        assertEquals(
            listOf(HapticEvent.REP_COUNT_ANNOUNCED(10), HapticEvent.FINAL_REP),
            events,
        )
    }

    @Test
    fun `FINAL_REP emitted when audio rep count disabled and is final rep`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = false, repSoundEnabled = true),
            params = params(reps = 10),
            repNumber = 10,
        )
        assertEquals(listOf(HapticEvent.FINAL_REP), events)
    }

    @Test
    fun `REP_COMPLETED emitted for non-final rep when audio rep count disabled`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = false, repSoundEnabled = true),
            params = params(reps = 10),
            repNumber = 5,
        )
        assertEquals(listOf(HapticEvent.REP_COMPLETED), events)
    }

    @Test
    fun `no event emitted when repSoundEnabled is false and audioRepCount disabled`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = false, repSoundEnabled = false),
            params = params(reps = 10),
            repNumber = 5,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `no event emitted when repSoundEnabled is false for final rep`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = false, repSoundEnabled = false),
            params = params(reps = 10),
            repNumber = 10,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `audio rep count still works when repSoundEnabled is false`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = true, repSoundEnabled = false),
            params = params(reps = 10),
            repNumber = 5,
        )
        assertEquals(listOf(HapticEvent.REP_COUNT_ANNOUNCED(5)), events)
    }

    @Test
    fun `non-final rep with audioRepCount emits only announced`() {
        val events = workingRepCueEvents(
            prefs = UserPreferences(audioRepCountEnabled = true, repSoundEnabled = true),
            params = params(reps = 10),
            repNumber = 5,
        )
        assertEquals(listOf(HapticEvent.REP_COUNT_ANNOUNCED(5)), events)
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
    fun `rep count beyond 25 falls through to REP_COMPLETED or FINAL_REP`() {
        val prefs = UserPreferences(audioRepCountEnabled = true, repSoundEnabled = true)
        assertEquals(
            listOf(HapticEvent.REP_COMPLETED),
            workingRepCueEvents(prefs = prefs, params = params(reps = 40), repNumber = 30),
        )
        assertEquals(
            listOf(HapticEvent.FINAL_REP),
            workingRepCueEvents(prefs = prefs, params = params(reps = 30), repNumber = 30),
        )
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
    fun `ActiveSessionEngine emits dual cue on final rep, falls back above 25, gates warmup, and respects AMRAP`() = runTest {
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
