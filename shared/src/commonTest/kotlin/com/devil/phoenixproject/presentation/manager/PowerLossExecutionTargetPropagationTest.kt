package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.manager.DWSMTestHarnessFixtures.strongRep
import com.devil.phoenixproject.presentation.manager.DWSMTestHarnessFixtures.invalidRep
import com.devil.phoenixproject.presentation.manager.DWSMTestHarnessFixtures.weakRep
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1392 (P3 remainder) — production-path single-effective-target tests
 * (signoff §4.2: pure tests alone do not satisfy this packet).
 *
 * Real DWSM/engine/guard/counter/freshness-gate composition with only transport
 * and storage faked (DWSMTestHarness): the ONE frozen [ExecutionTargetPlan] must
 * be derived before lease/packet/counter creation and read identically by the
 * packet byte, the counter config, the rep freshness gate, the power policy and
 * the HUD. Covers SD4 hard rules (cap independent of telemetry; saved nominal
 * below the cap never stops POWER; protocol max derived before Start;
 * finite/unlimited identity preserved) and the same-cycle one-winner latch.
 */
class PowerLossExecutionTargetPropagationTest {

    private fun armPowerSettings(
        harness: DWSMTestHarness,
        cap: Int,
        nominal: Int?,
        family: PowerLossProtocolFamily = PowerLossProtocolFamily.OLD_SCHOOL,
    ) {
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

    // ===== (d) POWER plan identity propagation =====

    @Test
    fun `power plan freezes one effective target across packet lease counter and HUD`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)

            // Packet agreement: the frame carries the frozen cap plus the warmup
            // buffer — never the saved nominal.
            assertEquals(
                23,
                harness.fakeBleRepo.programCommands.last().repsByte,
                "PROGRAM total byte is the frozen effective target 20 plus warmup 3",
            )

            // Lease agreement: one frozen plan, one effective target.
            val plan = assertNotNull(lease.targetPlan)
            assertEquals(TargetMode.POWER, plan.targetMode)
            assertEquals(20, plan.maxWorkingReps)
            assertEquals(12, plan.nominalReps, "the saved nominal is preserved display-only")
            assertEquals(3, plan.warmupSetCount, "the warmup bound is frozen on the plan")
            assertTrue(plan.protocolIdentityFinite)
            assertEquals(20, lease.effectiveWorkingRepTarget)

            // Freshness-gate agreement: the machine's cap total matches and the
            // nominal count is ordinary progress below the cap.
            val cutover = assertNotNull(lease.activationCutoverTimestampMs)
            harness.fakeBleRepo.setHandleState(com.devil.phoenixproject.data.repository.HandleState.Moving)
            runCurrent()
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(repsSetCount = 12, repsSetTotal = 20, timestamp = cutover + 1),
            )
            runCurrent()
            assertEquals(12, harness.coordinator.repCount.value.workingReps)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            // HUD agreement: the published facts come from the frozen plan.
            harness.activeSessionEngine.evaluatePowerLossForRep(lease, isWarmup = false, repMetrics = strongRep(baseTs = 10_000L))
            runCurrent()
            val hud = harness.coordinator.powerLossHud.value
            assertTrue(hud.powerActive)
            assertEquals(20, hud.maxWorkingReps, "HUD reads the frozen effective target")
            assertEquals(12, hud.savedNominalReps)

            // Counter agreement: completion lands exactly at the frozen cap and
            // normalizes to MAX_REPS_BACKSTOP on the finite POWER plan.
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(repsSetCount = 20, repsSetTotal = 20, timestamp = cutover + 2),
            )
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.MAX_REPS_BACKSTOP, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== (e) saved nominal below the cap never ends a POWER set =====

    @Test
    fun `saved nominal below the cap never ends a power set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)

            // The machine is told the cap, not the nominal, so the set is finite
            // at 20 working reps for both the machine counter and the app.
            assertEquals(23, harness.fakeBleRepo.programCommands.last().repsByte)
            assertEquals(20, lease.effectiveWorkingRepTarget)

            val cutover = assertNotNull(lease.activationCutoverTimestampMs)
            harness.fakeBleRepo.setHandleState(com.devil.phoenixproject.data.repository.HandleState.Moving)
            runCurrent()
            // Reaching the nominal 12 must not complete the set.
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(repsSetCount = 12, repsSetTotal = 20, timestamp = cutover + 1),
            )
            runCurrent()
            assertEquals(12, harness.coordinator.repCount.value.workingReps)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value, "the nominal is not a stop condition")

            // Neither must the power policy stop at the nominal.
            repeat(12) { index ->
                harness.activeSessionEngine.evaluatePowerLossForRep(
                    lease,
                    isWarmup = false,
                    repMetrics = strongRep(baseTs = 20_000L + index * 100),
                )
            }
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== (f) cap enforcement is independent of telemetry =====

    @Test
    fun `cap stop fires with telemetry invalid or unavailable`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 3, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3, isWarmupComplete = true)

            // Three working attempts with entirely unusable power telemetry (no
            // sealed concentric window, unknown ROM) — only the cap can stop, and
            // it must (SD4).
            repeat(3) { index ->
                harness.activeSessionEngine.evaluatePowerLossForRep(
                    lease,
                    isWarmup = false,
                    repMetrics = invalidRep(baseTs = 30_000L + index * 100),
                )
            }
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.MAX_REPS_BACKSTOP, persistedReason(harness, lease))
            assertEquals(
                SetEndReason.MAX_REPS_BACKSTOP,
                assertNotNull(harness.activeSessionEngine.claimedCompletion(lease)).reason,
                "the immutable winner is the cap even with no valid telemetry",
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== (a) same-cycle races: one completion, one snapshot, one navigation =====

    @Test
    fun `same cycle competing completions latch one winner one snapshot one summary`() = runTest {
        var claimCount = 0
        lateinit var harness: DWSMTestHarness
        harness = DWSMTestHarness(
            this,
            afterCompletionClaim = { _, _, _ ->
                claimCount += 1
                val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
                // Same-cycle competing candidates: stall (safety), manual, cap,
                // dispatch/cable-release and VBT fatigue all arrive after the
                // first claim and must lose to the immutable winner.
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.USER_STOPPED)
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.MAX_REPS_BACKSTOP)
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.CABLE_RELEASED)
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.VBT_AUTO_END)
            },
        )
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)
            harness.coordinator._repCount.value = RepCount(workingReps = 5, totalReps = 5, isWarmupComplete = true)

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.POWER_LOSS)
            runCurrent()
            // A same-cycle machine-side completion candidate joins the race.
            harness.repCounter.onRepEvent?.invoke(RepEvent(type = RepType.WORKOUT_COMPLETE, warmupCount = 3, workingCount = 20))
            runCurrent()
            advanceUntilIdle()

            assertEquals(1, claimCount, "exactly one completion claim")
            assertEquals(SetEndReason.POWER_LOSS, persistedReason(harness, lease), "the first winner is immutable")
            assertEquals(1, harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).size, "exactly one completed set")
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId }, "exactly one snapshot")
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount, "exactly one device stop")
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value, "exactly one navigation")
            assertEquals(
                SetEndReason.POWER_LOSS,
                assertNotNull(harness.activeSessionEngine.claimedCompletion(lease)).reason,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `late callbacks and disconnect never overwrite the latched winner`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)
            harness.coordinator._repCount.value = RepCount(workingReps = 5, totalReps = 5, isWarmupComplete = true)

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.POWER_LOSS)
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.POWER_LOSS, persistedReason(harness, lease))

            // Late callbacks after the committed completion, then a disconnect.
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.USER_STOPPED)
            harness.repCounter.onRepEvent?.invoke(RepEvent(type = RepType.WORKOUT_COMPLETE, warmupCount = 3, workingCount = 20))
            harness.fakeBleRepo.simulateDisconnect()
            runCurrent()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.VBT_AUTO_END)
            runCurrent()
            advanceUntilIdle()

            assertEquals(1, harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).size)
            assertEquals(SetEndReason.POWER_LOSS, persistedReason(harness, lease), "winner immutable across late callbacks")
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    // ===== (b) stale execution/lease rejection =====

    @Test
    fun `stale execution lease cannot complete or evaluate power loss`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val staleLease = startTrackedCableSet(harness, targetReps = 12)
            harness.coordinator._repCount.value = RepCount(workingReps = 4, totalReps = 4, isWarmupComplete = true)

            // Retire A through a normal manual stop, then a successor wins authority.
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertNotEquals(staleLease.executionId, lease.executionId)

            // A stale completion is rejected outright.
            harness.activeSessionEngine.handleSetCompletion(staleLease, SetEndReason.POWER_LOSS)
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(
                1,
                harness.fakeCompletedSetRepo.getCompletedSets(staleLease.sessionId).size,
                "only the original manual-stop completion exists for the stale execution",
            )
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))

            // A stale power-loss evaluation can neither trigger nor latch anything.
            harness.activeSessionEngine.evaluatePowerLossForRep(staleLease, isWarmup = false, repMetrics = weakRep(baseTs = 40_000L))
            runCurrent()
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== (c) no manufactured rep windows on counter jumps =====

    @Test
    fun `counter jumps manufacture no extra rep windows`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 20, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)
            val events = mutableListOf<RepType>()
            val engineHandler = harness.repCounter.onRepEvent
            harness.repCounter.onRepEvent = { event ->
                events += event.type
                engineHandler?.invoke(event)
            }

            val cutover = assertNotNull(lease.activationCutoverTimestampMs)
            harness.fakeBleRepo.setHandleState(com.devil.phoenixproject.data.repository.HandleState.Moving)
            runCurrent()
            // One packet whose directional counters jump several reps at once.
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 20,
                    topCounter = 7,
                    completeCounter = 7,
                    timestamp = cutover + 1,
                ),
            )
            runCurrent()
            assertEquals(3, harness.coordinator.repCount.value.workingReps, "the machine counter is trusted")
            assertEquals(1, events.count { it == RepType.WORKING_COMPLETED }, "one confirmation — not one per jumped counter unit")
            assertEquals(0, events.count { it == RepType.WORKOUT_COMPLETE }, "below the frozen cap nothing completes")

            // A rewound counter packet re-baselines instead of manufacturing reps.
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 20,
                    topCounter = 0,
                    completeCounter = 0,
                    timestamp = cutover + 2,
                ),
            )
            runCurrent()
            assertEquals(1, events.count { it == RepType.WORKING_COMPLETED }, "a rewound packet manufactures no rep windows")
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    // ===== (g) REP-mode behavior unchanged =====

    @Test
    fun `rep mode keeps nominal encoding and target reps reached`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // No POWER settings: the default runtime is inert REP.
            val lease = startTrackedCableSet(harness, targetReps = 8)
            val plan = lease.targetPlan
            assertTrue(plan == null || plan.targetMode == TargetMode.REP_TARGET)
            assertEquals(8, lease.effectiveWorkingRepTarget, "REP keeps its per-set nominal target")
            assertEquals(
                11,
                harness.fakeBleRepo.programCommands.last().repsByte,
                "REP bytes stay nominal plus warmup — byte-identical to today",
            )

            // Power-loss evaluation is inert under REP.
            harness.coordinator._repCount.value = RepCount(workingReps = 2, totalReps = 2, isWarmupComplete = true)
            harness.activeSessionEngine.evaluatePowerLossForRep(lease, isWarmup = false, repMetrics = weakRep(baseTs = 50_000L))
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))

            // WORKOUT_COMPLETE keeps its legacy TARGET_REPS_REACHED meaning.
            harness.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
            harness.repCounter.onRepEvent?.invoke(RepEvent(type = RepType.WORKOUT_COMPLETE, warmupCount = 3, workingCount = 8))
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.TARGET_REPS_REACHED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== Impossible plans fail safe to inert REP behavior =====

    @Test
    fun `impossible power plan fails safe to inert rep behavior`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // 255 cannot fit the finite PROGRAM byte alongside the warmup buffer.
            armPowerSettings(harness, cap = 255, nominal = 12)
            val lease = startTrackedCableSet(harness, targetReps = 12)

            assertNull(lease.targetPlan, "an impossible plan never arms POWER")
            assertEquals(12, lease.effectiveWorkingRepTarget)
            assertEquals(
                15,
                harness.fakeBleRepo.programCommands.last().repsByte,
                "the set fails safe to inert REP encoding",
            )

            repeat(3) { index ->
                harness.activeSessionEngine.evaluatePowerLossForRep(
                    lease,
                    isWarmup = false,
                    repMetrics = weakRep(baseTs = 60_000L + index * 100),
                )
            }
            runCurrent()
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value, "no POWER stop may fire")
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== Unlimited identity preserved; cap enforced in-app =====

    @Test
    fun `unlimited identity keeps its sentinel and caps in app`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            armPowerSettings(harness, cap = 3, nominal = null, family = PowerLossProtocolFamily.JUST_LIFT)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 0,
                    warmupReps = 0,
                    weightPerCableKg = 25f,
                    isJustLift = true,
                    useAutoStart = false,
                    selectedExerciseId = TestFixtures.benchPress.id,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val plan = assertNotNull(lease.targetPlan)
            assertEquals(TargetMode.POWER, plan.targetMode)
            assertFalse(plan.protocolIdentityFinite, "genuine Just Lift unlimited identity preserved")
            assertEquals(
                255,
                harness.fakeBleRepo.programCommands.last().repsByte,
                "the 0xFF unlimited sentinel semantics are unchanged",
            )
            assertEquals(3, lease.effectiveWorkingRepTarget, "the cap is enforced in-app")

            // The in-app cap fires at the frozen backstop even with no telemetry.
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3, isWarmupComplete = true)
            repeat(3) { index ->
                harness.activeSessionEngine.evaluatePowerLossForRep(
                    lease,
                    isWarmup = false,
                    repMetrics = invalidRep(baseTs = 70_000L + index * 100),
                )
            }
            runCurrent()
            advanceUntilIdle()
            assertEquals(SetEndReason.MAX_REPS_BACKSTOP, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }
}

/** Rep-segment fixtures: sealed concentric windows or deliberately unusable telemetry. */
internal object DWSMTestHarnessFixtures {

    fun repMetrics(baseTs: Long, velocityMmS: Double, valid: Boolean): List<WorkoutMetric> {
        val velocities = if (valid) {
            listOf(velocityMmS, velocityMmS, -200.0, -200.0)
        } else {
            listOf(0.0, 0.0, 0.0, 0.0)
        }
        val positions = if (valid) listOf(0f, 100f, 100f, 0f) else listOf(50f, 50f, 50f, 50f)
        return List(4) { index ->
            WorkoutMetric(
                timestamp = baseTs + index * 10,
                loadA = 20f,
                loadB = 20f,
                positionA = positions[index],
                positionB = positions[index],
                velocityA = velocities[index],
                velocityB = velocities[index],
            )
        }
    }

    /** Full-ROM rep with MCP V1 = 130.8 W (20 kg per cable at 500 mm/s). */
    fun strongRep(baseTs: Long): List<WorkoutMetric> = repMetrics(baseTs, velocityMmS = 500.0, valid = true)

    /** Full-ROM rep with MCP V1 = 65.4 W — a 50% loss against [strongRep]. */
    fun weakRep(baseTs: Long): List<WorkoutMetric> = repMetrics(baseTs, velocityMmS = 250.0, valid = true)

    /** No sealed concentric window and unknown ROM: unusable telemetry. */
    fun invalidRep(baseTs: Long): List<WorkoutMetric> = repMetrics(baseTs, velocityMmS = 0.0, valid = false)
}
