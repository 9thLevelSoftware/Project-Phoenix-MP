package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeAttributionEnvelope
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDiscoveryResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLookupKey
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRejection
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.BodyweightVariantOption
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RecommendationConfidence
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.SessionBodyweightAction
import com.devil.phoenixproject.domain.model.SessionBodyweightState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WeightAdjustmentRecommendation
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.util.BleConstants
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DropSetRuntimeRecoveryTest {
    private enum class TargetlessRecoveryCase { BODYWEIGHT, TIMED_CABLE, AMRAP }
    private enum class PlannedRecoveryCase { AMRAP_TIMED_CABLE, STANDARD_TIMED_CABLE, STANDARD_BODYWEIGHT }
    private enum class RestoredNavigationPlanCase { NORMAL, DECLINED }
    private enum class CandidatePolicyRecoveryCase {
        VALID,
        FEATURE_DISABLED,
        WRONG_REASON,
        TIMED_CABLE,
        BODYWEIGHT,
        DROP_BUDGET_EXHAUSTED,
        OMITTED_CANDIDATE,
        REVERSED_CANDIDATES,
    }
    private enum class SourceAuthorityMutation {
        WARMUP,
        ECHO,
        JUST_LIFT,
        CABLE_CLASSIFICATION,
        PHYSICAL_CABLE_COUNT,
        TEMPLATE_JUST_LIFT,
        TEMPLATE_AUTO_START,
        TEMPLATE_AMRAP,
        TEMPLATE_STALL_DETECTION,
        TEMPLATE_STOP_AT_TOP,
    }
    private enum class FinalAuthorityMutation { ACTIVE_PROFILE, CONFIGURATION_INPUT_EPOCH }
    private enum class RoutineAbandonmentPath {
        LOAD,
        LOAD_ASYNC,
        OVERVIEW,
        MODIFIED_OVERVIEW,
        EXIT,
        CLEAR,
    }

    @Test
    fun `v2 source authority round trips every immutable command field`() {
        val source = sourceAuthority()
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

        val encoded = json.encodeToString(source)
        val restored = json.decodeFromString<RestoredRetrySourceAuthoritySnapshot>(encoded)

        assertEquals(source, restored)
        assertEquals(source.toRestoredRetrySourceContext(), restored.toRestoredRetrySourceContext())
        assertEquals(ActiveWorkoutRuntimeDocument.CURRENT_VERSION, 2)
    }

    @Test
    fun `live completion mapping survives strict JSON and equals the immutable restored context`() {
        val logicalKey = LogicalSetKey(
            routineSessionId = "mapped-routine-session",
            routineExerciseId = "mapped-occurrence",
            setIndex = 4,
            setKind = SetType.AMRAP,
        )
        val identity = RoutineExecutionIdentity(
            profileId = "mapped-profile",
            routineId = "mapped-routine",
            routineSessionId = logicalKey.routineSessionId,
            routineExerciseId = logicalKey.routineExerciseId,
            logicalSetKey = logicalKey,
            plannedSetId = "mapped-planned-set",
            exerciseIndex = 3,
            setIndex = logicalKey.setIndex,
        )
        val commandTemplate = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 13,
            weightPerCableKg = 47.5f,
            activeRackItemIds = listOf("rack-a", "rack-b"),
            externalAddedLoadKg = 7.25f,
            counterweightKg = -2.5f,
            progressionRegressionKg = 1.75f,
            isJustLift = true,
            useAutoStart = true,
            stopAtTop = true,
            warmupReps = 5,
            selectedExerciseId = "selected-exercise",
            isAMRAP = true,
            lastUsedWeightKg = 51f,
            prWeightKg = 61f,
            stallDetectionEnabled = false,
            repCountTiming = RepCountTiming.BOTTOM,
            echoLevel = EchoLevel.EPIC,
            eccentricLoad = EccentricLoad.LOAD_150,
            justLiftRestSeconds = 95,
        )
        val completion = SetExecutionCompletion(
            lease = ExecutionLease(
                executionId = 987L,
                sessionId = "mapped-stable-session",
                profileId = "mapped-profile",
                requiresMachine = true,
                workingRepTarget = 13,
                isBodyweight = true,
                isJustLift = true,
                isAmrap = true,
                isTimedCable = true,
                activationCutoverTimestampMs = 123_456L,
            ),
            reason = SetEndReason.STALL_FAILURE,
            routineIdentity = identity,
            attemptNumber = 2,
            acceptedDropCount = 2,
            plannedSetType = SetType.AMRAP,
            programMode = ProgramMode.Echo,
            programmedBaseWeightPerCableKg = 52.5f,
            configuredStartWeightPerCableKg = 47.5f,
            progressionKg = -1.25f,
            actualReps = 9,
            targetReps = 13,
            isWarmup = true,
            isEcho = true,
            isJustLift = true,
            isBodyweight = true,
            isTimed = true,
            isAmrap = true,
            isCableExercise = true,
            physicalCableCount = 1,
            logicalPreRackCommandTemplate = commandTemplate,
        )
        val strictJson = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }

        val mappedSnapshot = completion.toRuntimeSourceAuthoritySnapshot()
        val decodedSnapshot = strictJson.decodeFromString<RestoredRetrySourceAuthoritySnapshot>(
            strictJson.encodeToString(mappedSnapshot),
        )

        assertEquals(completion.toRestoredRetrySourceContext(), mappedSnapshot.toRestoredRetrySourceContext())
        assertEquals(completion.toRestoredRetrySourceContext(), decodedSnapshot.toRestoredRetrySourceContext())
    }

    @Test
    fun `v2 teardown seed retains exact positive source identity and machine classification`() {
        val seed = RestoredTeardownSeedSnapshot(
            sourceExecutionId = 42L,
            sourceStableSessionId = "source-stable",
            profileId = "profile-a",
            requiresMachine = true,
        )

        assertEquals(42L, seed.sourceExecutionId)
        assertEquals("source-stable", seed.sourceStableSessionId)
        assertEquals("profile-a", seed.profileId)
        assertTrue(seed.requiresMachine)
    }

    @Test
    fun `restored publication installs exact teardown requirement before Resting is observable`() {
        val guard = WorkoutExecutionGuard()
        val epoch = guard.captureRecoveryPublicationEpoch()
        val configurationEpoch = guard.captureConfigurationInputEpoch()
        val claim = assertNotNull(
            guard.beginRecoveryPublication(
                expectedLease = null,
                expectedSupersessionEpoch = epoch,
                allowNoCurrentAfterOwnedInvalidation = false,
            ),
        )
        var restingPublished = false

        val owner = assertNotNull(
            guard.commitRestoredRuntimePublication(
                claim = claim,
                seed = RestoredTeardownSeed(
                    sourceExecutionId = 42L,
                    sourceStableSessionId = "source-stable",
                    profileId = "profile-a",
                    requiresMachine = true,
                ),
                expectedConfigurationInputEpoch = configurationEpoch,
            ) {
                assertIs<MachineTeardownState.TearingDown>(guard.machineTeardownState.value)
                restingPublished = true
            },
        )

        assertTrue(restingPublished)
        assertEquals(configurationEpoch + 1, guard.captureConfigurationInputEpoch())
        assertFalse(guard.isRestoredTeardownReady(owner))
        assertTrue(guard.beginExecution(executionSeed("successor")).isFailure)
        assertTrue(guard.markRestoredTeardownReady(owner))
        assertTrue(guard.isRestoredTeardownReady(owner))
    }

    @Test
    fun `discovery returns immutable persisted handle without publishing coordinator state`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val document = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = routine.exercises.single().id,
                sourceExercise = routine.exercises.single(),
                logicalSetKey = LogicalSetKey(
                    routineSessionId = "persisted-session",
                    routineExerciseId = routine.exercises.single().id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, document.routineSessionId, document)

            val beforeState = harness.coordinator.workoutState.value
            val beforeRoutine = harness.coordinator.loadedRoutine.value
            val beforeParameters = harness.coordinator.workoutParameters.value
            val discovery = assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(
                    routine = routine,
                    launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
                    cycleId = "cycle-a",
                    cycleDayNumber = 3,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(discovery.handle)

            assertEquals(profileId, handle.selectedProfileId)
            assertEquals(routine, handle.selectedRoutine)
            assertEquals(
                ActiveWorkoutRuntimeLookupKey(profileId, document.routineSessionId),
                handle.lookupKey,
            )
            assertEquals(2L, handle.rowRevision.documentVersion)
            assertEquals(RoutineLaunchOrigin.TRAINING_CYCLES, handle.launchOrigin)
            assertEquals("cycle-a", handle.cycleId)
            assertEquals(3, handle.cycleDayNumber)
            assertEquals(1, handle.progressInfo.currentExercise)
            assertEquals(1, handle.progressInfo.currentSet)
            assertEquals(beforeState, harness.coordinator.workoutState.value)
            assertEquals(beforeRoutine, harness.coordinator.loadedRoutine.value)
            assertEquals(beforeParameters, harness.coordinator.workoutParameters.value)
            assertNull(harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `attributable v1 discovery deletes only its captured key and returns validated manual coordinates`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val retained = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                sourceExercise = exercise,
                logicalSetKey = LogicalSetKey(
                    routineSessionId = "retained-runtime",
                    routineExerciseId = exercise.id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, retained.routineSessionId, retained)
            val rejected = harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = "captured-v1-key",
                reason = ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = "captured-v1-key",
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 1,
                ),
            )

            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            assertEquals(rejected.rowRevision, handle.rowRevision)
            assertEquals(RestTransitionPlan.Coordinates(0, 1), handle.manualRecoveryCoordinates)

            val result = assertIs<ActiveWorkoutRuntimeResumeResult.ManualSetReady>(
                harness.dwsm.resumeRoutine(handle),
            )

            assertEquals(0, result.exerciseIndex)
            assertEquals(1, result.setIndex)
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, "captured-v1-key"),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, retained.routineSessionId),
            )
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `attributable v1 with invalid coordinates deletes exact key and returns fresh start`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = "invalid-v1-coordinates",
                reason = ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = "invalid-v1-coordinates",
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 99,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            assertNull(handle.manualRecoveryCoordinates)

            assertIs<ActiveWorkoutRuntimeResumeResult.FreshStart>(harness.dwsm.resumeRoutine(handle))

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, "invalid-v1-coordinates"),
            )
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalid runtime cleanup failure remains inert and exact Resume retries cleanup`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val routineSessionId = "retry-invalid-runtime-cleanup"
            harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = routineSessionId,
                reason = ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 1,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            assertIs<ActiveWorkoutRuntimeResumeResult.RetryableFailure>(harness.dwsm.resumeRoutine(handle))

            assertEquals(
                RuntimeCleanupReason.INVALID_DOCUMENT,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            val retried = assertIs<ActiveWorkoutRuntimeResumeResult.ManualSetReady>(
                harness.dwsm.resumeRoutine(handle),
            )
            assertEquals(0, retried.exerciseIndex)
            assertEquals(1, retried.setIndex)
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalid runtime cleanup cancellation retains exact inert retry target and causal cancellation`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val routineSessionId = "cancel-invalid-runtime-cleanup"
            harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = routineSessionId,
                reason = ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 1,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            val expected = CancellationException("invalid cleanup cancelled")
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextConditionalDelete = expected

            val thrown = assertFailsWith<CancellationException> {
                harness.dwsm.resumeRoutine(handle)
            }

            assertTrue(thrown === expected)
            assertEquals(
                RuntimeCleanupReason.INVALID_DOCUMENT,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `explicit persisted discard awaits exact cleanup and retains retryable target`() = runTest {
        val harness = DWSMTestHarness(this)
        val connectionLogs = ConnectionLogRepository.instance
        try {
            connectionLogs.clearAll()
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val routineSessionId = "explicit-discard-rejected"
            harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = routineSessionId,
                reason = ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 1,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            assertIs<RoutineResumeDiscardResult.RetryableFailure>(
                harness.dwsm.discardRoutineResume(handle),
            )

            assertEquals(
                RuntimeCleanupReason.DISCARD_RECOVERY,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            assertIs<ActiveWorkoutRuntimeResumeResult.RetryableFailure>(
                harness.dwsm.resumeRoutine(handle),
            )
            assertEquals(
                RuntimeCleanupReason.DISCARD_RECOVERY,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )

            assertIs<RoutineResumeDiscardResult.Discarded>(
                harness.dwsm.discardRoutineResume(handle),
            )

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            val cleanupLog = connectionLogs.logs.value.single { entry ->
                entry.eventType == LogEventType.WORKOUT_PERSISTENCE &&
                    entry.message == "Active workout runtime cleanup"
            }
            assertEquals("reason=DISCARD_RECOVERY,planVariant=NONE", cleanupLog.details)
            assertNull(cleanupLog.deviceName)
            assertNull(cleanupLog.deviceAddress)
            val rendered = "${cleanupLog.message} ${cleanupLog.details}"
            listOf(profileId, routineSessionId, routine.id, exercise.id).forEach { sensitive ->
                assertFalse(rendered.contains(sensitive))
            }
        } finally {
            connectionLogs.clearAll()
            harness.cleanup()
        }
    }

    @Test
    fun `explicit persisted discard cannot delete a same key replacement`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val routineSessionId = "explicit-discard-stale"
            val document = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                sourceExercise = exercise,
                logicalSetKey = LogicalSetKey(
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, routineSessionId, document)
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            val replacement = document.copy(
                restDeadlineEpochMs = document.restDeadlineEpochMs?.plus(1_000L),
            )
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = {
                harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
                harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                    profileId,
                    routineSessionId,
                    replacement,
                )
            }

            assertIs<RoutineResumeDiscardResult.Superseded>(
                harness.dwsm.discardRoutineResume(handle),
            )

            assertEquals(
                replacement,
                assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
                ).document,
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
            harness.cleanup()
        }
    }

    @Test
    fun `stale rejected handle cannot delete a same millisecond replacement at its captured key`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val routineSessionId = "same-ms-rejected-key"
            harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = profileId,
                routineSessionId = routineSessionId,
                reason = ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = profileId,
                    routineId = routine.id,
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    sourceExerciseIndex = 0,
                    sourceSetIndex = 1,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )
            val replacement = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                sourceExercise = exercise,
                logicalSetKey = LogicalSetKey(
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = {
                harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
                harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                    profileId = profileId,
                    routineSessionId = routineSessionId,
                    document = replacement,
                )
            }

            assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(harness.dwsm.resumeRoutine(handle))

            assertEquals(
                replacement,
                assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId),
                ).document,
            )
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `authoritative in memory progress takes precedence over persisted candidate`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 2,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            assertTrue(harness.dwsm.loadRoutineAsync(routine))
            harness.coordinator._currentExerciseIndex.value = 1
            harness.coordinator._currentSetIndex.value = 1
            val selectedRoutine = routine.copy(name = "Selected request routine")
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val persisted = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = routine.exercises.first().id,
                sourceExercise = routine.exercises.first(),
                logicalSetKey = LogicalSetKey(
                    routineSessionId = "older-persisted-session",
                    routineExerciseId = routine.exercises.first().id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, persisted.routineSessionId, persisted)

            val discovery = assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(
                    routine = selectedRoutine,
                    launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
                    cycleId = "selected-cycle",
                    cycleDayNumber = 4,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.InMemory>(discovery.handle)

            assertEquals(profileId, handle.selectedProfileId)
            assertEquals(selectedRoutine, handle.selectedRoutine)
            assertEquals(harness.coordinator.loadedRoutine.value, handle.activeRoutineSnapshot)
            assertEquals(2, handle.progressInfo.currentExercise)
            assertEquals(2, handle.progressInfo.currentSet)
            assertEquals(RoutineLaunchOrigin.TRAINING_CYCLES, handle.launchOrigin)
            assertEquals("selected-cycle", handle.cycleId)
            assertEquals(4, handle.cycleDayNumber)
            assertTrue(harness.dwsm.isRoutineResumeHandleCurrent(handle))
            assertIs<ActiveWorkoutRuntimeResumeResult.Missing>(harness.dwsm.resumeRoutine(handle))

            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(reps = 17),
            )

            assertFalse(harness.dwsm.isRoutineResumeHandleCurrent(handle))
            assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(harness.dwsm.resumeRoutine(handle))
            assertIs<RoutineResumeDiscardResult.Superseded>(harness.dwsm.discardRoutineResume(handle))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `discovery rejects a selected routine owned by another profile`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val selected = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
            ).copy(profileId = "other-profile")
            val activeProfileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val document = runtimeDocument(
                profileId = activeProfileId,
                routineId = selected.id,
                routineExerciseId = selected.exercises.single().id,
                sourceExercise = selected.exercises.single(),
                logicalSetKey = LogicalSetKey(
                    routineSessionId = "wrong-profile-session",
                    routineExerciseId = selected.exercises.single().id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(
                activeProfileId,
                document.routineSessionId,
                document,
            )

            assertIs<RoutineResumeDiscovery.Superseded>(
                harness.dwsm.discoverRoutineResume(
                    routine = selected,
                    launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `discovery drops a persisted handle when manager generation changes during SQL read`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
            )
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val document = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = routine.exercises.single().id,
                sourceExercise = routine.exercises.single(),
                logicalSetKey = LogicalSetKey(
                    routineSessionId = "generation-session",
                    routineExerciseId = routine.exercises.single().id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                ),
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, document.routineSessionId, document)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            harness.fakeActiveWorkoutRuntimeRepository.discoverBlock = {
                entered.complete(Unit)
                release.await()
            }

            val discovery = async {
                harness.dwsm.discoverRoutineResume(
                    routine = routine,
                    launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                )
            }
            runCurrent()
            entered.await()
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(reps = 17),
            )
            release.complete(Unit)

            assertIs<RoutineResumeDiscovery.Superseded>(discovery.await())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `discovery failure is retryable and cannot be mistaken for missing progress`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            harness.fakeActiveWorkoutRuntimeRepository.discoverBlock = {
                throw IllegalStateException("discovery unavailable")
            }

            assertIs<RoutineResumeDiscovery.RetryableFailure>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            )
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.discoverBlock = null
            harness.cleanup()
        }
    }

    @Test
    fun `training cycle discovery without exact launch context is superseded`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)

            assertIs<RoutineResumeDiscovery.Superseded>(
                harness.dwsm.discoverRoutineResume(
                    routine = routine,
                    launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
                    cycleId = "",
                    cycleDayNumber = 0,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `recovery preparation resolves routine and rack without publishing coordinator state`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val poisonedRoutine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 2,
                setsPerExercise = 2,
                weightKg = 13f,
            ).copy(id = "poisoned-routine", name = "Poisoned")
            assertTrue(harness.dwsm.loadRoutineAsync(poisonedRoutine))
            harness.activeSessionEngine.mutateConfigurationInputs {
                harness.coordinator._currentExerciseIndex.value = 1
                harness.coordinator._currentSetIndex.value = 1
                harness.coordinator._workoutParameters.value =
                    harness.coordinator._workoutParameters.value.copy(reps = 99, weightPerCableKg = 47f)
                harness.coordinator.currentRoutineId = poisonedRoutine.id
                harness.coordinator.currentRoutineSessionId = "poisoned-session"
                harness.coordinator.routineLaunchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES
                harness.coordinator.activeCycleId = "poisoned-cycle"
                harness.coordinator.activeCycleDayNumber = 6
                harness.coordinator._activeRackItemIds.value = listOf("poisoned-rack")
                harness.coordinator.currentRackItemsJson = "poisoned-json"
            }
            val loadedBefore = harness.coordinator.loadedRoutine.value
            val exerciseBefore = harness.coordinator.currentExerciseIndex.value
            val setBefore = harness.coordinator.currentSetIndex.value
            val parametersBefore = harness.coordinator.workoutParameters.value
            val workoutStateBefore = harness.coordinator.workoutState.value
            val routineFlowBefore = harness.coordinator.routineFlowState.value
            val rackIdsBefore = harness.coordinator.activeRackItemIds.value
            val rackJsonBefore = harness.coordinator.currentRackItemsJson
            val routineIdBefore = harness.coordinator.currentRoutineId
            val routineSessionBefore = harness.coordinator.currentRoutineSessionId
            val launchBefore = harness.coordinator.routineLaunchOrigin
            val cycleBefore = harness.coordinator.activeCycleId to harness.coordinator.activeCycleDayNumber
            val epochBefore = harness.activeSessionEngine.executionGuard.captureConfigurationInputEpoch()
            val candidate = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            ).copy(id = "candidate-routine", name = "Candidate")

            val preparation = assertNotNull(
                harness.routineFlowManager.prepareRoutineForRecovery(
                    routine = candidate,
                    exerciseIndex = 0,
                    setIndex = 0,
                    launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                    cycleId = null,
                    cycleDayNumber = null,
                ),
            )

            assertEquals(candidate.id, preparation.resolvedRoutine.id)
            assertEquals(candidate.exercises.single().id, preparation.sourceExercise.id)
            assertEquals(25f, preparation.programmedBaseWeightPerCableKg)
            assertEquals(loadedBefore, harness.coordinator.loadedRoutine.value)
            assertEquals(exerciseBefore, harness.coordinator.currentExerciseIndex.value)
            assertEquals(setBefore, harness.coordinator.currentSetIndex.value)
            assertEquals(parametersBefore, harness.coordinator.workoutParameters.value)
            assertEquals(workoutStateBefore, harness.coordinator.workoutState.value)
            assertEquals(routineFlowBefore, harness.coordinator.routineFlowState.value)
            assertEquals(rackIdsBefore, harness.coordinator.activeRackItemIds.value)
            assertEquals(rackJsonBefore, harness.coordinator.currentRackItemsJson)
            assertEquals(routineIdBefore, harness.coordinator.currentRoutineId)
            assertEquals(routineSessionBefore, harness.coordinator.currentRoutineSessionId)
            assertEquals(launchBefore, harness.coordinator.routineLaunchOrigin)
            assertEquals(cycleBefore, harness.coordinator.activeCycleId to harness.coordinator.activeCycleDayNumber)
            assertEquals(epochBefore, harness.activeSessionEngine.executionGuard.captureConfigurationInputEpoch())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cold discovery is read only and explicit Resume hydrates rest without starting hardware`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val key = LogicalSetKey(
                routineSessionId = "cold-session",
                routineExerciseId = exercise.id,
                setIndex = 0,
                setKind = SetType.STANDARD,
            )
            val offeredDocument = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                logicalSetKey = key,
                sourceExercise = exercise,
            )
            val document = offeredDocument.copy(
                restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    offeredDocument.restTransitionPlan,
                ).normalAdvance,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine("source-stable", key.routineSessionId)
            harness.fakeCompletedSetRepo.saveCompletedSet(
                CompletedSet(
                    id = "completed-source",
                    sessionId = "source-stable",
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
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, key.routineSessionId, document)

            val discovery = assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(
                    routine = routine,
                    launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                ),
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(discovery.handle)

            assertEquals(ActiveWorkoutRuntimeLookupKey(profileId, key.routineSessionId), handle.lookupKey)
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())

            val resumed = harness.dwsm.resumeRoutine(handle)
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(resumed)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(document.restTransitionPlan, harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Resume rejects poisoned source mode facts and deletes only the captured row`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val retainedKey = LogicalSetKey("retained-session", exercise.id, 0, SetType.STANDARD)
            val rejectedKey = LogicalSetKey("rejected-session", exercise.id, 0, SetType.STANDARD)
            val retainedOffered = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                logicalSetKey = retainedKey,
                sourceExercise = exercise,
            )
            val retained = retainedOffered.copy(
                restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    retainedOffered.restTransitionPlan,
                ).normalAdvance,
            )
            val rejectedOffered = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                logicalSetKey = rejectedKey,
                sourceExercise = exercise,
            )
            val rejected = rejectedOffered.copy(
                sourceAuthority = rejectedOffered.sourceAuthority.copy(isTimed = true),
                restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    rejectedOffered.restTransitionPlan,
                ).normalAdvance,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine("source-stable", rejectedKey.routineSessionId)
            harness.fakeCompletedSetRepo.saveCompletedSet(
                CompletedSet(
                    id = "durable-rejected-source",
                    sessionId = "source-stable",
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
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, retainedKey.routineSessionId, retained)
            harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, rejectedKey.routineSessionId, rejected)
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.ManualSetReady>(harness.dwsm.resumeRoutine(handle))

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, rejectedKey.routineSessionId),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(profileId, retainedKey.routineSessionId),
            )
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Resume fails closed for every strict source and command flag mutation`() = runTest {
        val outcomes = mutableMapOf<SourceAuthorityMutation, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>>()
        enumValues<SourceAuthorityMutation>().forEachIndexed { caseIndex, mutation ->
            val harness = DWSMTestHarness(this)
            try {
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                )
                val exercise = routine.exercises.single()
                val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
                val routineSessionId = "strict-source-${mutation.name.lowercase()}"
                val stableSessionId = "strict-source-stable-$caseIndex"
                val key = LogicalSetKey(routineSessionId, exercise.id, 0, SetType.STANDARD)
                val offered = runtimeDocument(
                    profileId = profileId,
                    routineId = routine.id,
                    routineExerciseId = exercise.id,
                    logicalSetKey = key,
                    sourceExercise = exercise,
                )
                val baseSource = offered.sourceAuthority.copy(sourceStableSessionId = stableSessionId)
                val poisonedSource = when (mutation) {
                    SourceAuthorityMutation.WARMUP -> baseSource.copy(isWarmup = true)

                    SourceAuthorityMutation.ECHO -> baseSource.copy(isEcho = true)

                    SourceAuthorityMutation.JUST_LIFT -> baseSource.copy(isJustLift = true)

                    SourceAuthorityMutation.CABLE_CLASSIFICATION -> baseSource.copy(isCableExercise = false)

                    SourceAuthorityMutation.PHYSICAL_CABLE_COUNT -> baseSource.copy(
                        physicalCableCount = (baseSource.physicalCableCount ?: 0) + 1,
                    )

                    SourceAuthorityMutation.TEMPLATE_JUST_LIFT -> baseSource.copy(
                        commandTemplate = baseSource.commandTemplate.copy(isJustLift = true),
                    )

                    SourceAuthorityMutation.TEMPLATE_AUTO_START -> baseSource.copy(
                        commandTemplate = baseSource.commandTemplate.copy(useAutoStart = true),
                    )

                    SourceAuthorityMutation.TEMPLATE_AMRAP -> baseSource.copy(
                        commandTemplate = baseSource.commandTemplate.copy(isAMRAP = true),
                    )

                    SourceAuthorityMutation.TEMPLATE_STALL_DETECTION -> baseSource.copy(
                        commandTemplate = baseSource.commandTemplate.copy(
                            stallDetectionEnabled = !baseSource.commandTemplate.stallDetectionEnabled,
                        ),
                    )

                    SourceAuthorityMutation.TEMPLATE_STOP_AT_TOP -> baseSource.copy(
                        commandTemplate = baseSource.commandTemplate.copy(
                            stopAtTop = !baseSource.commandTemplate.stopAtTop,
                        ),
                    )
                }
                val document = offered.copy(
                    sourceStableSessionId = stableSessionId,
                    sourceAuthority = poisonedSource,
                    teardownSeed = offered.teardownSeed.copy(
                        sourceStableSessionId = stableSessionId,
                        requiresMachine = poisonedSource.isCableExercise,
                    ),
                    restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                        offered.restTransitionPlan,
                    ).normalAdvance,
                )
                harness.fakeCompletedSetRepo.setSessionRoutine(stableSessionId, routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "durable-strict-source-$caseIndex",
                        sessionId = stableSessionId,
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

                val result = harness.dwsm.resumeRoutine(handle)
                val retained = harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId) is
                    ActiveWorkoutRuntimeLoadResult.Loaded
                outcomes[mutation] = result to retained
            } finally {
                harness.cleanup()
            }
        }

        val rejected: Pair<ActiveWorkoutRuntimeResumeResult, Boolean> =
            ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 0) to false
        val expected: Map<SourceAuthorityMutation, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>> =
            enumValues<SourceAuthorityMutation>().associateWith { rejected }
        assertEquals(expected, outcomes)
    }

    @Test
    fun `Resume rejects every out of bounds or semantically wrong attempt state key`() = runTest {
        listOf(
            99 to SetType.AMRAP,
            1 to SetType.AMRAP,
        ).forEachIndexed { caseIndex, (invalidSetIndex, invalidSetType) ->
            val harness = DWSMTestHarness(this)
            try {
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                )
                val exercise = routine.exercises.single()
                val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
                val sourceKey = LogicalSetKey(
                    routineSessionId = "invalid-attempt-session-$caseIndex",
                    routineExerciseId = exercise.id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                )
                val offered = runtimeDocument(
                    profileId = profileId,
                    routineId = routine.id,
                    routineExerciseId = exercise.id,
                    logicalSetKey = sourceKey,
                    sourceExercise = exercise,
                )
                val invalidKey = sourceKey.copy(
                    setIndex = invalidSetIndex,
                    setKind = invalidSetType,
                )
                val document = offered.copy(
                    attemptStates = offered.attemptStates + PlannedSetAttemptState(
                        logicalSetKey = invalidKey,
                        nextAttemptNumber = 1,
                        acceptedDropCount = 0,
                    ),
                    restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                        offered.restTransitionPlan,
                    ).normalAdvance,
                )
                harness.fakeCompletedSetRepo.setSessionRoutine("source-stable", sourceKey.routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "durable-invalid-attempt-$caseIndex",
                        sessionId = "source-stable",
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
                harness.fakeActiveWorkoutRuntimeRepository.replace(
                    profileId,
                    sourceKey.routineSessionId,
                    document,
                )
                val handle = assertIs<RoutineResumeHandle.Persisted>(
                    assertIs<RoutineResumeDiscovery.Candidate>(
                        harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                    ).handle,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.ManualSetReady>(harness.dwsm.resumeRoutine(handle))
                assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(profileId, sourceKey.routineSessionId),
                )
                assertNull(harness.restTransitionPlan.value)
                assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `Resume accepts production targetless bodyweight timed cable and AMRAP normal plans`() = runTest {
        enumValues<TargetlessRecoveryCase>().forEachIndexed { caseIndex, recoveryCase ->
            val harness = DWSMTestHarness(this)
            try {
                val baseRoutine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 1,
                    weightKg = 25f,
                    repsPerSet = 10,
                )
                val baseExercise = baseRoutine.exercises.single()
                val exercise = when (recoveryCase) {
                    TargetlessRecoveryCase.BODYWEIGHT -> baseExercise.copy(
                        exercise = baseExercise.exercise.copy(isBodyweightOverride = true),
                    )

                    TargetlessRecoveryCase.TIMED_CABLE -> baseExercise.copy(
                        exercise = baseExercise.exercise.copy(isBodyweightOverride = false),
                        duration = 45,
                    )

                    TargetlessRecoveryCase.AMRAP -> baseExercise.copy(
                        exercise = baseExercise.exercise.copy(isBodyweightOverride = false),
                        setReps = listOf(10),
                        isAMRAP = true,
                    )
                }
                val routine = baseRoutine.copy(exercises = listOf(exercise))
                val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
                val setType = if (recoveryCase == TargetlessRecoveryCase.AMRAP) SetType.AMRAP else SetType.STANDARD
                val routineSessionId = "targetless-${recoveryCase.name.lowercase()}"
                val key = LogicalSetKey(routineSessionId, exercise.id, 0, setType)
                val offered = runtimeDocument(
                    profileId = profileId,
                    routineId = routine.id,
                    routineExerciseId = exercise.id,
                    logicalSetKey = key,
                    sourceExercise = exercise,
                )
                val stableSessionId = "targetless-source-$caseIndex"
                val document = offered.copy(
                    sourceStableSessionId = stableSessionId,
                    sourceAuthority = offered.sourceAuthority.copy(
                        sourceStableSessionId = stableSessionId,
                        targetReps = null,
                    ),
                    teardownSeed = offered.teardownSeed.copy(sourceStableSessionId = stableSessionId),
                    restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                        offered.restTransitionPlan,
                    ).normalAdvance,
                )
                harness.fakeCompletedSetRepo.setSessionRoutine(stableSessionId, routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "durable-targetless-$caseIndex",
                        sessionId = stableSessionId,
                        plannedSetId = null,
                        setNumber = 0,
                        setType = setType,
                        actualReps = 6,
                        actualWeightKg = exercise.weightPerCableKg,
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

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(handle),
                    recoveryCase.name,
                )
                assertEquals(document.restTransitionPlan, harness.restTransitionPlan.value, recoveryCase.name)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `Resume preserves planned set kind while validating targetless execution semantics`() = runTest {
        val outcomes = mutableMapOf<PlannedRecoveryCase, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>>()
        enumValues<PlannedRecoveryCase>().forEachIndexed { caseIndex, recoveryCase ->
            val harness = DWSMTestHarness(this)
            try {
                val baseRoutine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 1,
                    weightKg = 25f,
                    repsPerSet = 10,
                )
                val baseExercise = baseRoutine.exercises.single()
                val exercise = when (recoveryCase) {
                    PlannedRecoveryCase.AMRAP_TIMED_CABLE,
                    PlannedRecoveryCase.STANDARD_TIMED_CABLE,
                    -> baseExercise.copy(
                        exercise = baseExercise.exercise.copy(isBodyweightOverride = false),
                        duration = 45,
                    )

                    PlannedRecoveryCase.STANDARD_BODYWEIGHT -> baseExercise.copy(
                        exercise = baseExercise.exercise.copy(isBodyweightOverride = true),
                    )
                }
                val routine = baseRoutine.copy(exercises = listOf(exercise))
                val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
                val routineSessionId = "planned-${recoveryCase.name.lowercase()}"
                val plannedSet = when (recoveryCase) {
                    PlannedRecoveryCase.AMRAP_TIMED_CABLE -> PlannedSet.amrap(
                        id = "planned-amrap-timed",
                        routineExerciseId = exercise.id,
                        setNumber = 0,
                        targetWeightKg = exercise.weightPerCableKg,
                    )

                    PlannedRecoveryCase.STANDARD_TIMED_CABLE,
                    PlannedRecoveryCase.STANDARD_BODYWEIGHT,
                    -> PlannedSet.standard(
                        id = "planned-standard-$caseIndex",
                        routineExerciseId = exercise.id,
                        setNumber = 0,
                        targetReps = 10,
                        targetWeightKg = exercise.weightPerCableKg,
                    )
                }
                val routineSemanticKey = LogicalSetKey(
                    routineSessionId = routineSessionId,
                    routineExerciseId = exercise.id,
                    setIndex = 0,
                    setKind = SetType.STANDARD,
                )
                val offered = runtimeDocument(
                    profileId = profileId,
                    routineId = routine.id,
                    routineExerciseId = exercise.id,
                    logicalSetKey = routineSemanticKey,
                    sourceExercise = exercise,
                )
                val plannedKey = routineSemanticKey.copy(setKind = plannedSet.setType)
                val plannedIdentity = offered.sourceAuthority.routineIdentity.copy(
                    logicalSetKey = plannedKey,
                    plannedSetId = plannedSet.id,
                )
                val stableSessionId = "planned-source-$caseIndex"
                val normal = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    offered.restTransitionPlan,
                ).normalAdvance.copy(
                    logicalSetKey = plannedKey,
                    plannedSetId = plannedSet.id,
                )
                val document = offered.copy(
                    sourceStableSessionId = stableSessionId,
                    logicalSetKey = plannedKey,
                    plannedSetId = plannedSet.id,
                    sourceAuthority = offered.sourceAuthority.copy(
                        sourceStableSessionId = stableSessionId,
                        routineIdentity = plannedIdentity,
                        plannedSetTypeName = plannedSet.setType.name,
                        targetReps = null,
                        isAmrap = plannedSet.setType == SetType.AMRAP,
                    ),
                    teardownSeed = offered.teardownSeed.copy(sourceStableSessionId = stableSessionId),
                    attemptStates = listOf(
                        PlannedSetAttemptState(
                            logicalSetKey = plannedKey,
                            nextAttemptNumber = 2,
                            acceptedDropCount = 0,
                        ),
                    ),
                    restTransitionPlan = normal,
                )
                harness.fakeCompletedSetRepo.savePlannedSet(plannedSet)
                harness.fakeCompletedSetRepo.setSessionRoutine(stableSessionId, routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "durable-planned-$caseIndex",
                        sessionId = stableSessionId,
                        plannedSetId = plannedSet.id,
                        setNumber = 0,
                        setType = plannedSet.setType,
                        actualReps = 6,
                        actualWeightKg = exercise.weightPerCableKg,
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

                outcomes[recoveryCase] =
                    harness.dwsm.resumeRoutine(handle) to
                    (document.restTransitionPlan == harness.restTransitionPlan.value)
            } finally {
                harness.cleanup()
            }
        }
        assertTrue(
            outcomes.values.all { (result, planMatches) ->
                result is ActiveWorkoutRuntimeResumeResult.RestoredRest && planMatches
            },
            outcomes.mapValues { (_, outcome) -> outcome.first::class.simpleName to outcome.second }.toString(),
        )
    }

    @Test
    fun `Resume requires the exact full ordered drop offer from the current eligibility policy`() = runTest {
        val outcomes = mutableMapOf<CandidatePolicyRecoveryCase, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>>()
        enumValues<CandidatePolicyRecoveryCase>().forEachIndexed { caseIndex, recoveryCase ->
            val featureEnabled = recoveryCase != CandidatePolicyRecoveryCase.FEATURE_DISABLED
            val harness = DWSMTestHarness(
                testScope = this,
                dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                    DropSetFeatureGate { featureEnabled },
                    DropSetCandidateResolver(),
                ),
                dropSetConfigurationProvider = {
                    DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 5f)
                },
            )
            try {
                val baseRoutine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                    repsPerSet = 10,
                )
                val exercise = baseRoutine.exercises.single().let { base ->
                    when (recoveryCase) {
                        CandidatePolicyRecoveryCase.TIMED_CABLE -> base.copy(duration = 45)

                        CandidatePolicyRecoveryCase.BODYWEIGHT -> base.copy(
                            exercise = base.exercise.copy(isBodyweightOverride = true),
                        )

                        else -> base
                    }
                }
                val routine = baseRoutine.copy(exercises = listOf(exercise))
                val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
                val routineSessionId = "candidate-policy-${recoveryCase.name.lowercase()}"
                val sourceStableSessionId = "candidate-source-$caseIndex"
                val key = LogicalSetKey(routineSessionId, exercise.id, 0, SetType.STANDARD)
                val offered = runtimeDocument(
                    profileId = profileId,
                    routineId = routine.id,
                    routineExerciseId = exercise.id,
                    logicalSetKey = key,
                    sourceExercise = exercise,
                )
                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(offered.restTransitionPlan)
                val fullOrderedCandidates = listOf(
                    DropSetCandidate(DropPercentage.TEN, 22.5f, 0.9f),
                    DropSetCandidate(DropPercentage.TWENTY, 20f, 0.8f),
                    DropSetCandidate(DropPercentage.THIRTY, 17.5f, 0.7f),
                )
                val candidates = when (recoveryCase) {
                    CandidatePolicyRecoveryCase.OMITTED_CANDIDATE -> fullOrderedCandidates.dropLast(1)
                    CandidatePolicyRecoveryCase.REVERSED_CANDIDATES -> fullOrderedCandidates.reversed()
                    else -> fullOrderedCandidates
                }
                val acceptedDropCount = if (
                    recoveryCase == CandidatePolicyRecoveryCase.DROP_BUDGET_EXHAUSTED
                ) {
                    2
                } else {
                    0
                }
                val document = offered.copy(
                    sourceStableSessionId = sourceStableSessionId,
                    sourceAuthority = offered.sourceAuthority.copy(
                        sourceStableSessionId = sourceStableSessionId,
                        reasonName = if (recoveryCase == CandidatePolicyRecoveryCase.WRONG_REASON) {
                            SetEndReason.USER_STOPPED.name
                        } else {
                            offered.sourceAuthority.reasonName
                        },
                        acceptedDropCount = acceptedDropCount,
                        targetReps = offered.sourceAuthority.targetReps.takeUnless {
                            recoveryCase == CandidatePolicyRecoveryCase.TIMED_CABLE ||
                                recoveryCase == CandidatePolicyRecoveryCase.BODYWEIGHT
                        },
                    ),
                    teardownSeed = offered.teardownSeed.copy(sourceStableSessionId = sourceStableSessionId),
                    attemptStates = offered.attemptStates.map { state ->
                        state.copy(acceptedDropCount = acceptedDropCount)
                    },
                    restTransitionPlan = unresolved.copy(candidates = candidates),
                )
                harness.fakeCompletedSetRepo.setSessionRoutine(sourceStableSessionId, routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "durable-candidate-$caseIndex",
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

                val result = harness.dwsm.resumeRoutine(handle)
                val retained = harness.fakeActiveWorkoutRuntimeRepository.load(profileId, routineSessionId) is
                    ActiveWorkoutRuntimeLoadResult.Loaded
                outcomes[recoveryCase] = result to retained
            } finally {
                harness.cleanup()
            }
        }

        val rejected = ActiveWorkoutRuntimeResumeResult.ManualSetReady(exerciseIndex = 0, setIndex = 0) to false
        assertEquals(
            mapOf(
                CandidatePolicyRecoveryCase.VALID to (ActiveWorkoutRuntimeResumeResult.RestoredRest to true),
                CandidatePolicyRecoveryCase.FEATURE_DISABLED to rejected,
                CandidatePolicyRecoveryCase.WRONG_REASON to rejected,
                CandidatePolicyRecoveryCase.TIMED_CABLE to rejected,
                CandidatePolicyRecoveryCase.BODYWEIGHT to rejected,
                CandidatePolicyRecoveryCase.DROP_BUDGET_EXHAUSTED to rejected,
                CandidatePolicyRecoveryCase.OMITTED_CANDIDATE to rejected,
                CandidatePolicyRecoveryCase.REVERSED_CANDIDATES to rejected,
            ),
            outcomes,
        )
    }

    @Test
    fun `SQL key identity rejection happens before recovery preparation or planned reads`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val exercise = routine.exercises.single()
            val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
            val embeddedKey = LogicalSetKey("embedded-session", exercise.id, 0, SetType.STANDARD)
            val offered = runtimeDocument(
                profileId = profileId,
                routineId = routine.id,
                routineExerciseId = exercise.id,
                logicalSetKey = embeddedKey,
                sourceExercise = exercise,
            )
            val document = offered.copy(
                restTransitionPlan = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    offered.restTransitionPlan,
                ).normalAdvance,
            )
            harness.fakeActiveWorkoutRuntimeRepository.replace(
                profileId = profileId,
                routineSessionId = "sql-key-session",
                document = document,
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
                ).handle,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.ManualSetReady>(harness.dwsm.resumeRoutine(handle))

            assertEquals(0, harness.activeSessionEngine.recoveryPreparationCallsForTest)
            assertTrue(harness.fakeCompletedSetRepo.plannedSetReadRequests.isEmpty())
            assertNull(harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation after final reload cannot publish and a fresh Resume does not inherit cancellation`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val installed = installValidNormalRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "cancelled-final-reload",
            )
            harness.fakeActiveWorkoutRuntimeRepository.loadCalls = 0
            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { call, _, _ ->
                if (call == 2) currentCoroutineContext()[Job]?.cancel()
            }

            val cancelled = async { harness.dwsm.resumeRoutine(installed.handle) }
            assertFailsWith<CancellationException> { cancelled.await() }
            advanceUntilIdle()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertNull(harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())

            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = null
            harness.fakeActiveWorkoutRuntimeRepository.loadCalls = 0
            val retried = withTimeout(1_000) {
                harness.dwsm.resumeRoutine(installed.handle)
            }
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(retried)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(installed.document.restTransitionPlan, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `a newer persisted Resume deterministically supersedes an older blocked attempt`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseOlder = CompletableDeferred<Unit>()
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val older = installValidNormalRuntime(harness, routine, "older-resume")
            val newer = installValidNormalRuntime(harness, routine, "newer-resume")
            val olderEnteredFinalLoad = CompletableDeferred<Unit>()
            val newerSelected = CompletableDeferred<Unit>()
            val loadsBySession = mutableMapOf<String, Int>()
            harness.fakeActiveWorkoutRuntimeRepository.loadCalls = 0
            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { _, key, _ ->
                val sessionCalls = (loadsBySession[key.routineSessionId] ?: 0) + 1
                loadsBySession[key.routineSessionId] = sessionCalls
                when {
                    key.routineSessionId == older.document.routineSessionId && sessionCalls == 2 -> {
                        olderEnteredFinalLoad.complete(Unit)
                        releaseOlder.await()
                    }
                }
            }
            harness.activeSessionEngine.afterRuntimeResumeSelectionForTest = { selected ->
                if (selected == newer.handle) newerSelected.complete(Unit)
            }

            val olderResume = async { harness.dwsm.resumeRoutine(older.handle) }
            runCurrent()
            withTimeout(1_000) { olderEnteredFinalLoad.await() }
            val newerResume = async { harness.dwsm.resumeRoutine(newer.handle) }
            runCurrent()
            withTimeout(1_000) { newerSelected.await() }

            releaseOlder.complete(Unit)
            runCurrent()
            val olderResult = withTimeout(1_000) { olderResume.await() }
            val newerResult = withTimeout(1_000) { newerResume.await() }
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(olderResult)
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(newerResult)
            assertEquals(newer.document.restTransitionPlan, harness.restTransitionPlan.value)
            assertEquals(newer.document.routineSessionId, harness.coordinator.currentRoutineSessionId)
        } finally {
            releaseOlder.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `restored publication never exposes SetReady while workout state is Idle`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val installed = installValidNormalRuntime(harness, routine, "state-publication-order")
            var stateBeforeSetReady: WorkoutState? = null
            harness.activeSessionEngine.beforeRestoredRoutineFlowPublicationForTest = {
                stateBeforeSetReady = harness.coordinator.workoutState.value
            }

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )

            assertIs<WorkoutState.Resting>(stateBeforeSetReady)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `rack authority changing after preparation supersedes hydration before publication`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            val installed = installValidNormalRuntime(harness, routine, "rack-stamp-change")
            harness.fakeActiveWorkoutRuntimeRepository.loadCalls = 0
            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { call, _, _ ->
                if (call == 2) {
                    harness.fakeEquipmentRackRepo.saveItems(
                        listOf(RackItem(id = "new-rack-input", name = "New rack input", weightKg = 2f)),
                    )
                }
            }

            val result = harness.dwsm.resumeRoutine(installed.handle)

            assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(result)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertNull(harness.restTransitionPlan.value)
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.handle.lookupKey.profileId,
                    installed.handle.lookupKey.routineSessionId,
                ),
            )
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `blocked Resume revalidates active profile and configuration epoch before publication`() = runTest {
        val outcomes = mutableMapOf<FinalAuthorityMutation, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>>()
        enumValues<FinalAuthorityMutation>().forEachIndexed { caseIndex, mutation ->
            val harness = DWSMTestHarness(this)
            val releaseFinalLoad = CompletableDeferred<Unit>()
            try {
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                )
                val installed = installValidNormalRuntime(
                    harness,
                    routine,
                    "final-authority-${mutation.name.lowercase()}",
                )
                val enteredFinalLoad = CompletableDeferred<Unit>()
                harness.fakeActiveWorkoutRuntimeRepository.loadCalls = 0
                harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { call, _, _ ->
                    if (call == 2) {
                        enteredFinalLoad.complete(Unit)
                        releaseFinalLoad.await()
                    }
                }

                val resume = async { harness.dwsm.resumeRoutine(installed.handle) }
                runCurrent()
                withTimeout(1_000) { enteredFinalLoad.await() }
                when (mutation) {
                    FinalAuthorityMutation.ACTIVE_PROFILE ->
                        harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-$caseIndex")

                    FinalAuthorityMutation.CONFIGURATION_INPUT_EPOCH ->
                        harness.activeSessionEngine.supersedeConfigurationInputIntent()
                }
                releaseFinalLoad.complete(Unit)
                val result = withTimeout(1_000) { resume.await() }
                advanceUntilIdle()

                val retained = harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.handle.lookupKey.profileId,
                    installed.handle.lookupKey.routineSessionId,
                ) is ActiveWorkoutRuntimeLoadResult.Loaded
                outcomes[mutation] = result to retained
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value, mutation.name)
                assertNull(harness.restTransitionPlan.value, mutation.name)
                assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty(), mutation.name)
                assertTrue(harness.fakeBleRepo.workoutParameters.isEmpty(), mutation.name)
            } finally {
                releaseFinalLoad.complete(Unit)
                harness.cleanup()
            }
        }

        val expected: Map<FinalAuthorityMutation, Pair<ActiveWorkoutRuntimeResumeResult, Boolean>> =
            enumValues<FinalAuthorityMutation>().associateWith {
                ActiveWorkoutRuntimeResumeResult.Superseded to true
            }
        assertEquals(expected, outcomes)
    }

    @Test
    fun `restored unresolved Accept persists Accepted at zero without granting start permission`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidUnresolvedRuntime(harness, routine, "restored-accept-zero")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertEquals(0, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val configurationsBefore = harness.fakeBleRepo.workoutParameters.size

            val reduction = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            )

            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(
                assertIs<RestTransitionReduction.Changed>(reduction).plan,
            )
            assertEquals(DropPercentage.TWENTY, accepted.percentage)
            assertEquals(20f, accepted.resolvedWeightPerCableKg)
            assertEquals(0.8f, accepted.resultingExerciseMultiplier)
            assertEquals(2, accepted.nextAttemptNumber)
            assertEquals(accepted, harness.restTransitionPlan.value)
            val persisted = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(accepted, persisted.restTransitionPlan)
            assertEquals(
                PlannedSetAttemptState(accepted.logicalSetKey, nextAttemptNumber = 3, acceptedDropCount = 1),
                persisted.attemptStates.single { it.logicalSetKey == accepted.logicalSetKey },
            )
            assertEquals(0.8f, persisted.exerciseLoadOverlays.single().multiplier)
            assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)

            val duplicate = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            )
            assertIs<RestTransitionReduction.NoOp>(duplicate)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored unresolved Decline persists exact plan without resolving navigation`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidUnresolvedRuntime(harness, routine, "restored-decline")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val configurationsBefore = harness.fakeBleRepo.workoutParameters.size

            val reduction = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Decline(unresolved.actionIdentity()),
            )

            val declined = assertIs<RestTransitionPlan.Declined>(
                assertIs<RestTransitionReduction.Changed>(reduction).plan,
            )
            assertEquals(unresolved.normalAdvance, declined.normalAdvance)
            assertEquals(unresolved.offerId, declined.offerId)
            assertEquals(declined, harness.restTransitionPlan.value)
            val persisted = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(declined, persisted.restTransitionPlan)
            assertEquals(installed.document.attemptStates, persisted.attemptStates)
            assertEquals(installed.document.exerciseLoadOverlays, persisted.exerciseLoadOverlays)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity())),
            )
            assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored Normal and Declined Skip clear durably then navigate once without CONFIG`() = runTest {
        enumValues<RestoredNavigationPlanCase>().forEach { planCase ->
            val harness = DWSMTestHarness(this)
            try {
                harness.setActiveSummaryCountdownSeconds(-1)
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                    repsPerSet = 10,
                )
                val installed = installValidNavigationRuntime(
                    harness = harness,
                    routine = routine,
                    routineSessionId = "restored-${planCase.name.lowercase()}-skip",
                    planCase = planCase,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
                runCurrent()
                val plan = assertNotNull(harness.restTransitionPlan.value)
                val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                val configurationsBefore = harness.fakeBleRepo.workoutParameters.size
                var observedDurableClear = false
                harness.dwsm.restTransitionNavigationLookupObserverForTest = {
                    observedDurableClear = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    )?.restTransitionPlan == null
                }

                val first = async {
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }
                val second = async {
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }
                val reductions = listOf(first.await(), second.await())

                assertEquals(1, reductions.count { it is RestTransitionReduction.DispatchNormal })
                assertEquals(1, reductions.count { it is RestTransitionReduction.NoOp })
                assertTrue(observedDurableClear)
                assertNull(
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    )?.restTransitionPlan,
                )
                assertNull(harness.restTransitionPlan.value)
                assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
                assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
                assertEquals(0, harness.coordinator.currentExerciseIndex.value)
                assertEquals(1, harness.coordinator.currentSetIndex.value)
                val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
                assertEquals(0, setReady.exerciseIndex)
                assertEquals(1, setReady.setIndex)
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
                assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
            } finally {
                harness.dwsm.restTransitionNavigationLookupObserverForTest = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored navigation keeps unfinished RESET retryable after Skip revokes workout actions`() = runTest {
        enumValues<RestoredNavigationPlanCase>().forEach { planCase ->
            val harness = DWSMTestHarness(this)
            val initialStopEntered = CompletableDeferred<Unit>()
            val releaseInitialStop = CompletableDeferred<Unit>()
            try {
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                harness.fakeBleRepo.stopWorkoutBlock = {
                    initialStopEntered.complete(Unit)
                    releaseInitialStop.await()
                    Result.failure(IllegalStateException("restored reset failed after navigation"))
                }
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                    repsPerSet = 10,
                )
                val installed = installValidNavigationRuntime(
                    harness = harness,
                    routine = routine,
                    routineSessionId = "restored-${planCase.name.lowercase()}-teardown-retry",
                    planCase = planCase,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
                runCurrent()
                withTimeout(1_000) { initialStopEntered.await() }
                assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
                val plan = assertNotNull(harness.restTransitionPlan.value)

                assertIs<RestTransitionReduction.DispatchNormal>(
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity())),
                )
                assertNull(harness.restTransitionPlan.value)
                assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
                assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

                releaseInitialStop.complete(Unit)
                runCurrent()
                assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)

                harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }
                harness.dwsm.retryMachineTeardown()
                harness.dwsm.retryMachineTeardown()
                runCurrent()

                assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount, planCase.name)
                assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
                assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
                assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            } finally {
                releaseInitialStop.complete(Unit)
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored final Normal Skip clears durably then completes without CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 1,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidNavigationRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-final-normal-skip",
                planCase = RestoredNavigationPlanCase.NORMAL,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val plan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val configurationsBefore = harness.fakeBleRepo.workoutParameters.size
            var observedDurableClear = false
            harness.dwsm.restTransitionNavigationLookupObserverForTest = {
                observedDurableClear = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan == null
            }

            assertIs<RestTransitionReduction.DispatchNormal>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity())),
            )
            advanceUntilIdle()

            assertTrue(observedDurableClear)
            assertNull(harness.restTransitionPlan.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            harness.dwsm.restTransitionNavigationLookupObserverForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted restored Skip waits for exact RESET and durability then starts once`() = runTest {
        val harness = enabledRecoveryHarness()
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val durabilityEntered = CompletableDeferred<Unit>()
        val releaseDurability = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.stopWorkoutBlock = {
                stopEntered.complete(Unit)
                releaseStop.await()
                Result.success(Unit)
            }
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-accepted-barriers")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            withTimeout(1_000) { stopEntered.await() }
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(
                        accepted.actionIdentity().copy(transitionId = "wrong-transition"),
                    ),
                ),
            )
            val first = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(accepted.actionIdentity()),
            )
            val duplicate = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(accepted.actionIdentity()),
            )
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(first)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(duplicate)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            var blockedDurabilityRead = false
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {
                if (!blockedDurabilityRead) {
                    blockedDurabilityRead = true
                    durabilityEntered.complete(Unit)
                    releaseDurability.await()
                }
            }
            releaseStop.complete(Unit)
            runCurrent()
            withTimeout(1_000) { durabilityEntered.await() }
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            releaseDurability.complete(Unit)
            runCurrent()

            assertEquals(configurationsBefore + 1, harness.fakeBleRepo.commandsReceived.size)
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
            assertNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan,
            )
            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            runCurrent()
            assertEquals(configurationsBefore + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
        } finally {
            releaseStop.complete(Unit)
            releaseDurability.complete(Unit)
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {}
            harness.cleanup()
        }
    }

    @Test
    fun `definitive profile switch deletes the exact restored runtime without stale navigation`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-profile-switch")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
            harness.activeSessionEngine.beforeAcceptedRetryGateCaptureForTest = {
                harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile")
            }

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryGateCaptureForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch exits old presentation when routine completion cleanup is already pending`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "profile-after-complete-cleanup")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            harness.dwsm.showRoutineComplete()
            advanceUntilIdle()

            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertNotNull(harness.coordinator.loadedRoutine.value)
            assertEquals(
                RuntimeCleanupReason.ROUTINE_COMPLETED,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            val stopCalls = harness.fakeBleRepo.stopWorkoutCallCount

            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile")
            advanceUntilIdle()

            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(stopCalls, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(
                RuntimeCleanupReason.ROUTINE_COMPLETED,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch exits old presentation before a blocked routine completion delete returns`() = runTest {
        val harness = enabledRecoveryHarness()
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "profile-during-blocked-complete-cleanup")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = {
                deleteEntered.complete(Unit)
                releaseDelete.await()
            }

            harness.dwsm.showRoutineComplete()
            runCurrent()
            withTimeout(1_000) { deleteEntered.await() }

            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertNotNull(harness.coordinator.loadedRoutine.value)
            assertEquals(
                RuntimeCleanupReason.ROUTINE_COMPLETED,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            val stopCalls = harness.fakeBleRepo.stopWorkoutCallCount

            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile")
            runCurrent()

            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(stopCalls, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(
                RuntimeCleanupReason.ROUTINE_COMPLETED,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )

            releaseDelete.complete(Unit)
            advanceUntilIdle()
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            releaseDelete.complete(Unit)
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
            harness.cleanup()
        }
    }

    @Test
    fun `profile change immediately fences an old live Skip and timer before cleanup storage`() = runTest {
        val harness = enabledRecoveryHarness()
        val cleanupLoadEntered = CompletableDeferred<Unit>()
        val releaseCleanupLoad = CompletableDeferred<Unit>()
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
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<RestTransitionReduction.Changed>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                ),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val lookupKey = ActiveWorkoutRuntimeLookupKey(document.profileId, document.routineSessionId)
            var cleanupLoadBlocked = false
            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { _, key, _ ->
                if (!cleanupLoadBlocked && key == lookupKey) {
                    cleanupLoadBlocked = true
                    cleanupLoadEntered.complete(Unit)
                    releaseCleanupLoad.await()
                }
            }
            var switched = false
            var oldSkipReduction: RestTransitionReduction? = null
            var runtimeWritesBeforeSwitch = -1
            var navigationBeforeSwitch = -1
            var configPacketsBeforeSwitch = -1
            var startCallsBeforeSwitch = -1
            var teardownCallsBeforeSwitch = -1
            var workoutSavesBeforeSwitch = -1
            var completedSetSavesBeforeSwitch = -1
            var gamificationBeforeSwitch = -1
            var badgeChecksBeforeSwitch = -1
            harness.activeSessionEngine.beforePersistedRestTimerActionForTest = {
                if (!switched) {
                    switched = true
                    runtimeWritesBeforeSwitch = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                    navigationBeforeSwitch = harness.dwsm.restTransitionNavigationLookupsForTest
                    configPacketsBeforeSwitch = harness.fakeBleRepo.commandsReceived.size
                    startCallsBeforeSwitch = harness.fakeBleRepo.workoutParameters.size
                    teardownCallsBeforeSwitch = harness.fakeBleRepo.stopWorkoutCallCount
                    workoutSavesBeforeSwitch = harness.fakeWorkoutRepo.saveSessionAttempts.size
                    completedSetSavesBeforeSwitch = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
                    gamificationBeforeSwitch = harness.fakeGamificationRepo.updateStatsCallCount
                    badgeChecksBeforeSwitch = harness.fakeGamificationRepo.checkAndAwardBadgesCallCount
                    harness.fakeUserProfileRepo.setActiveProfileForTest(id = "profile-live-fence")
                    oldSkipReduction = harness.dwsm.applyRestTransitionAwait(
                        RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                    )
                }
            }

            advanceTimeBy((resting.restSecondsRemaining + 1L) * 1_000L)
            runCurrent()
            withTimeout(1_000) { cleanupLoadEntered.await() }

            assertIs<RestTransitionReduction.NoOp>(oldSkipReduction)
            assertEquals(runtimeWritesBeforeSwitch, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(navigationBeforeSwitch, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(configPacketsBeforeSwitch, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startCallsBeforeSwitch, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(teardownCallsBeforeSwitch, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(workoutSavesBeforeSwitch, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetSavesBeforeSwitch, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertEquals(gamificationBeforeSwitch, harness.fakeGamificationRepo.updateStatsCallCount)
            assertEquals(badgeChecksBeforeSwitch, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount)
            assertEquals(RuntimeCleanupReason.PROFILE_CHANGED, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    document.profileId,
                    document.routineSessionId,
                ),
            )

            releaseCleanupLoad.complete(Unit)
            advanceUntilIdle()
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    document.profileId,
                    document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            releaseCleanupLoad.complete(Unit)
            harness.activeSessionEngine.beforePersistedRestTimerActionForTest = null
            harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = null
            harness.cleanup()
        }
    }

    @Test
    fun `End retries failed routine completion cleanup without synthetic workout work`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(harness, routine, "complete-cleanup-then-end")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            harness.dwsm.showRoutineComplete()
            advanceUntilIdle()

            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertNotNull(harness.coordinator.loadedRoutine.value)
            assertEquals(
                RuntimeCleanupReason.ROUTINE_COMPLETED,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            val workoutSaves = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val completedSetSaves = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
            val gamificationUpdates = harness.fakeGamificationRepo.updateStatsCallCount
            val badgeChecks = harness.fakeGamificationRepo.checkAndAwardBadgesCallCount
            val configPackets = harness.fakeBleRepo.commandsReceived.size
            val startCalls = harness.fakeBleRepo.workoutParameters.size
            val teardownCalls = harness.fakeBleRepo.stopWorkoutCallCount
            val stopPackets = harness.fakeBleRepo.stopPacketCallCount
            val navigation = harness.dwsm.restTransitionNavigationLookupsForTest

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(workoutSaves, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetSaves, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertEquals(gamificationUpdates, harness.fakeGamificationRepo.updateStatsCallCount)
            assertEquals(badgeChecks, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount)
            assertEquals(configPackets, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startCalls, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(teardownCalls, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(stopPackets, harness.fakeBleRepo.stopPacketCallCount)
            assertEquals(navigation, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `End exits completed presentation after cleanup succeeded without synthetic workout work`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(harness, routine, "complete-cleanup-succeeded-then-end")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()

            harness.dwsm.showRoutineComplete()
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertEquals(routine.id, harness.coordinator.loadedRoutine.value?.id)
            val workoutSaves = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val completedSetSaves = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
            val gamificationUpdates = harness.fakeGamificationRepo.updateStatsCallCount
            val badgeChecks = harness.fakeGamificationRepo.checkAndAwardBadgesCallCount
            val configPackets = harness.fakeBleRepo.commandsReceived.size
            val startCalls = harness.fakeBleRepo.workoutParameters.size
            val teardownCalls = harness.fakeBleRepo.stopWorkoutCallCount
            val stopPackets = harness.fakeBleRepo.stopPacketCallCount
            val navigation = harness.dwsm.restTransitionNavigationLookupsForTest

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(workoutSaves, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetSaves, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertEquals(gamificationUpdates, harness.fakeGamificationRepo.updateStatsCallCount)
            assertEquals(badgeChecks, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount)
            assertEquals(configPackets, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startCalls, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(teardownCalls, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(stopPackets, harness.fakeBleRepo.stopPacketCallCount)
            assertEquals(navigation, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch exits completed presentation after runtime cleanup already succeeded`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(harness, routine, "complete-cleanup-before-profile-switch")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()

            harness.dwsm.showRoutineComplete()
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
            assertEquals(routine.id, harness.coordinator.loadedRoutine.value?.id)
            assertEquals(routine.id, harness.coordinator.currentRoutineId)
            val workoutSaves = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val completedSetSaves = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
            val gamificationUpdates = harness.fakeGamificationRepo.updateStatsCallCount
            val badgeChecks = harness.fakeGamificationRepo.checkAndAwardBadgesCallCount
            val configPackets = harness.fakeBleRepo.commandsReceived.size
            val startCalls = harness.fakeBleRepo.workoutParameters.size
            val teardownCalls = harness.fakeBleRepo.stopWorkoutCallCount
            val navigation = harness.dwsm.restTransitionNavigationLookupsForTest

            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "profile-after-complete")
            advanceUntilIdle()

            assertNull(harness.coordinator.loadedRoutine.value)
            assertNull(harness.coordinator.currentRoutineId)
            assertNull(harness.coordinator.currentRoutineSessionId)
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(workoutSaves, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetSaves, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertEquals(gamificationUpdates, harness.fakeGamificationRepo.updateStatsCallCount)
            assertEquals(badgeChecks, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount)
            assertEquals(configPackets, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startCalls, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(teardownCalls, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(navigation, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored publication resets every unrelated routine scoped cache`() = runTest {
        val harness = enabledRecoveryHarness()
        val poisonTimerJob = Job()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-cache-reset")
            val poisonVariant = BodyweightVariantOption("Poison", 0.25f)
            harness.coordinator._skippedExercises.value = setOf(7)
            harness.coordinator._completedExercises.value = setOf(8)
            harness.coordinator._completedRoutineSetKeys.value = setOf(9 to 10)
            harness.coordinator._weightAdjustmentRecommendation.value = WeightAdjustmentRecommendation(
                direction = WeightAdjustmentDirection.INCREASE,
                currentWeightKgPerCable = 10f,
                recommendedWeightKgPerCable = 12f,
                confidence = RecommendationConfidence.HIGH,
                reasonCode = "poison",
                explanation = "unrelated routine state",
                targetExerciseId = "poison-exercise",
                targetSetIndex = 4,
            )
            harness.coordinator._sessionBodyweightState.value = SessionBodyweightState(
                routineHasBodyweight = true,
                promptHandled = true,
                sessionBodyWeightKg = 99f,
                lastAction = SessionBodyweightAction.SKIPPED,
            )
            harness.coordinator.bodyweightSetsCompletedInRoutine = 12
            harness.coordinator._selectedBodyweightVariants.value = mapOf("poison" to poisonVariant)
            harness.coordinator.bodyweightCompletionVariantOverride = poisonVariant
            harness.coordinator.previousExerciseWasBodyweight = true
            harness.coordinator.bodyweightTimerJob = poisonTimerJob
            harness.coordinator.routineStartTime = 123_456L
            harness.coordinator.routineAccumulatedCalories = 77f

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()

            assertTrue(harness.coordinator._skippedExercises.value.isEmpty())
            assertTrue(harness.coordinator._completedExercises.value.isEmpty())
            assertTrue(harness.coordinator._completedRoutineSetKeys.value.isEmpty())
            assertNull(harness.coordinator._weightAdjustmentRecommendation.value)
            assertEquals(SessionBodyweightState(), harness.coordinator._sessionBodyweightState.value)
            assertEquals(0, harness.coordinator.bodyweightSetsCompletedInRoutine)
            assertTrue(harness.coordinator._selectedBodyweightVariants.value.isEmpty())
            assertNull(harness.coordinator.bodyweightCompletionVariantOverride)
            assertFalse(harness.coordinator.previousExerciseWasBodyweight)
            assertNull(harness.coordinator.bodyweightTimerJob)
            assertTrue(poisonTimerJob.isCancelled)
            assertEquals(0L, harness.coordinator.routineStartTime)
            assertEquals(0f, harness.coordinator.routineAccumulatedCalories)
            assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
        } finally {
            poisonTimerJob.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun `durability cancellation outranks current routine command drift`() = runTest {
        val harness = enabledRecoveryHarness()
        val cancellation = CancellationException("durability precedence")
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "durability-before-current-inputs")
            val driftedRoutine = routine.copy(
                exercises = routine.exercises.mapIndexed { index, exercise ->
                    if (index == 0) {
                        exercise.copy(weightPerCableKg = exercise.weightPerCableKg + 10f)
                    } else {
                        exercise
                    }
                },
            )
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = { throw cancellation }

            val thrown = assertFailsWith<CancellationException> {
                harness.dwsm.resumeRoutine(installed.handle.copy(selectedRoutine = driftedRoutine))
            }

            assertSame(cancellation, thrown)
            assertEquals(1, harness.fakeCompletedSetRepo.attemptDurabilityReadCount)
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {}
            harness.cleanup()
        }
    }

    @Test
    fun `transient profile switching retains a live plan timer and rejects its action without mutation`() = runTest {
        val harness = enabledRecoveryHarness()
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
            advanceTimeBy(11_000L)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<RestTransitionReduction.Changed>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                ),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val timerJob = assertNotNull(harness.coordinator.restTimerJob)
            val remainingBefore = harness.coordinator._restSecondsRemaining.value
            val writesBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.fakeUserProfileRepo.emitSwitchingForTest("other-profile")
            runCurrent()

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            assertEquals(document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertTrue(timerJob.isActive)
            assertTrue(harness.coordinator.restTimerJob === timerJob)
            assertEquals(writesBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)

            advanceTimeBy(1_100L)
            runCurrent()

            assertTrue(timerJob.isActive)
            assertTrue(harness.coordinator.restTimerJob === timerJob)
            assertEquals(document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertEquals(writesBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)

            var liveBoundaryHookCalls = 0
            harness.activeSessionEngine.afterPersistedRestTimerProfileAuthorityFailureForTest = {
                liveBoundaryHookCalls++
                harness.activeSessionEngine.afterPersistedRestTimerProfileAuthorityFailureForTest = null
                harness.fakeUserProfileRepo.emitReadyForTest(document.profileId)
            }
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, liveBoundaryHookCalls)
            assertTrue(timerJob.isActive)
            advanceTimeBy(200L)
            runCurrent()
            assertTrue(harness.coordinator._restSecondsRemaining.value < remainingBefore)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `transient and same-profile refresh retain the exact restored runtime`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-profile-retain",
                restDeadlineEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS + 30_000L,
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val timerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            val remainingBefore = harness.coordinator._restSecondsRemaining.value
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(installed.document.restTransitionPlan)
            val writesBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.fakeUserProfileRepo.emitSwitchingForTest("other-profile")
            runCurrent()

            var actionBoundaryHookCalls = 0
            harness.activeSessionEngine.afterRestoredActionProfileAuthorityFailureForTest = {
                actionBoundaryHookCalls++
                harness.activeSessionEngine.afterRestoredActionProfileAuthorityFailureForTest = null
                harness.fakeUserProfileRepo.emitReadyForTest(installed.document.profileId)
            }

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            assertEquals(1, actionBoundaryHookCalls)
            assertEquals(owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)

            harness.fakeUserProfileRepo.emitSwitchingForTest("other-profile")
            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
            assertTrue(timerJob.isActive)
            assertEquals(writesBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)

            var timerBoundaryHookCalls = 0
            harness.activeSessionEngine.afterRestoredRestTimerProfileAuthorityFailureForTest = {
                timerBoundaryHookCalls++
                harness.activeSessionEngine.afterRestoredRestTimerProfileAuthorityFailureForTest = null
                harness.fakeUserProfileRepo.emitReadyForTest(installed.document.profileId)
            }
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, timerBoundaryHookCalls)
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(harness.coordinator._restSecondsRemaining.value < remainingBefore)
            assertEquals(installed.document.restTransitionPlan, harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `same profile Ready at a rejected restored action boundary keeps the exact action usable`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidUnresolvedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-action-profile-boundary",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)

            harness.fakeUserProfileRepo.emitSwitchingForTest("other-profile")
            var boundaryHookCalls = 0
            harness.activeSessionEngine.afterRestoredActionProfileAuthorityFailureForTest = {
                boundaryHookCalls++
                harness.activeSessionEngine.afterRestoredActionProfileAuthorityFailureForTest = null
                harness.fakeUserProfileRepo.emitReadyForTest(installed.document.profileId)
            }

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                ),
            )
            assertEquals(1, boundaryHookCalls)
            assertEquals(owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(unresolved, harness.restTransitionPlan.value)

            assertIs<RestTransitionReduction.Changed>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                ),
            )

            assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored parameter mutation revokes the owner without blocking a fresh execution`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-parameter-supersession")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)

            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(reps = 17),
            )

            assertIs<RestTransitionReduction.NoOp>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            assertTrue(
                harness.activeSessionEngine.executionGuard.beginExecution(
                    ExecutionSeed(
                        sessionId = "fresh-after-restored-mutation",
                        profileId = installed.document.profileId,
                        requiresMachine = true,
                        workingRepTarget = 17,
                    ),
                ).isSuccess,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `ready restored Accepted waits for exact Skip then starts one exact retry`() = runTest {
        val harness = enabledRecoveryHarness()
        val durabilityEntered = CompletableDeferred<Unit>()
        val releaseDurability = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-ready-before-skip")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {
                durabilityEntered.complete(Unit)
                releaseDurability.await()
            }
            var durableClearObservedAtConfig = false
            harness.fakeBleRepo.afterWorkoutCommand = {
                durableClearObservedAtConfig = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan == null
            }
            val first = async {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            }
            val duplicate = async {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            }
            runCurrent()
            withTimeout(1_000) { durabilityEntered.await() }
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            releaseDurability.complete(Unit)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(first.await())
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(duplicate.await())
            runCurrent()

            assertTrue(durableClearObservedAtConfig)
            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                accepted.resolvedWeightPerCableKg,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.single(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            assertEquals(ProgramMode.OldSchool, harness.coordinator.workoutParameters.value.programMode)
            val retryLease = assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(retryLease.sessionId, installed.document.routineSessionId)
            harness.activeSessionEngine.handleSetCompletion(retryLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(1_000)
            runCurrent()
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(retryLease))
            assertEquals(accepted.nextAttemptNumber, completion.attemptNumber)
            assertEquals(1, completion.acceptedDropCount)
        } finally {
            releaseDurability.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `post-clear restored revocation cannot publish stale manual recovery`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-post-clear-revoke")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                assertTrue(harness.activeSessionEngine.executionGuard.revokeRestoredRuntime())
            }

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            runCurrent()

            assertNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan,
            )
            assertNull(harness.restTransitionPlan.value)
            assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `revoked restored Accepted permission cannot start when RESET completes`() = runTest {
        val harness = enabledRecoveryHarness()
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.stopWorkoutBlock = {
                stopEntered.complete(Unit)
                releaseStop.await()
                Result.success(Unit)
            }
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-accepted-revoked")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            withTimeout(1_000) { stopEntered.await() }
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            assertTrue(harness.activeSessionEngine.executionGuard.revokeRestoredRuntime())

            releaseStop.complete(Unit)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertEquals(
                accepted,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan,
            )
        } finally {
            releaseStop.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `restored STOP failure Retry retains Accepted Skip and starts after one exact retry`() = runTest {
        val harness = enabledRecoveryHarness()
        val retryStopEntered = CompletableDeferred<Unit>()
        val releaseRetryStop = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("initial restored reset failed"))
            }
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-accepted-retry")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )

            harness.fakeBleRepo.stopWorkoutBlock = {
                retryStopEntered.complete(Unit)
                releaseRetryStop.await()
                Result.success(Unit)
            }
            harness.dwsm.retryMachineTeardown()
            harness.dwsm.retryMachineTeardown()
            runCurrent()
            withTimeout(1_000) { retryStopEntered.await() }

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            val retrying = assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(2, retrying.attempt)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            releaseRetryStop.complete(Unit)
            runCurrent()

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
        } finally {
            releaseRetryStop.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `restored reconnect retains Accepted Skip and starts after one post-connect RESET`() = runTest {
        val harness = enabledRecoveryHarness()
        val reconnectStopEntered = CompletableDeferred<Unit>()
        val releaseReconnectStop = CompletableDeferred<Unit>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("initial restored reset failed"))
            }
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 10,
            )
            val installed = installValidAcceptedRuntime(harness, routine, "restored-accepted-reconnect")

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.stopWorkoutBlock = {
                reconnectStopEntered.complete(Unit)
                releaseReconnectStop.await()
                Result.success(Unit)
            }

            harness.dwsm.reconnectWorkoutTeardown(harness.bleConnectionManager)
            harness.dwsm.reconnectWorkoutTeardown(harness.bleConnectionManager)
            runCurrent()
            withTimeout(1_000) { reconnectStopEntered.await() }

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            releaseReconnectStop.complete(Unit)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
        } finally {
            releaseReconnectStop.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `restored no lease End deletes exact runtime without synthesizing workout work`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-no-lease",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            val sessionAttemptsBefore = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val completedSetAttemptsBefore = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
            val gamificationStatsBefore = harness.fakeGamificationRepo.updateStatsCallCount
            val badgeChecksBefore = harness.fakeGamificationRepo.checkAndAwardBadgesCallCount
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size
            val teardownCallsBefore = harness.fakeBleRepo.stopWorkoutCallCount
            val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(sessionAttemptsBefore, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetAttemptsBefore, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertEquals(gamificationStatsBefore, harness.fakeGamificationRepo.updateStatsCallCount)
            assertEquals(badgeChecksBefore, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount)
            assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(teardownCallsBefore, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `live lease End preserves claimed workout history and deletes its exact runtime`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
                repsPerSet = 8,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 8,
                totalReps = 8,
                isWarmupComplete = true,
            )
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)
            runCurrent()
            val document = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.replacements.lastOrNull()?.document,
            )
            assertNotNull(document.restTransitionPlan)
            val sessionAttemptsBefore = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val completedSetAttemptsBefore = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size
            assertTrue(sessionAttemptsBefore > 0)
            assertTrue(completedSetAttemptsBefore > 0)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    document.profileId,
                    document.routineSessionId,
                ),
            )
            assertEquals(sessionAttemptsBefore, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(completedSetAttemptsBefore, harness.fakeCompletedSetRepo.saveCompletedSetAttempts.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored stop without exit retains exact recovery runtime`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-stop-retains",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val plan = assertNotNull(harness.restTransitionPlan.value)
            val timerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size
            val teardownCallsBefore = harness.fakeBleRepo.stopWorkoutCallCount

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertEquals(plan, harness.restTransitionPlan.value)
            assertEquals(
                installed.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(teardownCallsBefore, harness.fakeBleRepo.stopWorkoutCallCount)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `intentional routine replacement exit and clear delete the exact restored runtime`() = runTest {
        RoutineAbandonmentPath.entries.forEach { path ->
            val harness = enabledRecoveryHarness()
            try {
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                val routine = WorkoutStateFixtures.createTestRoutine(
                    exerciseCount = 1,
                    setsPerExercise = 2,
                    weightKg = 25f,
                )
                routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
                val installed = installValidAcceptedRuntime(
                    harness = harness,
                    routine = routine,
                    routineSessionId = "restored-abandon-${path.name.lowercase()}",
                )
                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(installed.handle),
                )
                runCurrent()
                assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)

                val replacement = routine.copy(
                    id = "replacement-${path.name.lowercase()}",
                    name = "Replacement ${path.name}",
                )
                when (path) {
                    RoutineAbandonmentPath.LOAD -> harness.dwsm.loadRoutine(replacement)

                    RoutineAbandonmentPath.LOAD_ASYNC -> assertTrue(harness.dwsm.loadRoutineAsync(replacement))

                    RoutineAbandonmentPath.OVERVIEW -> harness.dwsm.enterRoutineOverview(replacement)

                    RoutineAbandonmentPath.MODIFIED_OVERVIEW -> harness.dwsm.enterRoutineOverview(
                        replacement,
                        AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50),
                    )

                    RoutineAbandonmentPath.EXIT -> harness.dwsm.exitRoutineFlow()

                    RoutineAbandonmentPath.CLEAR -> harness.dwsm.clearLoadedRoutine()
                }
                advanceUntilIdle()

                assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                    path.name,
                )
                assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(), path.name)
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), path.name)
                assertNull(harness.restTransitionPlan.value, path.name)
                assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(), path.name)
                assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty(), path.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), path.name)
                if (path == RoutineAbandonmentPath.EXIT || path == RoutineAbandonmentPath.CLEAR) {
                    assertNull(harness.coordinator.loadedRoutine.value, path.name)
                } else {
                    assertEquals(replacement.id, harness.coordinator.loadedRoutine.value?.id, path.name)
                }
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `stale terminal cleanup cannot delete or retain retry authority over same key replacement`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-stale-replacement",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val replacement = installed.document.copy(
                restDeadlineEpochMs = installed.document.restDeadlineEpochMs?.plus(1_000L),
            )
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = {
                harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
                harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                    replacement,
                )
            }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(
                replacement,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            val loadCallsAfterSupersession = harness.fakeActiveWorkoutRuntimeRepository.loadCalls
            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            advanceUntilIdle()

            assertEquals(loadCallsAfterSupersession, harness.fakeActiveWorkoutRuntimeRepository.loadCalls)
            assertEquals(
                replacement,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.beforeConditionalDelete = null
            harness.cleanup()
        }
    }

    @Test
    fun `terminal delete failure leaves only immutable inert cleanup authority and exact retry clears it`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-delete-retry",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertTrue(harness.coordinator.stopWorkoutInProgress.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            harness.activeSessionEngine.retryPendingRuntimeCleanup()
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertNull(harness.restTransitionPlan.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Resume retries an exact failed terminal cleanup without resurrecting its runtime`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-delete-resume-retry",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 2

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val rediscovered = assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            ).handle as RoutineResumeHandle.Persisted
            assertIs<ActiveWorkoutRuntimeResumeResult.RetryableFailure>(
                harness.dwsm.resumeRoutine(rediscovered),
            )
            runCurrent()

            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            assertIs<ActiveWorkoutRuntimeResumeResult.Missing>(
                harness.dwsm.resumeRoutine(rediscovered),
            )
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Discard retries an exact failed terminal cleanup and releases its stop guard`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-delete-discard-retry",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val rediscovered = assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            ).handle as RoutineResumeHandle.Persisted
            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertTrue(harness.coordinator.stopWorkoutInProgress.value)

            assertIs<RoutineResumeDiscardResult.Discarded>(
                harness.dwsm.discardRoutineResume(rediscovered),
            )
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal delete cancellation rethrows and retains only inert retry target`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-end-delete-cancel",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 1
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())

            val expectedCancellation = CancellationException("cleanup delete cancelled")
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextConditionalDelete = expectedCancellation
            val completion = CompletableDeferred<Throwable?>()
            val retryJob = assertNotNull(harness.activeSessionEngine.retryPendingRuntimeCleanup())
            retryJob.invokeOnCompletion { completion.complete(it) }
            advanceUntilIdle()

            val actualCancellation = assertIs<CancellationException>(completion.await())
            assertEquals(expectedCancellation.message, actualCancellation.message)
            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            harness.activeSessionEngine.retryPendingRuntimeCleanup()
            advanceUntilIdle()
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertFalse(harness.coordinator.stopWorkoutInProgress.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `duplicate terminal intents coalesce on the first immutable cleanup target`() = runTest {
        val harness = enabledRecoveryHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 2,
                weightKg = 25f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            val installed = installValidAcceptedRuntime(
                harness = harness,
                routine = routine,
                routineSessionId = "restored-cleanup-coalesce",
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
            runCurrent()
            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 2

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(1, harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining)

            harness.dwsm.loadRoutine(
                routine.copy(id = "replacement-after-cleanup-intent", name = "Replacement"),
            )
            advanceUntilIdle()

            assertEquals(RuntimeCleanupReason.END_WORKOUT, harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(
                1,
                harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining,
                "a duplicate intent must not launch a second delete for the same immutable target",
            )

            harness.fakeActiveWorkoutRuntimeRepository.failingConditionalDeleteCallsRemaining = 0
            harness.activeSessionEngine.retryPendingRuntimeCleanup()
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            harness.cleanup()
        }
    }

    private data class InstalledRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    private fun kotlinx.coroutines.test.TestScope.enabledRecoveryHarness(): DWSMTestHarness = DWSMTestHarness(
        testScope = this,
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(
            DropSetFeatureGate { true },
            DropSetCandidateResolver(),
        ),
        dropSetConfigurationProvider = {
            DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 5f)
        },
    )

    private fun readFloatLe(packet: ByteArray, offset: Int): Float {
        val bits = (packet[offset].toInt() and 0xFF) or
            ((packet[offset + 1].toInt() and 0xFF) shl 8) or
            ((packet[offset + 2].toInt() and 0xFF) shl 16) or
            ((packet[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    private suspend fun installValidUnresolvedRuntime(
        harness: DWSMTestHarness,
        routine: com.devil.phoenixproject.domain.model.Routine,
        routineSessionId: String,
    ): InstalledRuntime {
        val exercise = routine.exercises.single()
        val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
        val key = LogicalSetKey(routineSessionId, exercise.id, 0, SetType.STANDARD)
        val sourceStableSessionId = "source-$routineSessionId"
        val offered = runtimeDocument(
            profileId = profileId,
            routineId = routine.id,
            routineExerciseId = exercise.id,
            logicalSetKey = key,
            sourceExercise = exercise,
        )
        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(offered.restTransitionPlan)
        val document = offered.copy(
            sourceStableSessionId = sourceStableSessionId,
            sourceAuthority = offered.sourceAuthority.copy(sourceStableSessionId = sourceStableSessionId),
            teardownSeed = offered.teardownSeed.copy(sourceStableSessionId = sourceStableSessionId),
            restTransitionPlan = unresolved.copy(
                candidates = listOf(
                    DropSetCandidate(DropPercentage.TEN, 22.5f, 0.9f),
                    DropSetCandidate(DropPercentage.TWENTY, 20f, 0.8f),
                    DropSetCandidate(DropPercentage.THIRTY, 17.5f, 0.7f),
                ),
            ),
            restDeadlineEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS,
        )
        harness.fakeCompletedSetRepo.setSessionRoutine(sourceStableSessionId, routineSessionId)
        harness.fakeCompletedSetRepo.saveCompletedSet(
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

    private suspend fun installValidNormalRuntime(
        harness: DWSMTestHarness,
        routine: com.devil.phoenixproject.domain.model.Routine,
        routineSessionId: String,
    ): InstalledRuntime = installValidNavigationRuntime(
        harness = harness,
        routine = routine,
        routineSessionId = routineSessionId,
        planCase = RestoredNavigationPlanCase.NORMAL,
    )

    private suspend fun installValidAcceptedRuntime(
        harness: DWSMTestHarness,
        routine: com.devil.phoenixproject.domain.model.Routine,
        routineSessionId: String,
        restDeadlineEpochMs: Long? = null,
    ): InstalledRuntime {
        val unresolvedRuntime = installValidUnresolvedRuntime(harness, routine, routineSessionId)
        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
            unresolvedRuntime.document.restTransitionPlan,
        )
        val changed = assertIs<RestTransitionReduction.Changed>(
            reduceRestTransition(
                RestTransitionReducerState(
                    plan = unresolved,
                    currentSourceExecutionId = unresolvedRuntime.document.sourceExecutionId,
                    attemptStates = unresolvedRuntime.document.attemptStates,
                ),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(changed.plan)
        val document = unresolvedRuntime.document.copy(
            restTransitionPlan = accepted,
            restDeadlineEpochMs = restDeadlineEpochMs ?: unresolvedRuntime.document.restDeadlineEpochMs,
            attemptStates = assertNotNull(changed.attemptStates),
            exerciseLoadOverlays = listOf(
                ExerciseLoadOverlay(
                    routineExerciseId = unresolvedRuntime.document.routineExerciseId,
                    multiplier = accepted.resultingExerciseMultiplier,
                ),
            ),
        )
        harness.fakeActiveWorkoutRuntimeRepository.replace(
            document.profileId,
            document.routineSessionId,
            document,
        )
        val handle = assertIs<RoutineResumeHandle.Persisted>(
            assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            ).handle,
        )
        return InstalledRuntime(document, handle)
    }

    private suspend fun installValidNavigationRuntime(
        harness: DWSMTestHarness,
        routine: com.devil.phoenixproject.domain.model.Routine,
        routineSessionId: String,
        planCase: RestoredNavigationPlanCase,
    ): InstalledRuntime {
        val exercise = routine.exercises.single()
        val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
        val key = LogicalSetKey(routineSessionId, exercise.id, 0, SetType.STANDARD)
        val sourceStableSessionId = "source-$routineSessionId"
        val offered = runtimeDocument(
            profileId = profileId,
            routineId = routine.id,
            routineExerciseId = exercise.id,
            logicalSetKey = key,
            sourceExercise = exercise,
        )
        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(offered.restTransitionPlan)
        val document = offered.copy(
            sourceStableSessionId = sourceStableSessionId,
            sourceAuthority = offered.sourceAuthority.copy(
                sourceStableSessionId = sourceStableSessionId,
            ),
            teardownSeed = offered.teardownSeed.copy(
                sourceStableSessionId = sourceStableSessionId,
            ),
            restTransitionPlan = when (planCase) {
                RestoredNavigationPlanCase.NORMAL -> unresolved.normalAdvance

                RestoredNavigationPlanCase.DECLINED -> RestTransitionPlan.Declined(
                    transitionId = unresolved.transitionId,
                    sourceExecutionId = unresolved.sourceExecutionId,
                    logicalSetKey = unresolved.logicalSetKey,
                    offerId = unresolved.offerId,
                    normalAdvance = unresolved.normalAdvance,
                )
            },
        )
        harness.fakeCompletedSetRepo.setSessionRoutine(sourceStableSessionId, routineSessionId)
        harness.fakeCompletedSetRepo.saveCompletedSet(
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
        routineId: String,
        routineExerciseId: String,
        logicalSetKey: LogicalSetKey,
        sourceExercise: RoutineExercise,
    ): ActiveWorkoutRuntimeDocument {
        val normal = RestTransitionPlan.NormalAdvance(
            transitionId = "cold-transition",
            sourceExecutionId = "42",
            logicalSetKey = logicalSetKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(0, 0),
            plannedSetId = null,
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routineId,
            routineSessionId = logicalSetKey.routineSessionId,
            routineExerciseId = routineExerciseId,
            sourceExecutionId = "42",
            sourceStableSessionId = "source-stable",
            sourceAttemptNumber = 1,
            logicalSetKey = logicalSetKey,
            plannedSetId = null,
            sourceExerciseIndex = 0,
            sourceSetIndex = 0,
            attemptStates = listOf(
                PlannedSetAttemptState(
                    logicalSetKey = logicalSetKey,
                    nextAttemptNumber = 2,
                    acceptedDropCount = 0,
                ),
            ),
            restTransitionPlan = RestTransitionPlan.UnresolvedDropOffer(
                transitionId = normal.transitionId,
                sourceExecutionId = normal.sourceExecutionId,
                logicalSetKey = logicalSetKey,
                offerId = "cold-offer",
                plannedSetId = null,
                candidates = listOf(
                    com.devil.phoenixproject.domain.model.DropSetCandidate(
                        percentage = DropPercentage.TWENTY,
                        resolvedWeightPerCableKg = 20f,
                        resultingExerciseMultiplier = 0.8f,
                    ),
                ),
                normalAdvance = normal,
            ),
            restDeadlineEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS + 30_000L,
            originalRestDurationSeconds = 60,
            sourceAuthority = sourceAuthorityForExercise(
                profileId = profileId,
                routineId = routineId,
                routineSessionId = logicalSetKey.routineSessionId,
                routineExerciseId = routineExerciseId,
                exercise = sourceExercise,
            ),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = 42L,
                sourceStableSessionId = "source-stable",
                profileId = profileId,
                requiresMachine = !sourceExercise.exercise.isBodyweight,
            ),
        )
    }

    private fun sourceAuthorityForExercise(
        profileId: String,
        routineId: String,
        routineSessionId: String,
        routineExerciseId: String,
        exercise: RoutineExercise,
    ): RestoredRetrySourceAuthoritySnapshot {
        val targetReps = exercise.setReps.singleOrNull()?.takeIf { it > 0 }
            ?: exercise.setReps.firstOrNull()?.takeIf { it > 0 }
        val setType = if (targetReps == null || exercise.isAMRAP) SetType.AMRAP else SetType.STANDARD
        val logicalKey = LogicalSetKey(routineSessionId, routineExerciseId, 0, setType)
        val command = WorkoutParameters(
            programMode = exercise.programMode,
            reps = targetReps ?: exercise.reps,
            weightPerCableKg = exercise.weightPerCableKg,
            progressionRegressionKg = exercise.progressionKg,
            stopAtTop = exercise.stopAtTop,
            warmupReps = if (exercise.exercise.isBodyweight) 0 else 3,
            selectedExerciseId = exercise.exercise.id,
            isAMRAP = setType == SetType.AMRAP,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            repCountTiming = exercise.repCountTiming,
            echoLevel = exercise.getEchoLevelForSet(0),
            eccentricLoad = exercise.eccentricLoad,
        )
        return RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = "source-stable",
            sourceExecutionId = "42",
            profileId = profileId,
            routineIdentity = RoutineExecutionIdentity(
                profileId = profileId,
                routineId = routineId,
                routineSessionId = routineSessionId,
                routineExerciseId = routineExerciseId,
                logicalSetKey = logicalKey,
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = setType.name,
            programModeName = "OLD_SCHOOL",
            programmedBaseWeightPerCableKg = exercise.weightPerCableKg,
            configuredStartWeightPerCableKg = exercise.weightPerCableKg,
            progressionKg = exercise.progressionKg,
            actualReps = 6,
            targetReps = targetReps,
            isWarmup = false,
            isEcho = exercise.programMode == ProgramMode.Echo,
            isJustLift = false,
            isBodyweight = exercise.exercise.isBodyweight,
            isTimed = exercise.duration?.takeIf { it > 0 } != null,
            isAmrap = setType == SetType.AMRAP,
            isCableExercise = !exercise.exercise.isBodyweight,
            physicalCableCount = exercise.exercise.preferredCableCount,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(command),
        )
    }

    private fun sourceAuthority(
        profileId: String = "profile-a",
        routineId: String = "routine-a",
        routineSessionId: String = "routine-session-a",
        routineExerciseId: String = "routine-exercise-a",
    ): RestoredRetrySourceAuthoritySnapshot {
        val logicalKey = LogicalSetKey(routineSessionId, routineExerciseId, 0, SetType.STANDARD)
        val routineIdentity = RoutineExecutionIdentity(
            profileId = profileId,
            routineId = routineId,
            routineSessionId = routineSessionId,
            routineExerciseId = routineExerciseId,
            logicalSetKey = logicalKey,
            plannedSetId = null,
            exerciseIndex = 0,
            setIndex = 0,
        )
        val command = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 25f,
            activeRackItemIds = listOf("rack-a", "rack-b"),
            externalAddedLoadKg = 6f,
            counterweightKg = 2f,
            progressionRegressionKg = 1.5f,
            isJustLift = false,
            useAutoStart = false,
            stopAtTop = true,
            warmupReps = 3,
            selectedExerciseId = "exercise-a",
            isAMRAP = false,
            lastUsedWeightKg = 24f,
            prWeightKg = 31f,
            stallDetectionEnabled = true,
            repCountTiming = RepCountTiming.BOTTOM,
            echoLevel = EchoLevel.HARDEST,
            eccentricLoad = EccentricLoad.LOAD_130,
            justLiftRestSeconds = 75,
        )
        return RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = "source-stable",
            sourceExecutionId = "42",
            profileId = profileId,
            routineIdentity = routineIdentity,
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = SetType.STANDARD.name,
            programModeName = "OLD_SCHOOL",
            programmedBaseWeightPerCableKg = 25f,
            configuredStartWeightPerCableKg = 25f,
            progressionKg = 1.5f,
            actualReps = 6,
            targetReps = 10,
            isWarmup = false,
            isEcho = false,
            isJustLift = false,
            isBodyweight = false,
            isTimed = false,
            isAmrap = false,
            isCableExercise = true,
            physicalCableCount = 2,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(command),
        )
    }

    private fun executionSeed(sessionId: String) = ExecutionSeed(
        sessionId = sessionId,
        profileId = "profile-a",
        requiresMachine = true,
        workingRepTarget = 10,
    )
}
