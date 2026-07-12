package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMaxSource
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    private lateinit var profiles: FakeUserProfileRepository
    private lateinit var exercises: FakeExerciseRepository
    private lateinit var workouts: FakeWorkoutRepository
    private lateinit var personalRecords: FakePersonalRecordRepository
    private lateinit var velocity: FakeVelocityOneRepMaxRepository
    private lateinit var assessments: FakeAssessmentRepository
    private lateinit var externalMeasurements: FakeExternalMeasurementRepository

    @Before
    fun setUp() {
        profiles = FakeUserProfileRepository()
        exercises = FakeExerciseRepository()
        workouts = FakeWorkoutRepository()
        personalRecords = FakePersonalRecordRepository()
        velocity = FakeVelocityOneRepMaxRepository()
        assessments = FakeAssessmentRepository()
        externalMeasurements = FakeExternalMeasurementRepository()
    }

    @Test
    fun `A to B to A restores selection and Switching clears all visible profile data`() = runTest {
        val bench = exercise("bench")
        val squat = exercise("squat")
        exercises.addExercise(bench)
        exercises.addExercise(squat)
        profiles.seedReadyProfileForTest("b", name = "B")
        profiles.seedReadyProfileForTest("a", name = "A")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        profiles.emitSwitchingForTest("b")
        runCurrent()
        val switching = viewModel.uiState.value
        assertIs<ActiveProfileContext.Switching>(switching.context)
        assertNull(switching.selectedExercise)
        assertNull(switching.missingExerciseId)
        assertNull(switching.selectionFailure)
        assertIs<ProfileLoadable.Empty>(switching.currentOneRepMax)
        assertIs<ProfileLoadable.Empty>(switching.prHighlights)
        assertIs<ProfileLoadable.Empty>(switching.recentSessions)

        profiles.emitReadyForTest("b")
        advanceUntilIdle()
        viewModel.selectExercise(squat)
        advanceUntilIdle()

        profiles.emitSwitchingForTest("a")
        runCurrent()
        profiles.emitReadyForTest("a")
        advanceUntilIdle()
        assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
    }

    @Test
    fun `no saved selection uses only the Ready profile recent exercise`() = runTest {
        exercises.addExercise(exercise("bench"))
        exercises.addExercise(exercise("squat"))
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))
        workouts.addSession(session("b-squat", "b", "squat", timestamp = 200L))
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        workouts.recentCompletedRequests.clear()

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
        assertTrue(workouts.recentCompletedRequests.isNotEmpty())
        assertTrue(
            workouts.recentCompletedRequests.all {
                it.profileId == "a" &&
                    it.exerciseId == "bench" &&
                    it.limit == MAX_RECENT_EXERCISE_SESSIONS
            },
        )
    }

    @Test
    fun `deleted saved exercise reports the unresolved profile fallback id`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.selectExercise(bench)
        advanceUntilIdle()

        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        advanceUntilIdle()
        exercises.reset()
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))

        profiles.emitSwitchingForTest("a")
        runCurrent()
        profiles.emitReadyForTest("a")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedExercise)
        assertEquals("bench", viewModel.uiState.value.missingExerciseId)
        assertNull(viewModel.uiState.value.selectionFailure)
    }

    @Test
    fun `selection fallback failure is distinct from missing and the collector recovers`() = runTest {
        exercises.addExercise(exercise("bench"))
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))
        val failure = IllegalStateException("fallback")
        workouts.mostRecentCompletedExerciseFailure = failure
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertSame(failure, viewModel.uiState.value.selectionFailure)
        assertNull(viewModel.uiState.value.missingExerciseId)

        workouts.mostRecentCompletedExerciseFailure = null
        profiles.emitSwitchingForTest("a")
        runCurrent()
        profiles.emitReadyForTest("a")
        advanceUntilIdle()

        assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
        assertNull(viewModel.uiState.value.selectionFailure)
    }

    @Test
    fun `null resolver and empty repositories use explicit empty contracts`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<ProfileLoadable.Empty>(state.currentOneRepMax)
        assertEquals(
            ProfilePrHighlights(null, null, null),
            assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(state.prHighlights).value,
        )
        assertTrue(
            assertIs<ProfileLoadable.Ready<List<WorkoutSession>>>(state.recentSessions)
                .value
                .isEmpty(),
        )
    }

    @Test
    fun `PR failure does not blank one rep max or recent sessions and reset clears controls`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))
        val failure = IllegalStateException("prs")
        personalRecords.getAllForExerciseFailure = failure

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<ProfileLoadable.Ready<*>>(state.currentOneRepMax)
        assertSame(failure, assertIs<ProfileLoadable.Failed>(state.prHighlights).cause)
        assertIs<ProfileLoadable.Ready<*>>(state.recentSessions)
        assertTrue(personalRecords.getAllForExerciseRequests.isNotEmpty())

        personalRecords.reset()
        assertNull(personalRecords.getAllForExerciseFailure)
        assertNull(personalRecords.beforeGetAllForExerciseReturn)
        assertTrue(personalRecords.getAllForExerciseRequests.isEmpty())
    }

    @Test
    fun `resolver failure does not blank PRs or recent sessions`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))
        val failure = IllegalStateException("1rm")
        velocity.latestPassingFailure = failure

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertSame(failure, assertIs<ProfileLoadable.Failed>(state.currentOneRepMax).cause)
        assertIs<ProfileLoadable.Ready<*>>(state.prHighlights)
        assertIs<ProfileLoadable.Ready<*>>(state.recentSessions)
    }

    @Test
    fun `recent session failure does not blank an earlier source one rep max or PRs`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        assessments.saveAssessment(
            exerciseId = "bench",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = null,
            profileId = "a",
        )
        val failure = IllegalStateException("recent")
        workouts.recentCompletedFailure = failure

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val oneRepMax =
            assertIs<ProfileLoadable.Ready<com.devil.phoenixproject.domain.usecase.CurrentOneRepMax>>(
                state.currentOneRepMax,
            ).value
        assertEquals(CurrentOneRepMaxSource.ASSESSMENT, oneRepMax.source)
        assertIs<ProfileLoadable.Ready<*>>(state.prHighlights)
        assertSame(failure, assertIs<ProfileLoadable.Failed>(state.recentSessions).cause)
    }

    @Test
    fun `mixed A and B records sessions and selections never cross profiles`() = runTest {
        exercises.addExercise(exercise("bench"))
        workouts.addSession(
            session("a-bench", "a", "bench", timestamp = 100L, weightPerCableKg = 40f),
        )
        workouts.addSession(
            session("b-bench", "b", "bench", timestamp = 200L, weightPerCableKg = 80f),
        )
        personalRecords.addRecord(
            record(1L, "a", "bench", PRType.MAX_WEIGHT, 45f, 50f, 225f),
        )
        personalRecords.addRecord(
            record(2L, "a", "bench", PRType.MAX_VOLUME, 40f, 48f, 300f),
        )
        personalRecords.addRecord(
            record(3L, "b", "bench", PRType.MAX_WEIGHT, 85f, 95f, 425f),
        )
        personalRecords.addRecord(
            record(4L, "b", "bench", PRType.MAX_VOLUME, 80f, 90f, 600f),
        )
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()

        val aState = viewModel.uiState.value
        assertEquals(
            ProfilePrHighlights(45f, 50f, 300f),
            assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(aState.prHighlights).value,
        )
        assertTrue(
            assertIs<ProfileLoadable.Ready<List<WorkoutSession>>>(aState.recentSessions)
                .value
                .all { it.profileId == "a" },
        )

        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        advanceUntilIdle()

        val bState = viewModel.uiState.value
        assertEquals(
            ProfilePrHighlights(85f, 95f, 600f),
            assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(bState.prHighlights).value,
        )
        assertTrue(
            assertIs<ProfileLoadable.Ready<List<WorkoutSession>>>(bState.recentSessions)
                .value
                .all { it.profileId == "b" },
        )
        assertEquals(listOf("a", "b"), personalRecords.getAllForExerciseRequests.map { it.profileId })
        assertEquals(
            setOf("a", "b"),
            workouts.recentCompletedRequests.map { it.profileId }.toSet(),
        )
    }

    @Test
    fun `same profile Ready refresh updates context without restarting insights`() = runTest {
        exercises.addExercise(exercise("bench"))
        workouts.addSession(session("a-bench", "a", "bench", timestamp = 100L))
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        val before = viewModel.uiState.value
        val workoutRequestCount = workouts.recentCompletedRequests.size
        val personalRecordRequestCount = personalRecords.getAllForExerciseRequests.size
        val ready = assertIs<ActiveProfileContext.Ready>(before.context)

        profiles.updateCore(
            "a",
            ready.preferences.core.value.copy(bodyWeightKg = 82f),
        )
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertEquals(
            82f,
            assertIs<ActiveProfileContext.Ready>(after.context).preferences.core.value.bodyWeightKg,
        )
        assertEquals(before.selectedExercise, after.selectedExercise)
        assertEquals(before.currentOneRepMax, after.currentOneRepMax)
        assertEquals(before.prHighlights, after.prHighlights)
        assertEquals(before.recentSessions, after.recentSessions)
        assertEquals(workoutRequestCount, workouts.recentCompletedRequests.size)
        assertEquals(personalRecordRequestCount, personalRecords.getAllForExerciseRequests.size)
    }

    @Test
    fun `nullable blank and switching time selections are ignored`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        val initialWorkoutRequests = workouts.recentCompletedRequests.size
        val initialPrRequests = personalRecords.getAllForExerciseRequests.size

        viewModel.selectExercise(Exercise(name = "Null", muscleGroup = "Other", id = null))
        viewModel.selectExercise(Exercise(name = "Blank", muscleGroup = "Other", id = " "))
        advanceUntilIdle()
        assertEquals(initialWorkoutRequests, workouts.recentCompletedRequests.size)
        assertEquals(initialPrRequests, personalRecords.getAllForExerciseRequests.size)

        profiles.emitSwitchingForTest("b")
        viewModel.selectExercise(bench)
        runCurrent()
        profiles.emitReadyForTest("b")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedExercise)
        assertEquals(initialWorkoutRequests, workouts.recentCompletedRequests.size)
        assertEquals(initialPrRequests, personalRecords.getAllForExerciseRequests.size)
    }

    @Test
    fun `cooperative insight cancellation is never converted to Failed`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        personalRecords.beforeGetAllForExerciseReturn = { _, profileId ->
            if (profileId == "a") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    canceled.complete(Unit)
                }
            }
        }

        viewModel.selectExercise(bench)
        started.await()
        profiles.emitSwitchingForTest("b")
        runCurrent()
        canceled.await()

        val state = viewModel.uiState.value
        assertIs<ActiveProfileContext.Switching>(state.context)
        assertIs<ProfileLoadable.Empty>(state.currentOneRepMax)
        assertIs<ProfileLoadable.Empty>(state.prHighlights)
        assertIs<ProfileLoadable.Empty>(state.recentSessions)
    }

    @Test
    fun `non cooperative slow A result cannot overwrite B`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        personalRecords.addRecord(
            record(1L, "a", "bench", PRType.MAX_WEIGHT, 40f, 45f, 200f),
        )
        personalRecords.addRecord(
            record(2L, "b", "bench", PRType.MAX_WEIGHT, 80f, 90f, 400f),
        )
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        val aStarted = CompletableDeferred<Unit>()
        val staleAReturned = CompletableDeferred<Unit>()
        personalRecords.beforeGetAllForExerciseReturn = { _, profileId ->
            if (profileId == "a") {
                aStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    // Deliberately return a stale snapshot to exercise publication guards.
                }
                staleAReturned.complete(Unit)
            }
        }

        viewModel.selectExercise(bench)
        aStarted.await()
        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        advanceUntilIdle()
        viewModel.selectExercise(bench)
        advanceUntilIdle()
        staleAReturned.await()

        assertEquals(
            ProfilePrHighlights(80f, 90f, null),
            assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(
                viewModel.uiState.value.prHighlights,
            ).value,
        )
    }

    @Test
    fun `non finite and non positive PR values are excluded per field`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        listOf(
            record(
                1L,
                "a",
                "bench",
                PRType.MAX_WEIGHT,
                Float.NaN,
                Float.NaN,
                10f,
                "nan",
            ),
            record(
                2L,
                "a",
                "bench",
                PRType.MAX_WEIGHT,
                Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                20f,
                "infinite",
            ),
            record(3L, "a", "bench", PRType.MAX_WEIGHT, 0f, 0f, 30f, "zero"),
            record(4L, "a", "bench", PRType.MAX_WEIGHT, -1f, -2f, 40f, "negative"),
            record(5L, "a", "bench", PRType.MAX_WEIGHT, 50f, 70f, 50f, "valid-weight"),
            record(6L, "a", "bench", PRType.MAX_VOLUME, 20f, 60f, 0f, "zero-volume"),
            record(7L, "a", "bench", PRType.MAX_VOLUME, 30f, 65f, -1f, "negative-volume"),
            record(
                8L,
                "a",
                "bench",
                PRType.MAX_VOLUME,
                35f,
                68f,
                Float.POSITIVE_INFINITY,
                "infinite-volume",
            ),
            record(9L, "a", "bench", PRType.MAX_VOLUME, 40f, 69f, 500f, "valid-volume"),
        ).forEach(personalRecords::addRecord)

        viewModel.selectExercise(bench)
        advanceUntilIdle()

        assertEquals(
            ProfilePrHighlights(50f, 70f, 500f),
            assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(
                viewModel.uiState.value.prHighlights,
            ).value,
        )
    }

    @Test
    fun `trimmed normalized update targets current Ready and succeeds after authoritative value`() = runTest {
        profiles.seedReadyProfileForTest("a", name = "Before", colorIndex = 2)
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.updateIdentity("  After  ", 99)

        val inFlight = assertNotNull(viewModel.uiState.value.identityMutation)
        assertEquals("a", inFlight.profileId)
        assertEquals(ProfileIdentityMutationKind.UPDATE, inFlight.kind)
        advanceUntilIdle()

        assertEquals(
            FakeUserProfileRepository.UpdateProfileRequest("a", "After", 0),
            profiles.updateProfileRequests.single(),
        )
        val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertEquals("After", ready.profile.name)
        assertEquals(0, ready.profile.colorIndex)
        assertNull(viewModel.uiState.value.identityMutation)
        assertEquals(listOf<ProfileUiEvent>(ProfileUiEvent.IdentityUpdated("a")), events)
    }

    @Test
    fun `ordinary update failure preserves authoritative state clears token and emits scoped failure`() = runTest {
        profiles.seedReadyProfileForTest("a", name = "Before", colorIndex = 2)
        profiles.updateProfileFailure = IllegalStateException("update failed")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.updateIdentity("After", 3)
        advanceUntilIdle()

        val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertEquals("Before", ready.profile.name)
        assertEquals(2, ready.profile.colorIndex)
        assertNull(viewModel.uiState.value.identityMutation)
        assertEquals(
            listOf<ProfileUiEvent>(ProfileUiEvent.IdentityUpdateFailed("a", ProfileIdentityMutationKind.UPDATE)),
            events,
        )
    }

    @Test
    fun `identity cancellation emits no terminal event and clears only its token`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateProfileFailure = CancellationException("cancel update")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.updateIdentity("After", 1)
        val token = assertNotNull(viewModel.uiState.value.identityMutation).token
        advanceUntilIdle()

        assertTrue(token > 0)
        assertNull(viewModel.uiState.value.identityMutation)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `overlapping update and delete are rejected synchronously`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        val updateGate = CompletableDeferred<Unit>()
        profiles.beforeUpdateProfileMutation = { updateGate.await() }
        val viewModel = createViewModel()
        runCurrent()

        viewModel.updateIdentity("After", 1)
        runCurrent()
        viewModel.deleteActiveProfile()

        assertTrue(viewModel.uiState.value.identityMutationInFlight)
        assertTrue(profiles.deleteActiveProfileRequests.isEmpty())
        updateGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, profiles.updateProfileRequests.size)
    }

    @Test
    fun `same profile Ready refresh preserves mutation token without restarting insights`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        runCurrent()
        viewModel.selectExercise(bench)
        advanceUntilIdle()
        val recentCalls = workouts.recentCompletedRequests.size
        val prCalls = personalRecords.getAllForExerciseRequests.size
        val updateGate = CompletableDeferred<Unit>()
        profiles.beforeUpdateProfileMutation = { updateGate.await() }

        viewModel.updateIdentity("After", 1)
        runCurrent()
        val token = assertNotNull(viewModel.uiState.value.identityMutation).token
        val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        profiles.updateCore(
            "a",
            ready.preferences.core.value.copy(bodyWeightKg = 82f),
        )
        runCurrent()

        assertEquals(token, assertNotNull(viewModel.uiState.value.identityMutation).token)
        assertEquals(82f, assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context).preferences.core.value.bodyWeightKg)
        assertEquals(recentCalls, workouts.recentCompletedRequests.size)
        assertEquals(prCalls, personalRecords.getAllForExerciseRequests.size)
        updateGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `gated update followed by A to B cannot mutate B or publish stale A event`() = runTest {
        profiles.seedReadyProfileForTest("b", name = "B")
        profiles.seedReadyProfileForTest("a", name = "A")
        val updateStarted = CompletableDeferred<Unit>()
        profiles.beforeUpdateProfileMutation = { request ->
            if (request.profileId == "a") {
                updateStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    // Deliberately non-cooperative so post-call publication guards are exercised.
                }
            }
        }
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.updateIdentity("Stale A", 3)
        runCurrent()
        updateStarted.await()
        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        advanceUntilIdle()

        val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertEquals("b", ready.profile.id)
        assertEquals("B", ready.profile.name)
        assertTrue(events.isEmpty())
        assertNull(viewModel.uiState.value.identityMutation)
    }

    @Test
    fun `Default deletion never reaches repository`() = runTest {
        profiles.seedReadyProfileForTest("default")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertTrue(profiles.deleteActiveProfileRequests.isEmpty())
        assertFalse(viewModel.uiState.value.identityMutationInFlight)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `successful active deletion emits scoped ProfileDeleted after busy clears`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        val busyWhenObserved = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                events += event
                busyWhenObserved += viewModel.uiState.value.identityMutationInFlight
            }
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertEquals(listOf("a"), profiles.deleteActiveProfileRequests)
        assertEquals(listOf<ProfileUiEvent>(ProfileUiEvent.ProfileDeleted("a")), events)
        assertEquals(listOf(false), busyWhenObserved)
        assertEquals(
            "default",
            assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun `false active deletion emits scoped delete failure`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        profiles.deleteActiveProfileResultOverride = false
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertEquals(
            listOf<ProfileUiEvent>(ProfileUiEvent.IdentityUpdateFailed("a", ProfileIdentityMutationKind.DELETE)),
            events,
        )
        assertNull(viewModel.uiState.value.identityMutation)
    }

    @Test
    fun `ordinary delete exception emits scoped failure and preserves active profile`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        profiles.deleteProfileFailure = IllegalStateException("delete failed")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertEquals(
            listOf<ProfileUiEvent>(ProfileUiEvent.IdentityUpdateFailed("a", ProfileIdentityMutationKind.DELETE)),
            events,
        )
        assertEquals(
            "a",
            assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value).profile.id,
        )
        assertNull(viewModel.uiState.value.identityMutation)
    }

    @Test
    fun `active deletion rollback emits scoped failure after Switching Ready restoration`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        profiles.failBeforeProfileDeletionCommit = true
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        val contexts = mutableListOf<ActiveProfileContext>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        val contextJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            profiles.activeProfileContext.drop(1).take(2).toList(contexts)
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertEquals(listOf("a"), profiles.deleteActiveProfileRequests)
        assertEquals(2, contexts.size)
        assertEquals("default", assertIs<ActiveProfileContext.Switching>(contexts[0]).targetProfileId)
        assertEquals("a", assertIs<ActiveProfileContext.Ready>(contexts[1]).profile.id)
        assertEquals(
            listOf<ProfileUiEvent>(
                ProfileUiEvent.IdentityUpdateFailed("a", ProfileIdentityMutationKind.DELETE),
            ),
            events,
        )
        assertEquals(
            "a",
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context).profile.id,
        )
        assertNull(viewModel.uiState.value.identityMutation)
        contextJob.cancel()
    }

    @Test
    fun `recovery exception emits only scoped recovery event`() = runTest {
        profiles.seedReadyProfileForTest("default")
        profiles.seedReadyProfileForTest("a")
        val recovery = ProfileContextRecoveryException(IllegalStateException("reconcile"))
        profiles.deleteProfileFailure = recovery
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        runCurrent()

        viewModel.deleteActiveProfile()
        advanceUntilIdle()

        assertEquals(1, events.size)
        val event = assertIs<ProfileUiEvent.ProfileRecoveryRequired>(events.single())
        assertEquals("a", event.profileId)
        assertSame(recovery, event.cause)
        assertNull(viewModel.uiState.value.identityMutation)
    }

    @Test
    fun `all six typed updates capture Ready ID and refresh authoritative sections before release`() = runTest {
        profiles.seedReadyProfileForTest("a")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val core = CoreProfilePreferences(bodyWeightKg = 80f)
        val rack = RackPreferences()
        val workout = WorkoutPreferences(stopAtTop = true)
        val led = LedPreferences(colorScheme = 3)
        val vbt = VbtPreferences(velocityLossThresholdPercent = 30)
        val safety = ProfileLocalSafetyPreferences(safeWord = "phoenix")
        val viewModel = createViewModel()
        val snapshots = mutableListOf<PreferenceSuccessSnapshot>()
        var acceptedCallReturned = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is ProfileUiEvent.PreferenceMutationSucceeded) {
                    assertTrue(
                        acceptedCallReturned,
                        "success arrived before ProfileScreen could track the returned token",
                    )
                    snapshots += PreferenceSuccessSnapshot(
                        event = event,
                        uiState = viewModel.uiState.value,
                        repositoryReady = assertIs<ActiveProfileContext.Ready>(
                            profiles.activeProfileContext.value,
                        ),
                    )
                }
            }
        }
        advanceUntilIdle()

        suspend fun accept(call: () -> Long?): Long {
            acceptedCallReturned = false
            return withContext(Dispatchers.Main) {
                val token = call()
                acceptedCallReturned = true
                assertNotNull(token)
            }
        }

        accept { viewModel.updateCore(core) }
        advanceUntilIdle()
        accept { viewModel.updateRack(rack) }
        advanceUntilIdle()
        accept { viewModel.updateWorkout(workout) }
        advanceUntilIdle()
        accept { viewModel.updateLed(led) }
        advanceUntilIdle()
        accept { viewModel.updateVbt(vbt) }
        advanceUntilIdle()
        accept { viewModel.updateLocalSafety(safety) }
        advanceUntilIdle()

        assertEquals(
            listOf(
                FakeUserProfileRepository.PreferenceUpdateRequest.Core("a", core),
                FakeUserProfileRepository.PreferenceUpdateRequest.Rack("a", rack),
                FakeUserProfileRepository.PreferenceUpdateRequest.Workout("a", workout),
                FakeUserProfileRepository.PreferenceUpdateRequest.Led("a", led),
                FakeUserProfileRepository.PreferenceUpdateRequest.Vbt("a", vbt),
                FakeUserProfileRepository.PreferenceUpdateRequest.LocalSafety("a", safety),
            ),
            profiles.preferenceUpdateRequests,
        )
        assertEquals(
            listOf(
                ProfilePreferenceSection.CORE,
                ProfilePreferenceSection.RACK,
                ProfilePreferenceSection.WORKOUT,
                ProfilePreferenceSection.LED,
                ProfilePreferenceSection.VBT,
                ProfilePreferenceSection.LOCAL_SAFETY,
            ),
            snapshots.map { it.event.sections.single() },
        )
        snapshots.forEach { snapshot ->
            val event = snapshot.event
            val uiReady = assertIs<ActiveProfileContext.Ready>(snapshot.uiState.context)
            val repositoryReady = snapshot.repositoryReady
            val section = event.sections.single()
            assertEquals("a", event.profileId)
            assertEquals(ProfilePreferenceMutationKind.UPDATE, event.kind)
            assertEquals("a", uiReady.profile.id)
            assertEquals("a", repositoryReady.profile.id)
            assertFalse(section in snapshot.uiState.busyPreferenceSections)
            when (section) {
                ProfilePreferenceSection.CORE -> {
                    assertEquals(core, uiReady.preferences.core.value)
                    assertEquals(repositoryReady.preferences.core, uiReady.preferences.core)
                    assertTrue(uiReady.preferences.core.metadata.localGeneration > 0L)
                }
                ProfilePreferenceSection.RACK -> {
                    assertEquals(rack, uiReady.preferences.rack.value)
                    assertEquals(repositoryReady.preferences.rack, uiReady.preferences.rack)
                    assertTrue(uiReady.preferences.rack.metadata.localGeneration > 0L)
                }
                ProfilePreferenceSection.WORKOUT -> {
                    assertEquals(workout, uiReady.preferences.workout.value)
                    assertEquals(repositoryReady.preferences.workout, uiReady.preferences.workout)
                    assertTrue(uiReady.preferences.workout.metadata.localGeneration > 0L)
                }
                ProfilePreferenceSection.LED -> {
                    assertEquals(led, uiReady.preferences.led.value)
                    assertEquals(repositoryReady.preferences.led, uiReady.preferences.led)
                    assertTrue(uiReady.preferences.led.metadata.localGeneration > 0L)
                }
                ProfilePreferenceSection.VBT -> {
                    assertEquals(vbt, uiReady.preferences.vbt.value)
                    assertEquals(repositoryReady.preferences.vbt, uiReady.preferences.vbt)
                    assertTrue(uiReady.preferences.vbt.metadata.localGeneration > 0L)
                }
                ProfilePreferenceSection.LOCAL_SAFETY -> {
                    assertEquals(safety, uiReady.localSafety)
                    assertEquals(repositoryReady.localSafety, uiReady.localSafety)
                }
            }
        }
        val ready = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertEquals(core, ready.preferences.core.value)
        assertEquals(rack, ready.preferences.rack.value)
        assertEquals(workout, ready.preferences.workout.value)
        assertEquals(led, ready.preferences.led.value)
        assertEquals(vbt, ready.preferences.vbt.value)
        assertEquals(safety, ready.localSafety)
    }

    @Test
    fun `same section rejects synchronously while another section proceeds`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val coreGate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Core) coreGate.await()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        val first = assertNotNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)))
        assertNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 81f)))
        val led = assertNotNull(viewModel.updateLed(LedPreferences(colorScheme = 2)))

        assertTrue(first != led)
        assertEquals(
            setOf(ProfilePreferenceSection.CORE, ProfilePreferenceSection.LED),
            viewModel.uiState.value.busyPreferenceSections,
        )
        coreGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, profiles.preferenceUpdateRequests.count {
            it is FakeUserProfileRepository.PreferenceUpdateRequest.Core
        })
        assertEquals(1, profiles.preferenceUpdateRequests.count {
            it is FakeUserProfileRepository.PreferenceUpdateRequest.Led
        })
    }

    @Test
    fun `preference and identity claims reject cross domain overlap both directions`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val preferenceGate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Core) preferenceGate.await()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)))
        viewModel.updateIdentity("Blocked by preference", 1)
        assertTrue(profiles.updateProfileRequests.isEmpty())

        preferenceGate.complete(Unit)
        advanceUntilIdle()
        val identityGate = CompletableDeferred<Unit>()
        profiles.beforeUpdateProfileMutation = { identityGate.await() }
        viewModel.updateIdentity("Identity owner", 2)
        assertNotNull(viewModel.uiState.value.identityMutation)
        assertNull(viewModel.updateLed(LedPreferences(colorScheme = 4)))
        runCurrent()
        assertTrue(profiles.preferenceUpdateRequests.none {
            it is FakeUserProfileRepository.PreferenceUpdateRequest.Led
        })

        identityGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `ordinary preference failure is token profile and section scoped`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateWorkoutFailure = IllegalStateException("workout")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        var acceptedCallReturned = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is ProfileUiEvent.PreferenceUpdateFailed) {
                    assertTrue(
                        acceptedCallReturned,
                        "failure arrived before ProfileScreen could track the returned token",
                    )
                }
                events += event
            }
        }
        advanceUntilIdle()

        acceptedCallReturned = false
        val acceptedToken = withContext(Dispatchers.Main) {
            val token = viewModel.updateWorkout(WorkoutPreferences(stopAtTop = true))
            acceptedCallReturned = true
            token
        }
        val token = assertNotNull(acceptedToken)
        advanceUntilIdle()

        val failure = assertIs<ProfileUiEvent.PreferenceUpdateFailed>(events.single())
        assertEquals("a", failure.profileId)
        assertEquals(token, failure.token)
        assertEquals(ProfilePreferenceMutationKind.UPDATE, failure.kind)
        assertEquals(setOf(ProfilePreferenceSection.WORKOUT), failure.sections)
        assertTrue(failure.committedSections.isEmpty())
        assertTrue(viewModel.uiState.value.busyPreferenceSections.isEmpty())
        assertFalse(
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .preferences.workout.value.stopAtTop,
        )
    }

    @Test
    fun `stale A completion cannot clear a later owner or publish an unowned outcome`() = runTest {
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request.profileId == "a") {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { oldRelease.await() }
            }
        }
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val oldToken = assertNotNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)))
        runCurrent()
        oldStarted.await()
        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        runCurrent()
        oldRelease.complete(Unit)
        advanceUntilIdle()

        val laterGate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request.profileId == "b") laterGate.await()
        }
        val laterToken = assertNotNull(
            viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 90f)),
        )
        assertTrue(laterToken > oldToken)
        val eventCountBeforeStaleClear = events.size
        val clearMethod = ProfileViewModel::class.java
            .getDeclaredMethod("clearPreferenceMutation", java.lang.Long.TYPE)
            .apply { isAccessible = true }
        assertFalse(clearMethod.invoke(viewModel, oldToken) as Boolean)
        assertEquals(
            laterToken,
            viewModel.uiState.value.preferenceMutations
                .getValue(ProfilePreferenceSection.CORE)
                .token,
        )
        runCurrent()
        assertEquals(eventCountBeforeStaleClear, events.size)

        laterGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(events.none {
            it is ProfileUiEvent.PreferenceMutationSucceeded &&
                it.profileId == "a" &&
                it.token == oldToken
        })
    }

    @Test
    fun `same profile Ready preserves preference owner and exercise insights`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.selectExercise(bench)
        advanceUntilIdle()
        val recentCalls = workouts.recentCompletedRequests.size
        val prCalls = personalRecords.getAllForExerciseRequests.size
        val gate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { gate.await() }

        val token = assertNotNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)))
        runCurrent()
        profiles.emitReadyForTest("a")
        runCurrent()

        assertEquals(
            token,
            viewModel.uiState.value.preferenceMutations
                .getValue(ProfilePreferenceSection.CORE)
                .token,
        )
        assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
        assertEquals(recentCalls, workouts.recentCompletedRequests.size)
        assertEquals(prCalls, personalRecords.getAllForExerciseRequests.size)
        assertIs<ProfileLoadable.Ready<*>>(viewModel.uiState.value.prHighlights)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `Switching preserves ownership while clearing visible preference data`() = runTest {
        val bench = exercise("bench")
        exercises.addExercise(bench)
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.selectExercise(bench)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { gate.await() }

        val token = assertNotNull(viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)))
        runCurrent()
        profiles.emitSwitchingForTest("b")
        runCurrent()

        val switching = viewModel.uiState.value
        assertIs<ActiveProfileContext.Switching>(switching.context)
        assertEquals(
            token,
            switching.preferenceMutations.getValue(ProfilePreferenceSection.CORE).token,
        )
        assertNull(switching.importedBodyWeightMeasuredAt)
        assertNull(switching.selectedExercise)
        assertIs<ProfileLoadable.Empty>(switching.currentOneRepMax)
        assertIs<ProfileLoadable.Empty>(switching.prHighlights)
        assertIs<ProfileLoadable.Empty>(switching.recentSessions)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `measurement attribution restarts separately for Core generation and body weight changes`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 80f))
        val generationRelease = CompletableDeferred<Unit>()
        val bodyWeightRelease = CompletableDeferred<Unit>()
        val weight80 = measurement("a-80", "a", 80.0, 100L)
        val weight81 = measurement("a-81", "a", 81.0, 200L)
        var observationCount = 0
        externalMeasurements.observeByTypeOverride = { profileId, _ ->
            check(profileId == "a")
            when (++observationCount) {
                1 -> flowOf(listOf(weight80))
                2 -> flow {
                    generationRelease.await()
                    emit(listOf(weight80))
                }
                3 -> flow {
                    bodyWeightRelease.await()
                    emit(listOf(weight81))
                }
                else -> flowOf(emptyList())
            }
        }
        profiles.preferenceUpdateRequests.clear()
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(100L, viewModel.uiState.value.importedBodyWeightMeasuredAt)
        assertEquals(1, externalMeasurements.observationRequests.size)

        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 80f))
        runCurrent()

        val generationOnlyReady = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertEquals(80f, generationOnlyReady.preferences.core.value.bodyWeightKg)
        assertNull(viewModel.uiState.value.importedBodyWeightMeasuredAt)
        assertEquals(2, externalMeasurements.observationRequests.size)

        generationRelease.complete(Unit)
        runCurrent()
        assertEquals(100L, viewModel.uiState.value.importedBodyWeightMeasuredAt)

        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 81f))
        runCurrent()

        assertNull(viewModel.uiState.value.importedBodyWeightMeasuredAt)
        assertEquals(3, externalMeasurements.observationRequests.size)

        bodyWeightRelease.complete(Unit)
        runCurrent()
        assertEquals(200L, viewModel.uiState.value.importedBodyWeightMeasuredAt)
        assertEquals(
            listOf(ProfilePreferenceSection.CORE, ProfilePreferenceSection.CORE),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
    }

    @Test
    fun `measurement attribution clears during Switching`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 80f))
        externalMeasurements.upsertMeasurements(
            listOf(measurement("a-80", "a", 80.0, 100L)),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(100L, viewModel.uiState.value.importedBodyWeightMeasuredAt)

        profiles.emitSwitchingForTest("b")
        runCurrent()

        assertNull(viewModel.uiState.value.importedBodyWeightMeasuredAt)
        assertIs<ActiveProfileContext.Switching>(viewModel.uiState.value.context)
    }

    @Test
    fun `non cooperative old measurement cannot overwrite a new generation or profile`() = runTest {
        profiles.seedReadyProfileForTest("b")
        profiles.updateCore("b", CoreProfilePreferences(bodyWeightKg = 90f))
        profiles.seedReadyProfileForTest("a")
        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 80f))
        val oldRelease = CompletableDeferred<Unit>()
        val old = measurement("a-old", "a", 80.0, 100L)
        val currentGeneration = measurement("a-current", "a", 81.0, 200L)
        val currentProfile = measurement("b-current", "b", 90.0, 300L)
        var aObservationCount = 0
        externalMeasurements.observeByTypeOverride = { profileId, _ ->
            when (profileId) {
                "a" -> if (++aObservationCount == 1) {
                    nonCooperativeMeasurementFlow(oldRelease, old)
                } else {
                    flowOf(listOf(currentGeneration))
                }
                "b" -> flowOf(listOf(currentProfile))
                else -> flowOf(emptyList())
            }
        }
        val viewModel = createViewModel()
        runCurrent()

        profiles.updateCore("a", CoreProfilePreferences(bodyWeightKg = 81f))
        runCurrent()

        assertEquals(2, externalMeasurements.observationRequests.count { it.profileId == "a" })
        assertEquals(200L, viewModel.uiState.value.importedBodyWeightMeasuredAt)

        assertEquals("a", assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context).profile.id)
        assertEquals(
            81f,
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .preferences.core.value.bodyWeightKg,
        )
        assertEquals(200L, viewModel.uiState.value.importedBodyWeightMeasuredAt)

        profiles.emitSwitchingForTest("b")
        runCurrent()
        profiles.emitReadyForTest("b")
        runCurrent()

        assertEquals("b", assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context).profile.id)
        assertEquals(300L, viewModel.uiState.value.importedBodyWeightMeasuredAt)

        oldRelease.complete(Unit)
        runCurrent()

        assertEquals("b", assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context).profile.id)
        assertEquals(300L, viewModel.uiState.value.importedBodyWeightMeasuredAt)
    }

    @Test
    fun `adult enable owns local safety and VBT and commits safety first`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val token = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        assertEquals(
            setOf(ProfilePreferenceSection.LOCAL_SAFETY, ProfilePreferenceSection.VBT),
            viewModel.uiState.value.busyPreferenceSections,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(ProfilePreferenceSection.LOCAL_SAFETY, ProfilePreferenceSection.VBT),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
        val safetyRequest = assertIs<FakeUserProfileRepository.PreferenceUpdateRequest.LocalSafety>(
            profiles.preferenceUpdateRequests[0],
        )
        assertTrue(safetyRequest.value.adultsOnlyConfirmed)
        assertTrue(safetyRequest.value.adultsOnlyPrompted)
        val vbtRequest = assertIs<FakeUserProfileRepository.PreferenceUpdateRequest.Vbt>(
            profiles.preferenceUpdateRequests[1],
        )
        assertTrue(vbtRequest.value.vulgarModeEnabled)
        val success = assertIs<ProfileUiEvent.PreferenceMutationSucceeded>(events.single())
        assertEquals(token, success.token)
        assertEquals("a", success.profileId)
        assertEquals(ProfilePreferenceMutationKind.ADULT_ENABLE, success.kind)
        assertEquals(
            setOf(ProfilePreferenceSection.LOCAL_SAFETY, ProfilePreferenceSection.VBT),
            success.sections,
        )
    }

    @Test
    fun `adult first write failure commits neither section`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateLocalSafetyFailure = IllegalStateException("safety")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val token = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()

        assertEquals(
            listOf(ProfilePreferenceSection.LOCAL_SAFETY),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
        val failure = assertIs<ProfileUiEvent.PreferenceUpdateFailed>(events.single())
        assertEquals(token, failure.token)
        assertEquals(ProfilePreferenceMutationKind.ADULT_ENABLE, failure.kind)
        assertTrue(failure.committedSections.isEmpty())
        val ready = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertFalse(ready.localSafety.adultsOnlyConfirmed)
        assertFalse(ready.preferences.vbt.value.vulgarModeEnabled)
    }

    @Test
    fun `adult second write failure retains confirmed safety and leaves vulgar false`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateVbtFailure = IllegalStateException("vbt")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val token = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()

        assertEquals(
            listOf(ProfilePreferenceSection.LOCAL_SAFETY, ProfilePreferenceSection.VBT),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
        val failure = assertIs<ProfileUiEvent.PreferenceUpdateFailed>(events.single())
        assertEquals(token, failure.token)
        assertEquals(
            setOf(ProfilePreferenceSection.LOCAL_SAFETY),
            failure.committedSections,
        )
        val ready = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertTrue(ready.localSafety.adultsOnlyConfirmed)
        assertTrue(ready.localSafety.adultsOnlyPrompted)
        assertFalse(ready.preferences.vbt.value.vulgarModeEnabled)
    }

    @Test
    fun `switch between adult writes never mutates B`() = runTest {
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Vbt) {
                profiles.emitSwitchingForTest("b")
                profiles.emitReadyForTest("b")
            }
        }
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val token = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()

        assertTrue(profiles.preferenceUpdateRequests.all { it.profileId == "a" })
        val bReady = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertEquals("b", bReady.profile.id)
        assertFalse(bReady.localSafety.adultsOnlyConfirmed)
        assertFalse(bReady.preferences.vbt.value.vulgarModeEnabled)
        assertTrue(events.none {
            it is ProfileUiEvent.PreferenceMutationSucceeded && it.token == token
        })

        profiles.emitReadyForTest("a")
        val aReady = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertTrue(aReady.localSafety.adultsOnlyConfirmed)
        assertFalse(aReady.preferences.vbt.value.vulgarModeEnabled)
    }

    @Test
    fun `adult cancellation emits no terminal outcome and clears only its owner`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateLocalSafetyFailure = CancellationException("cancel adult")
        val ledGate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Led) ledGate.await()
        }
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        val ledToken = assertNotNull(viewModel.updateLed(LedPreferences(colorScheme = 2)))
        val adultToken = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        runCurrent()

        assertEquals(
            ledToken,
            viewModel.uiState.value.preferenceMutations
                .getValue(ProfilePreferenceSection.LED)
                .token,
        )
        assertFalse(viewModel.uiState.value.preferenceMutations.values.any { it.token == adultToken })
        assertTrue(events.isEmpty())

        ledGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(events.none { event ->
            (event as? ProfileUiEvent.PreferenceMutationSucceeded)?.token == adultToken ||
                (event as? ProfileUiEvent.PreferenceUpdateFailed)?.token == adultToken
        })

        val initialYieldToken = assertNotNull(
            viewModel.updateCore(CoreProfilePreferences(bodyWeightKg = 80f)),
        )
        launch(coroutineRule.dispatcher) {
            viewModel.viewModelScope.cancel()
        }
        runCurrent()

        assertFalse(
            viewModel.uiState.value.preferenceMutations.values.any { it.token == initialYieldToken },
        )
        assertTrue(events.none { event ->
            (event as? ProfileUiEvent.PreferenceMutationSucceeded)?.token == initialYieldToken ||
                (event as? ProfileUiEvent.PreferenceUpdateFailed)?.token == initialYieldToken
        })
    }

    @Test
    fun `adult operation overlaps neither local safety nor VBT writes`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val gate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.LocalSafety) gate.await()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        assertNull(
            viewModel.updateLocalSafety(
                ProfileLocalSafetyPreferences(safeWord = "blocked"),
            ),
        )
        assertNull(viewModel.updateVbt(VbtPreferences(velocityLossThresholdPercent = 25)))
        assertNotNull(viewModel.updateLed(LedPreferences(colorScheme = 3)))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, profiles.preferenceUpdateRequests.count {
            it is FakeUserProfileRepository.PreferenceUpdateRequest.LocalSafety
        })
        assertEquals(1, profiles.preferenceUpdateRequests.count {
            it is FakeUserProfileRepository.PreferenceUpdateRequest.Vbt
        })
    }

    @Test
    fun `adult partial commit retry writes only VBT`() = runTest {
        profiles.seedReadyProfileForTest("a")
        profiles.updateVbtFailure = IllegalStateException("first vbt")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()
        assertTrue(
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .localSafety.adultsOnlyConfirmed,
        )

        profiles.preferenceUpdateRequests.clear()
        profiles.updateVbtFailure = null
        events.clear()
        val retryToken = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()

        assertEquals(
            listOf(ProfilePreferenceSection.VBT),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
        val success = assertIs<ProfileUiEvent.PreferenceMutationSucceeded>(events.single())
        assertEquals(retryToken, success.token)
        assertEquals(ProfilePreferenceMutationKind.ADULT_ENABLE, success.kind)
        assertEquals(setOf(ProfilePreferenceSection.VBT), success.sections)
        assertTrue(
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .preferences.vbt.value.vulgarModeEnabled,
        )
    }

    @Test
    fun `disco unlock succeeds only after authoritative matching commit`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val gate = CompletableDeferred<Unit>()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Led) gate.await()
        }
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        val unlockedAtEvent = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                events += event
                if (event is ProfileUiEvent.PreferenceMutationSucceeded) {
                    unlockedAtEvent += assertIs<ActiveProfileContext.Ready>(
                        viewModel.uiState.value.context,
                    ).preferences.led.value.discoModeUnlocked
                }
            }
        }
        advanceUntilIdle()

        val token = assertNotNull(viewModel.unlockDiscoMode())
        runCurrent()
        assertTrue(events.isEmpty())
        assertFalse(
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .preferences.led.value.discoModeUnlocked,
        )

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), unlockedAtEvent)
        val success = assertIs<ProfileUiEvent.PreferenceMutationSucceeded>(events.single())
        assertEquals(token, success.token)
        assertEquals("a", success.profileId)
        assertEquals(ProfilePreferenceMutationKind.DISCO_UNLOCK, success.kind)
        assertEquals(setOf(ProfilePreferenceSection.LED), success.sections)
        assertTrue(viewModel.uiState.value.busyPreferenceSections.isEmpty())
    }

    @Test
    fun `dominatrix failure and switch produce no stale success`() = runTest {
        profiles.seedReadyProfileForTest("b")
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        val events = mutableListOf<ProfileUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        advanceUntilIdle()

        assertNull(viewModel.unlockDominatrixMode())
        runCurrent()
        assertTrue(profiles.preferenceUpdateRequests.isEmpty())
        assertTrue(events.isEmpty())

        profiles.updateLocalSafety(
            "a",
            ProfileLocalSafetyPreferences(
                adultsOnlyConfirmed = true,
                adultsOnlyPrompted = true,
            ),
        )
        profiles.updateVbt(
            "a",
            VbtPreferences(
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
            ),
        )
        advanceUntilIdle()
        profiles.preferenceUpdateRequests.clear()
        profiles.updateVbtFailure = IllegalStateException("dominatrix write")

        val failureToken = assertNotNull(viewModel.unlockDominatrixMode())
        advanceUntilIdle()

        val ordinaryFailure = assertIs<ProfileUiEvent.PreferenceUpdateFailed>(events.single())
        assertEquals(failureToken, ordinaryFailure.token)
        assertEquals("a", ordinaryFailure.profileId)
        assertEquals(ProfilePreferenceMutationKind.DOMINATRIX_UNLOCK, ordinaryFailure.kind)
        assertEquals(setOf(ProfilePreferenceSection.VBT), ordinaryFailure.sections)
        assertTrue(ordinaryFailure.committedSections.isEmpty())
        assertFalse(
            assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
                .preferences.vbt.value.dominatrixModeUnlocked,
        )

        profiles.updateVbtFailure = null
        profiles.preferenceUpdateRequests.clear()
        events.clear()
        profiles.beforePreferenceUpdate = { request ->
            if (request is FakeUserProfileRepository.PreferenceUpdateRequest.Vbt) {
                profiles.emitSwitchingForTest("b")
                profiles.emitReadyForTest("b")
            }
        }

        val staleToken = assertNotNull(viewModel.unlockDominatrixMode())
        advanceUntilIdle()

        assertTrue(events.none {
            it is ProfileUiEvent.PreferenceMutationSucceeded && it.token == staleToken
        })
        val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
        assertEquals("b", ready.profile.id)
        assertFalse(ready.preferences.vbt.value.dominatrixModeUnlocked)
        assertEquals(
            listOf(ProfilePreferenceSection.VBT),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
    }

    @Test
    fun `decline writes prompted unconfirmed and explicit enable remains retryable`() = runTest {
        profiles.seedReadyProfileForTest("a")
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.declineAdultsOnly())
        advanceUntilIdle()
        val declined = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertTrue(declined.localSafety.adultsOnlyPrompted)
        assertFalse(declined.localSafety.adultsOnlyConfirmed)
        assertFalse(declined.preferences.vbt.value.vulgarModeEnabled)

        profiles.preferenceUpdateRequests.clear()
        val retryToken = assertNotNull(viewModel.confirmAdultsOnlyAndEnableVulgar())
        advanceUntilIdle()

        assertTrue(retryToken > 0)
        assertEquals(
            listOf(ProfilePreferenceSection.LOCAL_SAFETY, ProfilePreferenceSection.VBT),
            profiles.preferenceUpdateRequests.map(::requestSection),
        )
        val enabled = assertIs<ActiveProfileContext.Ready>(viewModel.uiState.value.context)
        assertTrue(enabled.localSafety.adultsOnlyPrompted)
        assertTrue(enabled.localSafety.adultsOnlyConfirmed)
        assertTrue(enabled.preferences.vbt.value.vulgarModeEnabled)
    }

    private data class PreferenceSuccessSnapshot(
        val event: ProfileUiEvent.PreferenceMutationSucceeded,
        val uiState: ProfileUiState,
        val repositoryReady: ActiveProfileContext.Ready,
    )

    private fun requestSection(
        request: FakeUserProfileRepository.PreferenceUpdateRequest,
    ): ProfilePreferenceSection = when (request) {
        is FakeUserProfileRepository.PreferenceUpdateRequest.Core -> ProfilePreferenceSection.CORE
        is FakeUserProfileRepository.PreferenceUpdateRequest.Rack -> ProfilePreferenceSection.RACK
        is FakeUserProfileRepository.PreferenceUpdateRequest.Workout -> ProfilePreferenceSection.WORKOUT
        is FakeUserProfileRepository.PreferenceUpdateRequest.Led -> ProfilePreferenceSection.LED
        is FakeUserProfileRepository.PreferenceUpdateRequest.Vbt -> ProfilePreferenceSection.VBT
        is FakeUserProfileRepository.PreferenceUpdateRequest.LocalSafety ->
            ProfilePreferenceSection.LOCAL_SAFETY
    }

    private fun measurement(
        externalId: String,
        profileId: String,
        value: Double,
        measuredAt: Long,
    ) = ExternalBodyMeasurement(
        externalId = externalId,
        provider = IntegrationProvider.APPLE_HEALTH,
        measurementType = "weight",
        value = value,
        unit = "kg",
        measuredAt = measuredAt,
        profileId = profileId,
    )

    private fun nonCooperativeMeasurementFlow(
        release: CompletableDeferred<Unit>,
        measurement: ExternalBodyMeasurement,
    ): Flow<List<ExternalBodyMeasurement>> = object : Flow<List<ExternalBodyMeasurement>> {
        override suspend fun collect(collector: FlowCollector<List<ExternalBodyMeasurement>>) {
            withContext(NonCancellable) {
                release.await()
                collector.emit(listOf(measurement))
            }
        }
    }

    private fun createViewModel() = ProfileViewModel(
        profiles = profiles,
        exercises = exercises,
        workouts = workouts,
        personalRecords = personalRecords,
        resolveCurrentOneRepMax = ResolveCurrentOneRepMaxUseCase(
            velocityRepository = velocity,
            assessmentRepository = assessments,
            workoutRepository = workouts,
        ),
        externalMeasurements = externalMeasurements,
    )

    private fun exercise(id: String): Exercise = Exercise(
        name = id.replaceFirstChar { it.uppercase() },
        muscleGroup = "Other",
        id = id,
    )

    private fun session(
        id: String,
        profileId: String,
        exerciseId: String,
        timestamp: Long,
        weightPerCableKg: Float = 40f,
        workingReps: Int = 5,
    ) = WorkoutSession(
        id = id,
        profileId = profileId,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        timestamp = timestamp,
        weightPerCableKg = weightPerCableKg,
        workingReps = workingReps,
        totalReps = workingReps,
    )

    private fun record(
        id: Long,
        profileId: String,
        exerciseId: String,
        prType: PRType,
        weightPerCableKg: Float,
        oneRepMax: Float,
        volume: Float,
        workoutMode: String = "Old School",
    ) = PersonalRecord(
        id = id,
        profileId = profileId,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        weightPerCableKg = weightPerCableKg,
        reps = 5,
        oneRepMax = oneRepMax,
        timestamp = id,
        workoutMode = workoutMode,
        prType = prType,
        volume = volume,
    )
}
