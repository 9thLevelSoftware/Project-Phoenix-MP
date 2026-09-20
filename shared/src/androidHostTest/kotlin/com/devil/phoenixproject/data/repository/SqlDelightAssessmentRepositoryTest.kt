package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.createTestDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightAssessmentRepositoryTest {
    private lateinit var database: PhoenixDatabase
    private lateinit var exerciseRepository: SqlDelightExerciseRepository
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var repository: SqlDelightAssessmentRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = SqlDelightExerciseRepository(
            database,
            com.devil.phoenixproject.data.local.ExerciseImporter(database),
            com.devil.phoenixproject.testutil.FakePreferencesManager(),
        )
        workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        repository = SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            exerciseRepository,
        )
        insertExerciseIfAbsent(id = "bench-press", name = "Bench Press")
        // ExerciseTrainingMax has an FK to UserProfile (migration 49) and the test DB runs
        // with foreign keys ON, so the profile these tests write for has to exist.
        database.phoenixDatabaseQueries.insertUserProfileIgnore(
            id = ATHLETE,
            name = "Athlete A",
            colorIndex = 0L,
            createdAt = 0L,
            isActive = 0L,
        )
    }

    @Test
    fun `saveAssessmentSession keeps estimate total and stores session and exercise per cable`() =
        runTest {
            val sessionId = repository.saveSessionForTest(
                estimatedOneRepMaxKg = 100f,
                userOverrideKg = null,
                profileId = ATHLETE,
            )

            val session = workoutRepository.getSession(sessionId)
            val assessment = repository.getLatestAssessment("bench-press", ATHLETE)
            assertEquals(ATHLETE, session?.profileId)
            assertEquals(30f, session?.weightPerCableKg)
            assertEquals(100f, assessment?.estimatedOneRepMaxKg)
            assertNull(assessment?.userOverrideKg)
            assertEquals(sessionId, assessment?.assessmentSessionId)
            assertEquals(
                50f,
                exerciseRepository.getTrainingMax("bench-press", ATHLETE),
            )
        }

    @Test
    fun `saveAssessmentSession keeps override total and updates exercise per cable`() =
        runTest {
            val sessionId = repository.saveSessionForTest(
                estimatedOneRepMaxKg = 100f,
                userOverrideKg = 120f,
                profileId = ATHLETE,
            )

            val assessment = repository.getLatestAssessment("bench-press", ATHLETE)
            assertEquals(100f, assessment?.estimatedOneRepMaxKg)
            assertEquals(120f, assessment?.userOverrideKg)
            assertEquals(sessionId, assessment?.assessmentSessionId)
            assertEquals(
                60f,
                exerciseRepository.getTrainingMax("bench-press", ATHLETE),
            )
        }

    @Test
    fun `latest assessment is isolated by explicit profile`() = runTest {
        repository.saveAssessment(
            exerciseId = "bench-press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = null,
            profileId = ATHLETE,
        )
        repository.saveAssessment(
            exerciseId = "bench-press",
            estimatedOneRepMaxKg = 140f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = null,
            profileId = "athlete-b",
        )

        assertEquals(
            100f,
            repository.getLatestAssessment("bench-press", ATHLETE)
                ?.estimatedOneRepMaxKg,
        )
        assertEquals(
            140f,
            repository.getLatestAssessment("bench-press", "athlete-b")
                ?.estimatedOneRepMaxKg,
        )
    }

    @Test
    fun `blank profile IDs are rejected before assessment writes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.saveAssessment(
                exerciseId = "bench-press",
                estimatedOneRepMaxKg = 100f,
                loadVelocityDataJson = "[]",
                sessionId = null,
                userOverrideKg = null,
                profileId = " ",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repository.saveSessionForTest(profileId = " ")
        }

        assertNull(repository.getLatestAssessment("bench-press", " "))
        assertEquals(emptyList(), workoutRepository.getAllSessions(" ").first())
    }

    @Test
    fun `ordinary post-write failure removes rows and restores prior per-cable 1RM`() =
        runTest {
            exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
            val failure = IllegalStateException("test failure")
            val failingRepository = repositoryWithExerciseUpdate(
                afterDelegateUpdate = { throw failure },
            )

            val thrown = assertFailsWith<IllegalStateException> {
                failingRepository.saveSessionForTest(profileId = ATHLETE)
            }

            assertEquals(failure::class, thrown::class)
            assertEquals(failure.message, thrown.message)
            assertEquals(emptyList(), workoutRepository.getAllSessions(ATHLETE).first())
            assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
            assertEquals(
                40f,
                exerciseRepository.getTrainingMax("bench-press", ATHLETE),
            )
        }

    @Test
    fun `pre-exercise-write failure does not restore over a newer value`() = runTest {
        exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
        val failingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                exerciseRepository.setTrainingMax("bench-press", ATHLETE, 55f, TrainingMaxSource.MANUAL)
                throw IllegalStateException("pre-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            failingWorkoutRepository,
            exerciseRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = ATHLETE)
        }

        assertEquals(
            55f,
            exerciseRepository.getTrainingMax("bench-press", ATHLETE),
        )
        assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
    }

    @Test
    fun `compensation snapshots the value immediately before the exercise write`() = runTest {
        exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
        val workoutWithConcurrentManualUpdate = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                workoutRepository.saveSession(session)
                exerciseRepository.setTrainingMax("bench-press", ATHLETE, 55f, TrainingMaxSource.MANUAL)
            }
        }
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun setTrainingMax(
                exerciseId: String,
                profileId: String,
                oneRepMaxKg: Float?,
                source: TrainingMaxSource,
            ) {
                exerciseRepository.setTrainingMax(exerciseId, profileId, oneRepMaxKg, source)
                throw IllegalStateException("post-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            workoutWithConcurrentManualUpdate,
            failingExerciseRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = ATHLETE)
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions(ATHLETE).first())
        assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
        assertEquals(
            55f,
            exerciseRepository.getTrainingMax("bench-press", ATHLETE),
        )
    }

    @Test
    fun `compare-and-set compensation preserves a newer post-write value`() = runTest {
        exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
        val failingRepository = repositoryWithExerciseUpdate {
            exerciseRepository.setTrainingMax("bench-press", ATHLETE, 55f, TrainingMaxSource.MANUAL)
            throw IllegalStateException("failure after newer value")
        }

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = ATHLETE)
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions(ATHLETE).first())
        assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
        assertEquals(
            55f,
            exerciseRepository.getTrainingMax("bench-press", ATHLETE),
        )
    }

    @Test
    fun `restore precedes suspendable cleanup so a same-value newer write survives`() = runTest {
        exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
        val deleteSessionReached = CompletableDeferred<Unit>()
        val releaseDeleteSession = CompletableDeferred<Unit>()
        val pausingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            // The rollback discards the session (no tombstone: it was never the user's to
            // delete), so that is the call this test has to pause on.
            override suspend fun discardSession(sessionId: String) {
                deleteSessionReached.complete(Unit)
                releaseDeleteSession.await()
                workoutRepository.discardSession(sessionId)
            }
        }
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun setTrainingMax(
                exerciseId: String,
                profileId: String,
                oneRepMaxKg: Float?,
                source: TrainingMaxSource,
            ) {
                exerciseRepository.setTrainingMax(exerciseId, profileId, oneRepMaxKg, source)
                throw IllegalStateException("post-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            pausingWorkoutRepository,
            failingExerciseRepository,
        )
        val save = async {
            runCatching {
                failingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 100f,
                    profileId = ATHLETE,
                )
            }
        }

        deleteSessionReached.await()
        exerciseRepository.setTrainingMax("bench-press", ATHLETE, 50f, TrainingMaxSource.MANUAL)
        releaseDeleteSession.complete(Unit)
        val failure = save.await().exceptionOrNull()

        assertEquals(IllegalStateException::class, failure?.let { it::class })
        assertEquals("post-write failure", failure?.message)
        assertEquals(emptyList(), workoutRepository.getAllSessions(ATHLETE).first())
        assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
        assertEquals(
            50f,
            exerciseRepository.getTrainingMax("bench-press", ATHLETE),
        )
    }

    @Test
    fun `concurrent failing and successful saves are serialized and keep the successful row`() =
        runTest {
            exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val updateCount = AtomicInteger(0)
            val firstUpdateReached = CompletableDeferred<Unit>()
            val releaseFirstFailure = CompletableDeferred<Unit>()
            val secondUpdateReached = CompletableDeferred<Unit>()
            val serializingExerciseRepository = object : ExerciseRepository by exerciseRepository {
                override suspend fun setTrainingMax(
                    exerciseId: String,
                    profileId: String,
                    oneRepMaxKg: Float?,
                    source: TrainingMaxSource,
                ) {
                    exerciseRepository.setTrainingMax(exerciseId, profileId, oneRepMaxKg, source)
                    when (updateCount.incrementAndGet()) {
                        1 -> {
                            firstUpdateReached.complete(Unit)
                            releaseFirstFailure.await()
                            throw IllegalStateException("first save fails")
                        }
                        2 -> secondUpdateReached.complete(Unit)
                    }
                }
            }
            val serializingRepository = SqlDelightAssessmentRepository(
                database,
                workoutRepository,
                serializingExerciseRepository,
                ioDispatcher,
            )
            val first = async {
                runCatching {
                    serializingRepository.saveSessionForTest(
                        estimatedOneRepMaxKg = 100f,
                        profileId = ATHLETE,
                    )
                }
            }
            runCurrent()
            firstUpdateReached.await()
            val second = async {
                serializingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 120f,
                    profileId = ATHLETE,
                )
            }
            runCurrent()

            assertFalse(secondUpdateReached.isCompleted)

            releaseFirstFailure.complete(Unit)
            runCurrent()
            val firstFailure = first.await().exceptionOrNull()
            assertEquals(IllegalStateException::class, firstFailure?.let { it::class })
            assertEquals("first save fails", firstFailure?.message)
            val successfulSessionId = second.await()

            assertEquals(
                listOf(successfulSessionId),
                workoutRepository.getAllSessions(ATHLETE).first().map { it.id },
            )
            val assessment = repository.getLatestAssessment("bench-press", ATHLETE)
            assertEquals(successfulSessionId, assessment?.assessmentSessionId)
            assertEquals(120f, assessment?.estimatedOneRepMaxKg)
            assertEquals(
                60f,
                exerciseRepository.getTrainingMax("bench-press", ATHLETE),
            )
        }

    @Test
    fun `raw save cannot interleave with compensating session insert identity`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val firstUpdateReached = CompletableDeferred<Unit>()
        val releaseFirstFailure = CompletableDeferred<Unit>()
        val failingRepository = repositoryWithExerciseUpdate(ioDispatcher) {
            firstUpdateReached.complete(Unit)
            releaseFirstFailure.await()
            throw IllegalStateException("session save fails")
        }
        val failingSession = async {
            runCatching {
                failingRepository.saveSessionForTest(profileId = ATHLETE)
            }
        }
        runCurrent()
        firstUpdateReached.await()
        val rawSave = async {
            failingRepository.saveAssessment(
                exerciseId = "bench-press",
                estimatedOneRepMaxKg = 135f,
                loadVelocityDataJson = "[]",
                sessionId = null,
                userOverrideKg = null,
                profileId = ATHLETE,
            )
        }
        runCurrent()

        assertFalse(rawSave.isCompleted)

        releaseFirstFailure.complete(Unit)
        runCurrent()
        val sessionFailure = failingSession.await().exceptionOrNull()
        assertEquals(IllegalStateException::class, sessionFailure?.let { it::class })
        assertEquals("session save fails", sessionFailure?.message)
        val rawId = rawSave.await()
        val remaining = repository.getAssessmentsByExercise("bench-press", ATHLETE).first()
        assertEquals(listOf(rawId), remaining.map { it.id })
        assertEquals(135f, remaining.single().estimatedOneRepMaxKg)
        assertNull(remaining.single().assessmentSessionId)
    }

    @Test
    fun `sixteen concurrent raw saves return IDs for their own exercise rows`() = runTest {
        val calls = (0 until 16).map { index ->
            val exerciseId = "raw-exercise-$index"
            insertExerciseIfAbsent(id = exerciseId, name = "Raw Exercise $index")
            Triple(exerciseId, ATHLETE, 100f + index)
        }

        val saves = calls.map { (exerciseId, profileId, estimateKg) ->
            async(Dispatchers.Default) {
                val returnedId = repository.saveAssessment(
                    exerciseId = exerciseId,
                    estimatedOneRepMaxKg = estimateKg,
                    loadVelocityDataJson = "[]",
                    sessionId = null,
                    userOverrideKg = null,
                    profileId = profileId,
                )
                Triple(exerciseId, estimateKg, returnedId)
            }
        }.awaitAll()

        assertEquals(16, saves.map { it.third }.distinct().size)
        saves.forEach { (exerciseId, estimateKg, returnedId) ->
            val row = repository.getLatestAssessment(exerciseId, ATHLETE)
            assertEquals(returnedId, row?.id, exerciseId)
            assertEquals(estimateKg, row?.estimatedOneRepMaxKg, exerciseId)
        }
    }

    @Test
    fun `real child cancellation runs non-cancellable compensation and escapes unchanged`() =
        runTest {
            exerciseRepository.setTrainingMax("bench-press", ATHLETE, 40f, TrainingMaxSource.MANUAL)
            val exerciseWriteApplied = CompletableDeferred<Unit>()
            val cancellingRepository = repositoryWithExerciseUpdate {
                exerciseWriteApplied.complete(Unit)
                awaitCancellation()
            }
            val save = async {
                cancellingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 120f,
                    profileId = ATHLETE,
                )
            }
            exerciseWriteApplied.await()
            val cancellation = CancellationException("route popped")

            save.cancel(cancellation)
            val thrown = assertFailsWith<CancellationException> { save.await() }

            assertEquals(CancellationException::class, thrown::class)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(emptyList(), workoutRepository.getAllSessions(ATHLETE).first())
            assertNull(repository.getLatestAssessment("bench-press", ATHLETE))
            assertEquals(
                40f,
                exerciseRepository.getTrainingMax("bench-press", ATHLETE),
            )
        }

    private fun repositoryWithExerciseUpdate(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        afterDelegateUpdate: suspend () -> Unit,
    ): SqlDelightAssessmentRepository {
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun setTrainingMax(
                exerciseId: String,
                profileId: String,
                oneRepMaxKg: Float?,
                source: TrainingMaxSource,
            ) {
                exerciseRepository.setTrainingMax(exerciseId, profileId, oneRepMaxKg, source)
                afterDelegateUpdate()
            }
        }
        return SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            failingExerciseRepository,
            ioDispatcher,
        )
    }

    private suspend fun SqlDelightAssessmentRepository.saveSessionForTest(
        estimatedOneRepMaxKg: Float = 100f,
        userOverrideKg: Float? = null,
        profileId: String,
    ): String = saveAssessmentSession(
        exerciseId = "bench-press",
        exerciseName = "Bench Press",
        estimatedOneRepMaxKg = estimatedOneRepMaxKg,
        loadVelocityDataJson = "[]",
        userOverrideKg = userOverrideKg,
        totalReps = 9,
        durationMs = 60_000L,
        weightPerCableKg = 30f,
        profileId = profileId,
    )

    private companion object {
        const val ATHLETE = "athlete-a"
    }

    private fun insertExerciseIfAbsent(id: String, name: String) {
        database.phoenixDatabaseQueries.insertExerciseIfAbsent(
            id = id,
            name = name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
