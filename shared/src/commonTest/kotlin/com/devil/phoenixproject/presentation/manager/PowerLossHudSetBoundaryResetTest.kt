package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossHudState
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.manager.DWSMTestHarnessFixtures.strongRep
import com.devil.phoenixproject.presentation.manager.DWSMTestHarnessFixtures.weakRep
import com.devil.phoenixproject.presentation.powerloss.PowerLossRuntimeGate
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1392 — power-loss HUD set-boundary reset (ui-spec SD6).
 *
 * The live power panel (`Working attempt N … Ending set…`) belongs to exactly
 * one set. Field QA on the implementation branch saw set 1's frozen panel render
 * under set 2's `Set 2 / Calibration Reps` headers after a max-reps backstop
 * completion: `WorkoutCoordinator._powerLossHud` was written per rep but never
 * cleared at the set boundary. These tests pin the production-path reset at set
 * teardown and at the next set's context install, plus the SD6 non-regression
 * that `endingSet` stays visible through the stop-to-completion window.
 */
class PowerLossHudSetBoundaryResetTest {

    private var armedPowerConfig: PowerLossConfig? = null

    private fun armPowerSettings(
        harness: DWSMTestHarness,
        cap: Int,
        nominal: Int?,
        family: PowerLossProtocolFamily = PowerLossProtocolFamily.OLD_SCHOOL,
    ) {
        PowerLossRuntimeGate.pairedSamplesValidated = true
        armedPowerConfig = PowerLossConfig(
            targetMode = TargetMode.POWER,
            thresholdPercent = 20,
            maxWorkingReps = cap,
            requireTwoConsecutive = false,
            savedNominalReps = nominal,
        )
        harness.coordinator.updatePowerLossSettings(
            PowerLossRuntimeSettings(
                targetMode = TargetMode.POWER,
                thresholdPercent = 20,
                maxWorkingReps = cap,
                requireTwoConsecutive = false,
                savedNominalReps = nominal,
                protocolFamily = family,
                pairedSamplesValidated = true,
            ),
        )
    }

    @AfterTest
    fun clearRuntimeGate() {
        PowerLossRuntimeGate.pairedSamplesValidated = false
        armedPowerConfig = null
    }

    private suspend fun startTrackedCableSet(
        harness: DWSMTestHarness,
        targetReps: Int,
    ): ExecutionLease {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = targetReps,
                warmupReps = 0,
                weightPerCableKg = 25f,
                useAutoStart = false,
                selectedExerciseId = TestFixtures.benchPress.id,
                powerLoss = armedPowerConfig ?: PowerLossConfig.DEFAULT,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private suspend fun persistedReason(
        harness: DWSMTestHarness,
        lease: ExecutionLease,
    ): SetEndReason = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single().setEndReason

    @Test
    fun `completion clears the frozen power hud before the next set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)

            // A rep publishes the live panel: it belongs to this set only.
            harness.activeSessionEngine.evaluatePowerLossForRep(
                lease,
                isWarmup = false,
                repMetrics = strongRep(baseTs = 10_000L),
            )
            runCurrent()
            val frozen = harness.coordinator.powerLossHud.value
            assertTrue(frozen.powerActive, "the live panel is published for the active set")

            // Complete the set at the frozen cap through the production counter
            // path (machine WORKOUT_COMPLETE → MAX_REPS_BACKSTOP completion).
            val cutover = assertNotNull(lease.activationCutoverTimestampMs)
            harness.fakeBleRepo.setHandleState(com.devil.phoenixproject.data.repository.HandleState.Moving)
            runCurrent()
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(repsSetCount = 20, repsSetTotal = 20, timestamp = cutover + 1),
            )
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.MAX_REPS_BACKSTOP, persistedReason(harness, lease))

            // SD6 set boundary: the frozen panel ends with the completed
            // transition — nothing of set 1 survives for set 2 to render.
            val cleared = harness.coordinator.powerLossHud.value
            assertFalse(cleared.powerActive, "the frozen panel is cleared at set teardown")
            assertFalse(cleared.endingSet, "no stale Ending set state survives the set")
            assertEquals(0, cleared.currentAttemptOrdinal)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `a new set installs a clean power hud even after a stale frozen panel`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // Reproduce the field observation: a frozen panel from a finished set
            // (`Working attempt 20 … Ending set…`) is still published when the
            // next set's HUD mounts.
            harness.coordinator.setPowerLossHud(
                PowerLossHudState(
                    powerActive = true,
                    currentAttemptOrdinal = 20,
                    completedRepCount = 20,
                    endingSet = true,
                ),
            )
            armPowerSettings(harness, cap = 20, nominal = 12)
            startTrackedCableSet(harness, targetReps = 12)

            val hud = harness.coordinator.powerLossHud.value
            assertFalse(hud.powerActive, "the new set's HUD starts clean before any rep")
            assertFalse(hud.endingSet, "the previous set's Ending set state never leaks")
            assertEquals(0, hud.currentAttemptOrdinal)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `ending set stays visible until the completed transition`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)

            // Rep 1 establishes the reference (130.8 W); rep 2 loses 50% and
            // trips the 20% threshold (earliest fatigue trigger: 2nd attempt).
            // The production counter state at the trigger mirrors the two
            // completed working reps the rep packets would have counted.
            harness.coordinator._repCount.value = RepCount(workingReps = 2, totalReps = 2, isWarmupComplete = true)
            harness.activeSessionEngine.evaluatePowerLossForRep(
                lease,
                isWarmup = false,
                repMetrics = strongRep(baseTs = 20_000L),
            )
            harness.activeSessionEngine.evaluatePowerLossForRep(
                lease,
                isWarmup = false,
                repMetrics = weakRep(baseTs = 20_100L),
            )

            // SD6: the committed stop state is distinct from the completed
            // transition — `Ending set…` is visible while the set is stopping.
            val stopping = harness.coordinator.powerLossHud.value
            assertTrue(stopping.powerActive)
            assertTrue(stopping.endingSet, "the committed stop state renders during the stop")

            // Once the set completes, the panel clears with the transition.
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.POWER_LOSS, persistedReason(harness, lease))
            val completed = harness.coordinator.powerLossHud.value
            assertFalse(completed.powerActive, "the completed transition leaves no frozen panel")
            assertFalse(completed.endingSet)
        } finally {
            harness.cleanup()
        }
    }
}
