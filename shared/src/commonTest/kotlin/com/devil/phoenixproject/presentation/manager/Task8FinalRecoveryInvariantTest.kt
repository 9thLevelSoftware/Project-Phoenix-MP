package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Task8FinalRecoveryInvariantTest {
    @Test
    fun `cold recovery validates a scattered legacy superset occurrence in normalized order`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val legacyRoutine = WorkoutStateFixtures.createNonContiguousSupersetRoutine()
            assertEquals(
                listOf("re-nc-0", "re-nc-1", "re-nc-2"),
                legacyRoutine.exercises.map { it.id },
            )
            val normalizedSourceExercise = legacyRoutine.exercises[2]
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val document = normalRuntimeDocument(
                profileId = profileId,
                routineId = legacyRoutine.id,
                sourceExercise = normalizedSourceExercise,
                sourceExerciseIndex = 1,
                sourceSetIndex = 1,
                routineSessionId = "scattered-normalized-session",
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                document.sourceStableSessionId,
                document.routineSessionId,
            )
            harness.fakeCompletedSetRepo.saveCompletedSet(
                CompletedSet(
                    id = "scattered-normalized-durable-source",
                    sessionId = document.sourceStableSessionId,
                    plannedSetId = null,
                    setNumber = 1,
                    setType = SetType.STANDARD,
                    actualReps = 6,
                    actualWeightKg = 15f,
                    loggedRpe = null,
                    isPr = false,
                    completedAt = 1L,
                    setEndReason = SetEndReason.STALL_FAILURE,
                    routineExerciseId = "re-nc-2",
                    attemptNumber = 1,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(
                profileId,
                document.routineSessionId,
                document,
            )

            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(
                        legacyRoutine,
                        RoutineLaunchOrigin.DAILY_ROUTINES,
                    ),
                ).handle,
            )
            assertEquals(RestTransitionPlan.Coordinates(1, 1), handle.manualRecoveryCoordinates)
            assertEquals(2, handle.progressInfo.currentExercise)
            assertEquals(2, handle.progressInfo.currentSet)

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(handle),
            )
            runCurrent()

            assertEquals(
                listOf("re-nc-0", "re-nc-2", "re-nc-1"),
                harness.coordinator.loadedRoutine.value?.exercises?.map { it.id },
            )
            assertEquals("re-nc-2", harness.coordinator.loadedRoutine.value?.exercises?.get(1)?.id)
            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(document.restTransitionPlan, harness.restTransitionPlan.value)
            assertEquals(
                document,
                assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(
                        profileId,
                        document.routineSessionId,
                    ),
                ).document,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `live rest at first set resumes through the existing in memory workout`() = runTest {
        val harness = DWSMTestHarness(
            testScope = this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = {
                DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f)
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 6,
                totalReps = 6,
                isWarmupComplete = true,
            )
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)
            runCurrent()

            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val liveDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(RestTransitionPlan.Coordinates(0, 0), liveDocument.sourceCoordinatesForTest())
            assertEquals(
                liveDocument,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    liveDocument.profileId,
                    liveDocument.routineSessionId,
                ),
            )
            val planBeforeResume = assertNotNull(harness.restTransitionPlan.value)
            val commandsBeforeResume = harness.fakeBleRepo.commandsReceived.size
            val configurationsBeforeResume = harness.fakeBleRepo.workoutParameters.size
            val stopsBeforeResume = harness.fakeBleRepo.stopWorkoutCallCount
            val runtimeWritesBeforeResume = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val preparationsBeforeResume = harness.activeSessionEngine.recoveryPreparationCallsForTest

            val handle = assertIs<RoutineResumeHandle.InMemory>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(
                        routine,
                        RoutineLaunchOrigin.DAILY_ROUTINES,
                    ),
                ).handle,
            )
            assertEquals(1, handle.progressInfo.currentExercise)
            assertEquals(1, handle.progressInfo.currentSet)
            assertEquals(liveDocument.routineSessionId, handle.routineSessionId)
            assertTrue(harness.dwsm.isRoutineResumeHandleCurrent(handle))

            assertIs<ActiveWorkoutRuntimeResumeResult.Missing>(harness.dwsm.resumeRoutine(handle))

            assertSame(sourceLease, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(planBeforeResume, harness.restTransitionPlan.value)
            assertEquals(liveDocument, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(commandsBeforeResume, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(configurationsBeforeResume, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(stopsBeforeResume, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(runtimeWritesBeforeResume, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(preparationsBeforeResume, harness.activeSessionEngine.recoveryPreparationCallsForTest)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(
                liveDocument,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    liveDocument.profileId,
                    liveDocument.routineSessionId,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun normalRuntimeDocument(
        profileId: String,
        routineId: String,
        sourceExercise: RoutineExercise,
        sourceExerciseIndex: Int,
        sourceSetIndex: Int,
        routineSessionId: String,
    ): ActiveWorkoutRuntimeDocument {
        val sourceStableSessionId = "source-$routineSessionId"
        val isBodyweight = sourceExercise.exercise.isBodyweight
        val isTimed = sourceExercise.duration?.takeIf { it > 0 } != null
        val logicalSetKey = LogicalSetKey(
            routineSessionId = routineSessionId,
            routineExerciseId = sourceExercise.id,
            setIndex = sourceSetIndex,
            setKind = SetType.STANDARD,
        )
        val sourceCommand = WorkoutParameters(
            programMode = sourceExercise.programMode,
            reps = requireNotNull(sourceExercise.setReps[sourceSetIndex]),
            weightPerCableKg = sourceExercise.weightPerCableKg,
            progressionRegressionKg = sourceExercise.progressionKg,
            stopAtTop = sourceExercise.stopAtTop,
            warmupReps = if (isBodyweight) 0 else 3,
            selectedExerciseId = sourceExercise.exercise.id,
            isAMRAP = false,
            stallDetectionEnabled = sourceExercise.stallDetectionEnabled,
            repCountTiming = sourceExercise.repCountTiming,
            echoLevel = sourceExercise.getEchoLevelForSet(sourceSetIndex),
            eccentricLoad = sourceExercise.eccentricLoad,
        )
        val sourceAuthority = RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = sourceStableSessionId,
            sourceExecutionId = "42",
            profileId = profileId,
            routineIdentity = RoutineExecutionIdentity(
                profileId = profileId,
                routineId = routineId,
                routineSessionId = routineSessionId,
                routineExerciseId = sourceExercise.id,
                logicalSetKey = logicalSetKey,
                plannedSetId = null,
                exerciseIndex = sourceExerciseIndex,
                setIndex = sourceSetIndex,
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
            targetReps = sourceExercise.setReps[sourceSetIndex].takeUnless {
                isBodyweight || isTimed
            },
            isWarmup = false,
            isEcho = sourceExercise.programMode == ProgramMode.Echo,
            isJustLift = false,
            isBodyweight = isBodyweight,
            isTimed = isTimed,
            isAmrap = false,
            isCableExercise = !isBodyweight,
            physicalCableCount = sourceExercise.exercise.preferredCableCount,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(sourceCommand),
        )
        val normalPlan = RestTransitionPlan.NormalAdvance(
            transitionId = "scattered-normalized-transition",
            sourceExecutionId = "42",
            logicalSetKey = logicalSetKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(sourceExerciseIndex, sourceSetIndex),
            plannedSetId = null,
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routineId,
            routineSessionId = routineSessionId,
            routineExerciseId = sourceExercise.id,
            sourceExecutionId = "42",
            sourceStableSessionId = sourceStableSessionId,
            sourceAttemptNumber = 1,
            logicalSetKey = logicalSetKey,
            plannedSetId = null,
            sourceExerciseIndex = sourceExerciseIndex,
            sourceSetIndex = sourceSetIndex,
            sourceAuthority = sourceAuthority,
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = 42L,
                sourceStableSessionId = sourceStableSessionId,
                profileId = profileId,
                requiresMachine = !isBodyweight,
            ),
            attemptStates = listOf(
                PlannedSetAttemptState(
                    logicalSetKey = logicalSetKey,
                    nextAttemptNumber = 2,
                    acceptedDropCount = 0,
                ),
            ),
            restTransitionPlan = normalPlan,
            restDeadlineEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS + 30_000L,
            originalRestDurationSeconds = 60,
        )
    }

    private fun ActiveWorkoutRuntimeDocument.sourceCoordinatesForTest() = RestTransitionPlan.Coordinates(sourceExerciseIndex, sourceSetIndex)
}
