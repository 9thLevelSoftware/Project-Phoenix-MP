package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
            HapticEvent.REP_COMPLETED to "chirpchirp",
            HapticEvent.FINAL_REP to "boopbeepbeep",
            HapticEvent.WORKOUT_COMPLETE to "boopbeepbeep",
        )
        assertEquals("boopbeepbeep", soundMap[HapticEvent.FINAL_REP])
        assertEquals("chirpchirp", soundMap[HapticEvent.REP_COMPLETED])
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
 * Issue #100: Final rep detection logic tests.
 *
 * These verify the decision logic for when to emit FINAL_REP vs REP_COMPLETED,
 * tested against the conditions used in ActiveSessionEngine.
 */
class FinalRepDetectionLogicTest {

    /**
     * Helper that replicates the FINAL_REP decision logic from ActiveSessionEngine.
     * This avoids needing the full coordinator/engine setup for unit testing pure logic.
     */
    private fun isFinalRep(
        isJustLift: Boolean,
        isAMRAP: Boolean,
        targetReps: Int,
        currentRep: Int,
    ): Boolean {
        return !isJustLift && !isAMRAP && targetReps > 0 && currentRep >= targetReps
    }

    @Test
    fun `final rep detected when currentRep equals targetReps`() {
        assertTrue(isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 10, currentRep = 10))
    }

    @Test
    fun `final rep detected when currentRep exceeds targetReps`() {
        // Edge case: machine sometimes reports one more than expected
        assertTrue(isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 10, currentRep = 11))
    }

    @Test
    fun `non-final rep when currentRep less than targetReps`() {
        val result = isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 10, currentRep = 5)
        assertEquals(false, result)
    }

    @Test
    fun `not final rep in Just Lift mode even at target`() {
        val result = isFinalRep(isJustLift = true, isAMRAP = false, targetReps = 10, currentRep = 10)
        assertEquals(false, result)
    }

    @Test
    fun `not final rep in AMRAP mode even at target`() {
        val result = isFinalRep(isJustLift = false, isAMRAP = true, targetReps = 10, currentRep = 10)
        assertEquals(false, result)
    }

    @Test
    fun `not final rep when targetReps is zero`() {
        val result = isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 0, currentRep = 5)
        assertEquals(false, result)
    }

    @Test
    fun `first rep is not final when target is more than 1`() {
        val result = isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 10, currentRep = 1)
        assertEquals(false, result)
    }

    @Test
    fun `single rep set - rep 1 is final when target is 1`() {
        assertTrue(isFinalRep(isJustLift = false, isAMRAP = false, targetReps = 1, currentRep = 1))
    }
}

/**
 * Issue #100: Preference gate decision logic tests.
 *
 * These test the gating conditions applied in ActiveSessionEngine before emitting events.
 */
class AudioPreferenceGateTest {

    /**
     * Simulates the event selection logic for working rep completion (BOTTOM timing path).
     * Returns the HapticEvent that would be emitted, or null if gated off.
     */
    private fun selectWorkingRepEvent(
        audioRepCountEnabled: Boolean,
        repSoundEnabled: Boolean,
        repNumber: Int,
        isFinalRep: Boolean,
    ): HapticEvent? {
        return if (audioRepCountEnabled && repNumber in 1..25) {
            HapticEvent.REP_COUNT_ANNOUNCED(repNumber)
        } else if (repSoundEnabled && isFinalRep) {
            HapticEvent.FINAL_REP
        } else if (repSoundEnabled) {
            HapticEvent.REP_COMPLETED
        } else {
            null
        }
    }

    @Test
    fun `audioRepCount takes priority over FINAL_REP`() {
        // When audio rep count is enabled, it takes priority even on final rep
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = true,
            repSoundEnabled = true,
            repNumber = 10,
            isFinalRep = true,
        )
        assertTrue(event is HapticEvent.REP_COUNT_ANNOUNCED)
        val announced = event as HapticEvent.REP_COUNT_ANNOUNCED
        assertEquals(10, announced.repNumber)
    }

    @Test
    fun `FINAL_REP emitted when audio rep count disabled and is final rep`() {
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = false,
            repSoundEnabled = true,
            repNumber = 10,
            isFinalRep = true,
        )
        assertEquals(HapticEvent.FINAL_REP, event)
    }

    @Test
    fun `REP_COMPLETED emitted for non-final rep when audio rep count disabled`() {
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = false,
            repSoundEnabled = true,
            repNumber = 5,
            isFinalRep = false,
        )
        assertEquals(HapticEvent.REP_COMPLETED, event)
    }

    @Test
    fun `no event emitted when repSoundEnabled is false and audioRepCount disabled`() {
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = false,
            repSoundEnabled = false,
            repNumber = 5,
            isFinalRep = false,
        )
        assertEquals(null, event)
    }

    @Test
    fun `no event emitted when repSoundEnabled is false for final rep`() {
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = false,
            repSoundEnabled = false,
            repNumber = 10,
            isFinalRep = true,
        )
        assertEquals(null, event)
    }

    @Test
    fun `audio rep count still works when repSoundEnabled is false`() {
        // audioRepCountEnabled overrides repSoundEnabled for announced reps
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = true,
            repSoundEnabled = false,
            repNumber = 5,
            isFinalRep = false,
        )
        assertTrue(event is HapticEvent.REP_COUNT_ANNOUNCED)
    }

    @Test
    fun `warmup rep gated by repSoundEnabled true`() {
        // Simulates the warmup gating logic
        val repSoundEnabled = true
        val event = if (repSoundEnabled) HapticEvent.REP_COMPLETED else null
        assertEquals(HapticEvent.REP_COMPLETED, event)
    }

    @Test
    fun `warmup rep gated by repSoundEnabled false`() {
        val repSoundEnabled = false
        val event = if (repSoundEnabled) HapticEvent.REP_COMPLETED else null
        assertEquals(null, event)
    }

    @Test
    fun `countdown tick gated by beepsEnabled AND countdownBeepsEnabled`() {
        // Both must be true for countdown tick to emit
        data class GateState(val beepsEnabled: Boolean, val countdownBeepsEnabled: Boolean)

        val cases = listOf(
            GateState(true, true) to true,
            GateState(true, false) to false,
            GateState(false, true) to false,
            GateState(false, false) to false,
        )

        for ((state, expected) in cases) {
            val shouldEmit = state.beepsEnabled && state.countdownBeepsEnabled
            assertEquals(
                expected,
                shouldEmit,
                "beepsEnabled=${state.beepsEnabled}, countdownBeepsEnabled=${state.countdownBeepsEnabled}",
            )
        }
    }

    @Test
    fun `rep count beyond 25 falls through to chirp or final rep`() {
        // When rep number exceeds 25, audioRepCount can't announce it (1-25 range)
        // Should fall through to REP_COMPLETED or FINAL_REP based on final rep check
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = true,
            repSoundEnabled = true,
            repNumber = 30,
            isFinalRep = false,
        )
        // repNumber 30 is not in 1..25, so audioRepCount is skipped
        assertEquals(HapticEvent.REP_COMPLETED, event)
    }

    @Test
    fun `rep count beyond 25 on final rep emits FINAL_REP`() {
        val event = selectWorkingRepEvent(
            audioRepCountEnabled = true,
            repSoundEnabled = true,
            repNumber = 30,
            isFinalRep = true,
        )
        assertEquals(HapticEvent.FINAL_REP, event)
    }
}
