package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
