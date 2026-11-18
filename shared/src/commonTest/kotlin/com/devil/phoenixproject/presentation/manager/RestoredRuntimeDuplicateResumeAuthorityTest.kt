package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.PowerLossRestartExplanation
import com.devil.phoenixproject.data.repository.PowerLossRestartRecovery
import com.devil.phoenixproject.data.repository.RestoredPowerLossRuntimeSnapshot
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.ProtocolRepBounds
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RestoredRuntimeDuplicateResumeAuthorityTest {
    @Test
    fun `duplicate Resume of paused runtime after profile drift is superseded without publication`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            assertDormantDuplicateResumeIsSuperseded(
                harness = harness,
                routineSessionId = "duplicate-paused-profile-drift",
                dormantTimer = DormantTimer.PAUSED,
            ) {
                it.fakeUserProfileRepo.setActiveProfileForTest(id = "profile-after-restoration")
            }
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `duplicate Resume of expired runtime after rack stamp drift is superseded without publication`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            assertDormantDuplicateResumeIsSuperseded(
                harness = harness,
                routineSessionId = "duplicate-expired-rack-drift",
                dormantTimer = DormantTimer.EXPIRED,
            ) {
                it.fakeEquipmentRackRepo.saveItems(
                    listOf(RackItem(id = "rack-after-restoration", name = "Changed rack", weightKg = 2f)),
                )
            }
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `duplicate Resume of paused runtime after configuration epoch drift is superseded without publication`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            assertDormantDuplicateResumeIsSuperseded(
                harness = harness,
                routineSessionId = "duplicate-paused-configuration-drift",
                dormantTimer = DormantTimer.PAUSED,
            ) {
                it.activeSessionEngine.supersedeConfigurationInputIntent()
            }
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `interrupted power loss runtime rebaselines on resume with no machine commands`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // An armed power-loss runtime snapshot with incomplete samples survives the
            // crash; after restart the armed policy must NEVER resume — the accumulator,
            // baseline and streak are rebaselined with the explanatory UX state, and the
            // restore path issues NO automatic machine commands (existing invariant).
            val armedSnapshot = RestoredPowerLossRuntimeSnapshot(
                codecVersion = RestoredPowerLossRuntimeSnapshot.CURRENT_CODEC_VERSION,
                targetModeName = "POWER",
                thresholdPercent = 25,
                maxWorkingReps = 15,
                requireTwoConsecutive = true,
                savedNominalReps = 12,
                baselineMcpWatts = 312.5f,
                referenceWorkingOrdinal = 2,
                qualifyingStreak = 1,
                sealedObservationCount = 3,
                expectedObservationCount = 4,
                samplesComplete = false,
                restartExplanationName = "INCOMPLETE_SAMPLES",
            )
            val installed = installRuntime(
                harness = harness,
                routineSessionId = "power-loss-interrupted-restart",
                dormantTimer = DormantTimer.PAUSED,
                powerLoss = armedSnapshot,
            )

            val commandsBefore = harness.fakeBleRepo.commandsReceived.size
            val workoutParametersBefore = harness.fakeBleRepo.programCommands.size
            val stopWorkoutCallsBefore = harness.fakeBleRepo.stopWorkoutCallCount

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            // No automatic machine commands on restore beyond the restore baseline.
            assertEquals(
                commandsBefore,
                harness.fakeBleRepo.commandsReceived.size,
                "commands after resume: ${harness.fakeBleRepo.commandsReceived}",
            )
            assertEquals(workoutParametersBefore, harness.fakeBleRepo.programCommands.size)
            // The only machine-facing call on restore is the established safety
            // teardown stop (MachineTeardownState.Ready path): nothing is started,
            // no workout program is sent, and no other machine command is issued.
            assertEquals(stopWorkoutCallsBefore + 1, harness.fakeBleRepo.stopWorkoutCallCount)

            // The armed policy is never resumed; every part of the policy state is reset
            // and the explanatory UX state comes from the snapshot.
            val recovery = PowerLossRestartRecovery.rebaselineOnRestart(installed.document.powerLoss)
            assertEquals(false, recovery.resumeArmed)
            assertEquals(true, recovery.baselineReset)
            assertEquals(true, recovery.streakReset)
            assertEquals(true, recovery.accumulatorReset)
            assertEquals(PowerLossRestartExplanation.INCOMPLETE_SAMPLES, recovery.explanation)

            // The production controller entry resets the live reducer state too.
            val controller = PowerLossSetController()
            val seeded = controller.onObservation(
                config = PowerLossConfig(targetMode = TargetMode.POWER, thresholdPercent = 25, maxWorkingReps = 15),
                plan = requireNotNull(
                    ExecutionTargetPlan.derive(
                        config = PowerLossConfig(targetMode = TargetMode.POWER, thresholdPercent = 25, maxWorkingReps = 15),
                        bounds = ProtocolRepBounds(maxRepresentableWorkingReps = 60, protocolIdentityFinite = true),
                        warmupSetCount = 0,
                        now = 0L,
                    ),
                ),
                observation = PowerLossObservation(
                    workingConcentricOrdinal = 1,
                    isWarmup = false,
                    mcpWatts = 312.5f,
                    romA = CableRom.trusted(420f),
                    romB = CableRom.trusted(415f),
                    activeCables = setOf(PowerCable.A, PowerCable.B),
                ),
            )
            assertNotNull(seeded)
            assertNotNull(controller.buildEvidence("session-x", "set-x"))

            val state = controller.restoreFromSnapshot(installed.document.powerLoss)
            assertEquals(false, state.resumeArmed)
            assertEquals(PowerLossRestartExplanation.INCOMPLETE_SAMPLES, state.explanation)
            assertNull(
                controller.buildEvidence("session-x", "set-x"),
                "restart/rebaseline resets the accumulator baseline and streak",
            )
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    private enum class DormantTimer { PAUSED, EXPIRED }

    private data class InstalledRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    private suspend fun TestScope.assertDormantDuplicateResumeIsSuperseded(
        harness: DWSMTestHarness,
        routineSessionId: String,
        dormantTimer: DormantTimer,
        mutateAuthority: suspend (DWSMTestHarness) -> Unit,
    ) {
        val installed = installRuntime(harness, routineSessionId, dormantTimer)
        assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
            harness.dwsm.resumeRoutine(installed.handle),
        )
        runCurrent()
        assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)

        val runtimeOwnerBefore = assertNotNull(
            harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(),
        )
        val timerOwnerBefore = assertNotNull(
            harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(),
        )
        assertEquals(runtimeOwnerBefore, timerOwnerBefore)
        assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
        assertNull(
            harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
        )

        val commandsBefore = harness.fakeBleRepo.commandsReceived.size
        val workoutParametersBefore = harness.fakeBleRepo.programCommands.size
        val stopWorkoutCallsBefore = harness.fakeBleRepo.stopWorkoutCallCount

        mutateAuthority(harness)
        val result = harness.dwsm.resumeRoutine(installed.handle)

        val runtimeOwnerAfter = harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest()
        val timerOwnerAfter = harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest()
        assertTrue(runtimeOwnerAfter == null || runtimeOwnerAfter == runtimeOwnerBefore)
        assertTrue(timerOwnerAfter == null || timerOwnerAfter == timerOwnerBefore)
        assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
        assertNull(
            harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
        )
        assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
        assertEquals(workoutParametersBefore, harness.fakeBleRepo.programCommands.size)
        assertEquals(stopWorkoutCallsBefore, harness.fakeBleRepo.stopWorkoutCallCount)
        assertEquals(
            installed.document,
            harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                installed.document.profileId,
                installed.document.routineSessionId,
            ),
        )
        assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(result)
    }

    private suspend fun TestScope.installRuntime(
        harness: DWSMTestHarness,
        routineSessionId: String,
        dormantTimer: DormantTimer,
        powerLoss: RestoredPowerLossRuntimeSnapshot? = null,
    ): InstalledRuntime {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 25f,
        )
        val exercise = routine.exercises.single()
        val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
        val sourceStableSessionId = "source-$routineSessionId"
        val logicalSetKey = LogicalSetKey(
            routineSessionId = routineSessionId,
            routineExerciseId = exercise.id,
            setIndex = 0,
            setKind = SetType.STANDARD,
        )
        val document = runtimeDocument(
            profileId = profileId,
            routine = routine,
            sourceExercise = exercise,
            sourceStableSessionId = sourceStableSessionId,
            logicalSetKey = logicalSetKey,
            dormantTimer = dormantTimer,
            nowEpochMs = harness.nowMs,
            powerLoss = powerLoss,
        )
        harness.fakeCompletedSetRepo.setSessionRoutine(sourceStableSessionId, routineSessionId)
        harness.fakeCompletedSetRepo.insertCompletedSet(
            CompletedSet(
                id = "durable-$routineSessionId",
                sessionId = sourceStableSessionId,
                plannedSetId = null,
                setNumber = 0,
                setType = SetType.STANDARD,
                actualReps = 6,
                actualWeightKg = 25f,
                loggedRpe = null,
                isPr = false,
                completedAt = 1L,
                setEndReason = SetEndReason.STALL_FAILURE,
                routineExerciseId = exercise.id,
                attemptNumber = 1,
            ),
        )
        harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, routineSessionId, document)
        val handle = assertIs<RoutineResumeHandle.Persisted>(
            assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            ).handle,
        )
        return InstalledRuntime(document, handle)
    }

    private fun runtimeDocument(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        logicalSetKey: LogicalSetKey,
        dormantTimer: DormantTimer,
        nowEpochMs: Long,
        powerLoss: RestoredPowerLossRuntimeSnapshot? = null,
    ): ActiveWorkoutRuntimeDocument {
        val sourceExecutionId = "42"
        val plan = RestTransitionPlan.NormalAdvance(
            transitionId = "duplicate-resume-${logicalSetKey.routineSessionId}",
            sourceExecutionId = sourceExecutionId,
            logicalSetKey = logicalSetKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(0, 0),
            plannedSetId = null,
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routine.id,
            routineSessionId = logicalSetKey.routineSessionId,
            routineExerciseId = sourceExercise.id,
            sourceExecutionId = sourceExecutionId,
            sourceStableSessionId = sourceStableSessionId,
            sourceAttemptNumber = 1,
            logicalSetKey = logicalSetKey,
            plannedSetId = null,
            sourceExerciseIndex = 0,
            sourceSetIndex = 0,
            sourceAuthority = sourceAuthority(
                profileId = profileId,
                routine = routine,
                sourceExercise = sourceExercise,
                sourceStableSessionId = sourceStableSessionId,
                sourceExecutionId = sourceExecutionId,
                logicalSetKey = logicalSetKey,
            ),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = sourceExecutionId.toLong(),
                sourceStableSessionId = sourceStableSessionId,
                profileId = profileId,
                requiresMachine = true,
            ),
            attemptStates = listOf(
                PlannedSetAttemptState(
                    logicalSetKey = logicalSetKey,
                    nextAttemptNumber = 2,
                    acceptedDropCount = 0,
                ),
            ),
            restTransitionPlan = plan,
            restDeadlineEpochMs = when (dormantTimer) {
                DormantTimer.PAUSED -> null
                DormantTimer.EXPIRED -> nowEpochMs - 1_000L
            },
            pausedRestRemainingSeconds = when (dormantTimer) {
                DormantTimer.PAUSED -> 30
                DormantTimer.EXPIRED -> null
            },
            isRestPaused = dormantTimer == DormantTimer.PAUSED,
            originalRestDurationSeconds = 60,
            powerLoss = powerLoss,
        )
    }

    private fun sourceAuthority(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        sourceExecutionId: String,
        logicalSetKey: LogicalSetKey,
    ): RestoredRetrySourceAuthoritySnapshot {
        val targetReps = requireNotNull(sourceExercise.setReps.first())
        val command = WorkoutParameters(
            programMode = sourceExercise.programMode,
            reps = targetReps,
            weightPerCableKg = sourceExercise.weightPerCableKg,
            progressionRegressionKg = sourceExercise.progressionKg,
            stopAtTop = sourceExercise.stopAtTop,
            warmupReps = 3,
            selectedExerciseId = sourceExercise.exercise.id,
            isAMRAP = false,
            stallDetectionEnabled = sourceExercise.stallDetectionEnabled,
            repCountTiming = sourceExercise.repCountTiming,
            echoLevel = sourceExercise.getEchoLevelForSet(0),
            eccentricLoad = sourceExercise.eccentricLoad,
        )
        return RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = sourceStableSessionId,
            sourceExecutionId = sourceExecutionId,
            profileId = profileId,
            routineIdentity = RoutineExecutionIdentity(
                profileId = profileId,
                routineId = routine.id,
                routineSessionId = logicalSetKey.routineSessionId,
                routineExerciseId = sourceExercise.id,
                logicalSetKey = logicalSetKey,
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = SetType.STANDARD.name,
            programModeName = "OLD_SCHOOL",
            programmedBaseWeightPerCableKg = sourceExercise.weightPerCableKg,
            configuredStartWeightPerCableKg = sourceExercise.weightPerCableKg,
            progressionKg = sourceExercise.progressionKg,
            actualReps = 6,
            targetReps = targetReps,
            isWarmup = false,
            isEcho = false,
            isJustLift = false,
            isBodyweight = false,
            isTimed = false,
            isAmrap = false,
            isCableExercise = true,
            physicalCableCount = sourceExercise.exercise.preferredCableCount,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(command),
        )
    }
}
