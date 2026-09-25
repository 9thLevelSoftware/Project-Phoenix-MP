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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightAssessmentRepositoryTest {
    private lateinit var database: PhoenixDatabase
    private lateinit var exerciseRepository: SqlDelightExerciseRepository
    private lateinit var baselineRepository: SqlDelightProfileExerciseBaselineRepository
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
        baselineRepository = SqlDelightProfileExerciseBaselineRepository(database)
        repository = SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            baselineRepository,
        )
        database.phoenixDatabaseQueries.insertProfile("athlete-a", "Athlete A", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.insertProfile("athlete-b", "Athlete B", 1L, 2L, 0L)
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
                baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
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
                baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
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
    fun `failure before atomic baseline write removes session and result rows`() =
        runTest {
            baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
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
                baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
            )
        }

    @Test
    fun `pre-exercise-write failure does not restore over a newer value`() = runTest {
        baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
        val failingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                baselineRepository.set("athlete-a", "bench-press", 55f, 2L)
                throw IllegalStateException("pre-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            failingWorkoutRepository,
            baselineRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(
            55f,
            baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
        )
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
    }

    @Test
    fun `failure before atomic baseline write preserves a concurrent manual value`() = runTest {
        baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
        val workoutWithConcurrentManualUpdate = object : WorkoutRepository by workoutRepository {
            override suspend fun saveSession(session: WorkoutSession) {
                workoutRepository.saveSession(session)
                baselineRepository.set("athlete-a", "bench-press", 55f, 2L)
            }
        }
        val failingBaselineRepository = object : ProfileExerciseBaselineRepository by baselineRepository {
            override suspend fun writeForAssessment(
                profileId: String,
                exerciseId: String,
                oneRepMaxPerCableKg: Float,
                updatedAt: Long,
            ): AssessmentBaselineWriteReceipt {
                throw IllegalStateException("post-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            workoutWithConcurrentManualUpdate,
            failingBaselineRepository,
        )

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            55f,
            baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
        )
    }

    @Test
    fun `failure before atomic baseline write preserves a newer value`() = runTest {
        baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
        val failingRepository = repositoryWithExerciseUpdate {
            baselineRepository.set("athlete-a", "bench-press", 55f, 3L)
            throw IllegalStateException("failure after newer value")
        }

        assertFailsWith<IllegalStateException> {
            failingRepository.saveSessionForTest(profileId = "athlete-a")
        }

        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            55f,
            baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
        )
    }

    @Test
    fun `baseline handling precedes internal session discard cleanup`() = runTest {
        baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
        val deleteSessionReached = CompletableDeferred<Unit>()
        val releaseDeleteSession = CompletableDeferred<Unit>()
        var userDeleteCalled = false
        val pausingWorkoutRepository = object : WorkoutRepository by workoutRepository {
            override suspend fun deleteSession(sessionId: String) {
                userDeleteCalled = true
                workoutRepository.deleteSession(sessionId)
            }

            override suspend fun discardSessionInternal(sessionId: String) {
                deleteSessionReached.complete(Unit)
                releaseDeleteSession.await()
                workoutRepository.discardSessionInternal(sessionId)
            }
        }
        val failingBaselineRepository = object : ProfileExerciseBaselineRepository by baselineRepository {
            override suspend fun writeForAssessment(
                profileId: String,
                exerciseId: String,
                oneRepMaxPerCableKg: Float,
                updatedAt: Long,
            ): AssessmentBaselineWriteReceipt {
                throw IllegalStateException("post-write failure")
            }
        }
        val failingRepository = SqlDelightAssessmentRepository(
            database,
            pausingWorkoutRepository,
            failingBaselineRepository,
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
        baselineRepository.set("athlete-a", "bench-press", 50f, 4L)
        releaseDeleteSession.complete(Unit)
        val failure = save.await().exceptionOrNull()

        assertEquals(IllegalStateException::class, failure?.let { it::class })
        assertEquals("post-write failure", failure?.message)
        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertFalse(userDeleteCalled)
        assertEquals(
            50f,
            baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
        )
    }

    @Test
    fun `concurrent failing and successful saves are serialized and keep the successful row`() =
        runTest {
            baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val updateCount = AtomicInteger(0)
            val firstUpdateReached = CompletableDeferred<Unit>()
            val releaseFirstFailure = CompletableDeferred<Unit>()
            val secondUpdateReached = CompletableDeferred<Unit>()
            val serializingBaselineRepository = object : ProfileExerciseBaselineRepository by baselineRepository {
                override suspend fun writeForAssessment(
                    profileId: String,
                    exerciseId: String,
                    oneRepMaxPerCableKg: Float,
                    updatedAt: Long,
                ): AssessmentBaselineWriteReceipt {
                    when (updateCount.incrementAndGet()) {
                        1 -> {
                            firstUpdateReached.complete(Unit)
                            releaseFirstFailure.await()
                            throw IllegalStateException("first save fails")
                        }
                        2 -> secondUpdateReached.complete(Unit)
                    }
                    return baselineRepository.writeForAssessment(
                        profileId,
                        exerciseId,
                        oneRepMaxPerCableKg,
                        updatedAt,
                    )
                }
            }
            val serializingRepository = SqlDelightAssessmentRepository(
                database,
                workoutRepository,
                serializingBaselineRepository,
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
                baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
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
            baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
            val baselineWriteEntered = CompletableDeferred<Unit>()
            val releaseBaselineWrite = CompletableDeferred<Unit>()
            val cancellingRepository = repositoryWithExerciseUpdate {
                baselineWriteEntered.complete(Unit)
                releaseBaselineWrite.await()
            }
            val save = async {
                cancellingRepository.saveSessionForTest(
                    estimatedOneRepMaxKg = 120f,
                    profileId = "athlete-a",
                )
            }
            baselineWriteEntered.await()
            val cancellation = CancellationException("route popped")

            save.cancel(cancellation)
            releaseBaselineWrite.complete(Unit)
            val thrown = assertFailsWith<CancellationException> { save.await() }

            assertEquals(CancellationException::class, thrown::class)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
            assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
            assertEquals(
                40f,
                baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
            )
        }

    @Test
    fun `cancellation at baseline commit return still receives receipt and compensates`() = runTest {
        baselineRepository.set("athlete-a", "bench-press", 40f, 1L)
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val writeCommitted = CompletableDeferred<Unit>()
        val releaseWriteReturn = CompletableDeferred<Unit>()
        val pausingBaselineRepository = object : ProfileExerciseBaselineRepository by baselineRepository {
            override suspend fun writeForAssessment(
                profileId: String,
                exerciseId: String,
                oneRepMaxPerCableKg: Float,
                updatedAt: Long,
            ): AssessmentBaselineWriteReceipt {
                val receipt = baselineRepository.writeForAssessment(
                    profileId,
                    exerciseId,
                    oneRepMaxPerCableKg,
                    updatedAt,
                )
                writeCommitted.complete(Unit)
                releaseWriteReturn.await()
                return receipt
            }
        }
        val cancellingRepository = SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            pausingBaselineRepository,
            ioDispatcher,
        )
        val save = async {
            cancellingRepository.saveSessionForTest(
                estimatedOneRepMaxKg = 120f,
                profileId = "athlete-a",
            )
        }
        runCurrent()
        writeCommitted.await()
        val cancellation = CancellationException("canceled at baseline return")

        save.cancel(cancellation)
        releaseWriteReturn.complete(Unit)
        runCurrent()
        val thrown = assertFailsWith<CancellationException> { save.await() }

        assertEquals(cancellation.message, thrown.message)
        assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
        assertNull(repository.getLatestAssessment("bench-press", "athlete-a"))
        assertEquals(
            40f,
            baselineRepository.get("athlete-a", "bench-press")?.oneRepMaxPerCableKg,
        )
    }

    private fun repositoryWithExerciseUpdate(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        afterDelegateUpdate: suspend () -> Unit,
    ): SqlDelightAssessmentRepository {
        val failingBaselineRepository = object : ProfileExerciseBaselineRepository by baselineRepository {
            override suspend fun writeForAssessment(
                profileId: String,
                exerciseId: String,
                oneRepMaxPerCableKg: Float,
                updatedAt: Long,
            ): AssessmentBaselineWriteReceipt {
                afterDelegateUpdate()
                return baselineRepository.writeForAssessment(
                    profileId,
                    exerciseId,
                    oneRepMaxPerCableKg,
                    updatedAt,
                )
            }
        }
        return SqlDelightAssessmentRepository(
            database,
            workoutRepository,
            failingBaselineRepository,
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
        database.phoenixDatabaseQueries.insertExercise(
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
