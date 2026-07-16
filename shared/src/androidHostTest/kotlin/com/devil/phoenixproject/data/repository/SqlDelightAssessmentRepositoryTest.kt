package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
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
    private lateinit var database: VitruvianDatabase
    private lateinit var exerciseRepository: SqlDelightExerciseRepository
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var repository: SqlDelightAssessmentRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = SqlDelightExerciseRepository(
            database,
            com.devil.phoenixproject.data.local.ExerciseImporter(database),
        )
        workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        repository = SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            exerciseRepository,
        )
        insertExercise(id = "bench-press", name = "Bench Press")
    }

    @Test
    fun `saveAssessmentSession keeps estimate total and stores session and exercise per cable`() =
        runTest {
            val sessionId = repository.saveSessionForTest(
                estimatedOneRepMaxKg = 100f,
                userOverrideKg = null,
                profileId = "athlete-a",
            )

            val session = workoutRepository.getSession(sessionId)
            val assessment = repository.getLatestAssessment("bench-press", "athlete-a")
            assertEquals("athlete-a", session?.profileId)
            assertEquals(30f, session?.weightPerCableKg)
            assertEquals(100f, assessment?.estimatedOneRepMaxKg)
            assertNull(assessment?.userOverrideKg)
            assertEquals(sessionId, assessment?.assessmentSessionId)
            assertEquals(
                50f,
                exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
            )
        }

    @Test
    fun `saveAssessmentSession keeps override total and updates exercise per cable`() =
        runTest {
            val sessionId = repository.saveSessionForTest(
                estimatedOneRepMaxKg = 100f,
                userOverrideKg = 120f,
                profileId = "athlete-a",
            )

            val assessment = repository.getLatestAssessment("bench-press", "athlete-a")
            assertEquals(100f, assessment?.estimatedOneRepMaxKg)
            assertEquals(120f, assessment?.userOverrideKg)
            assertEquals(sessionId, assessment?.assessmentSessionId)
            assertEquals(
                60f,
                exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
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
            profileId = "athlete-a",
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
            repository.getLatestAssessment("bench-press", "athlete-a")
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
            exerciseRepository.updateOneRepMax("bench-press", 40f)
            val failure = IllegalStateException("test failure")
            val failingRepository = repositoryWithExerciseUpdate(
                afterDelegateUpdate = { throw failure },
            )

            val thrown = assertFailsWith<IllegalStateException> {
                failingRepository.saveSessionForTest(profileId = "athlete-a")
            }

            assertEquals(failure::class, thrown::class)
            assertEquals(failure.message, thrown.message)
            assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
            assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
            assertEquals(
                40f,
                exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
            )
        }

    @Test
    fun `pre-exercise-write failure does not restore over a newer value`() = runTest {
        exerciseRepository.updateOneRepMax("bench-press", 40f)
        val failingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                exerciseRepository.updateOneRepMax("bench-press", 55f)
                throw IllegalStateException("pre-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            failingWorkoutRepository,
            exerciseRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(
            55f,
            exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
        )
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
    }

    @Test
    fun `compensation snapshots the value immediately before the exercise write`() = runTest {
        exerciseRepository.updateOneRepMax("bench-press", 40f)
        val workoutWithConcurrentManualUpdate = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                workoutRepository.saveSession(session)
                exerciseRepository.updateOneRepMax("bench-press", 55f)
            }
        }
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun updateOneRepMax(
                exerciseId: String,
                oneRepMaxKg: Float?,
            ) {
                exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
                throw IllegalStateException("post-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            workoutWithConcurrentManualUpdate,
            failingExerciseRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            55f,
            exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
        )
    }

    @Test
    fun `compare-and-set compensation preserves a newer post-write value`() = runTest {
        exerciseRepository.updateOneRepMax("bench-press", 40f)
        val failingRepository = repositoryWithExerciseUpdate {
            exerciseRepository.updateOneRepMax("bench-press", 55f)
            throw IllegalStateException("failure after newer value")
        }

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            55f,
            exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
        )
    }

    @Test
    fun `restore precedes suspendable cleanup so a same-value newer write survives`() = runTest {
        exerciseRepository.updateOneRepMax("bench-press", 40f)
        val deleteSessionReached = CompletableDeferred<Unit>()
        val releaseDeleteSession = CompletableDeferred<Unit>()
        val pausingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            override suspend fun deleteSession(sessionId: String) {
                deleteSessionReached.complete(Unit)
                releaseDeleteSession.await()
                workoutRepository.deleteSession(sessionId)
            }
        }
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun updateOneRepMax(
                exerciseId: String,
                oneRepMaxKg: Float?,
            ) {
                exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
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
                    profileId = "athlete-a",
                )
            }
        }

        deleteSessionReached.await()
        exerciseRepository.updateOneRepMax("bench-press", 50f)
        releaseDeleteSession.complete(Unit)
        val failure = save.await().exceptionOrNull()

        assertEquals(IllegalStateException::class, failure?.let { it::class })
        assertEquals("post-write failure", failure?.message)
        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            50f,
            exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
        )
    }

    @Test
    fun `concurrent failing and successful saves are serialized and keep the successful row`() =
        runTest {
            exerciseRepository.updateOneRepMax("bench-press", 40f)
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val updateCount = AtomicInteger(0)
            val firstUpdateReached = CompletableDeferred<Unit>()
            val releaseFirstFailure = CompletableDeferred<Unit>()
            val secondUpdateReached = CompletableDeferred<Unit>()
            val serializingExerciseRepository = object : ExerciseRepository by exerciseRepository {
                override suspend fun updateOneRepMax(
                    exerciseId: String,
                    oneRepMaxKg: Float?,
                ) {
                    exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
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
                        profileId = "athlete-a",
                    )
                }
            }
            runCurrent()
            firstUpdateReached.await()
            val second = async {
                serializingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 120f,
                    profileId = "athlete-a",
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
                workoutRepository.getAllSessions("athlete-a").first().map { it.id },
            )
            val assessment = repository.getLatestAssessment("bench-press", "athlete-a")
            assertEquals(successfulSessionId, assessment?.assessmentSessionId)
            assertEquals(120f, assessment?.estimatedOneRepMaxKg)
            assertEquals(
                60f,
                exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
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
                failingRepository.saveSessionForTest(profileId = "athlete-a")
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
                profileId = "athlete-a",
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
        val remaining = repository.getAssessmentsByExercise("bench-press", "athlete-a").first()
        assertEquals(listOf(rawId), remaining.map { it.id })
        assertEquals(135f, remaining.single().estimatedOneRepMaxKg)
        assertNull(remaining.single().assessmentSessionId)
    }

    @Test
    fun `sixteen concurrent raw saves return IDs for their own exercise rows`() = runTest {
        val calls = (0 until 16).map { index ->
            val exerciseId = "raw-exercise-$index"
            insertExercise(id = exerciseId, name = "Raw Exercise $index")
            Triple(exerciseId, "athlete-a", 100f + index)
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
            val row = repository.getLatestAssessment(exerciseId, "athlete-a")
            assertEquals(returnedId, row?.id, exerciseId)
            assertEquals(estimateKg, row?.estimatedOneRepMaxKg, exerciseId)
        }
    }

    @Test
    fun `real child cancellation runs non-cancellable compensation and escapes unchanged`() =
        runTest {
            exerciseRepository.updateOneRepMax("bench-press", 40f)
            val exerciseWriteApplied = CompletableDeferred<Unit>()
            val cancellingRepository = repositoryWithExerciseUpdate {
                exerciseWriteApplied.complete(Unit)
                awaitCancellation()
            }
            val save = async {
                cancellingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 120f,
                    profileId = "athlete-a",
                )
            }
            exerciseWriteApplied.await()
            val cancellation = CancellationException("route popped")

            save.cancel(cancellation)
            val thrown = assertFailsWith<CancellationException> { save.await() }

            assertEquals(CancellationException::class, thrown::class)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
            assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
            assertEquals(
                40f,
                exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg,
            )
        }

    private fun repositoryWithExerciseUpdate(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        afterDelegateUpdate: suspend () -> Unit,
    ): SqlDelightAssessmentRepository {
        val failingExerciseRepository = object : ExerciseRepository by exerciseRepository {
            override suspend fun updateOneRepMax(
                exerciseId: String,
                oneRepMaxKg: Float?,
            ) {
                exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
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

    private fun insertExercise(id: String, name: String) {
        database.vitruvianDatabaseQueries.insertExercise(
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
            one_rep_max_kg = null,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
