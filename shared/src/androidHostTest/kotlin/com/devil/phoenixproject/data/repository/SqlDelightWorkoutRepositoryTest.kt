package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightWorkoutRepositoryTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var repository: SqlDelightWorkoutRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = FakeExerciseRepository()
        repository = SqlDelightWorkoutRepository(database, exerciseRepository)
    }

    // ========== Session CRUD Tests ==========

    @Test
    fun `saveSession persists session to database`() = runTest {
        val session = createTestSession(id = "test-session-001")

        repository.saveSession(session)

        val retrieved = repository.getSession("test-session-001")
        assertEquals("test-session-001", retrieved?.id)
        assertEquals(session.mode, retrieved?.mode)
        assertEquals(session.weightPerCableKg, retrieved?.weightPerCableKg)
    }

    @Test
    fun `getSession returns null for non-existent session`() = runTest {
        val result = repository.getSession("non-existent")
        assertNull(result)
    }

    @Test
    fun `deleteSession removes session from database`() = runTest {
        val session = createTestSession(id = "to-delete")
        repository.saveSession(session)

        repository.deleteSession("to-delete")

        val result = repository.getSession("to-delete")
        assertNull(result)
    }

    @Test
    fun `getAllSessions returns all saved sessions`() = runTest {
        repository.saveSession(createTestSession(id = "session-1", timestamp = 1000))
        repository.saveSession(createTestSession(id = "session-2", timestamp = 2000))
        repository.saveSession(createTestSession(id = "session-3", timestamp = 3000))

        repository.getAllSessions().test {
            val sessions = awaitItem()
            assertEquals(3, sessions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRecentSessions respects limit`() = runTest {
        repeat(5) { i ->
            repository.saveSession(createTestSession(id = "session-$i", timestamp = i.toLong() * 1000))
        }

        repository.getRecentSessions(3).test {
            val sessions = awaitItem()
            assertEquals(3, sessions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteAllSessions removes all sessions`() = runTest {
        repository.saveSession(createTestSession(id = "session-1"))
        repository.saveSession(createTestSession(id = "session-2"))

        repository.deleteAllSessions()

        repository.getAllSessions().test {
            val sessions = awaitItem()
            assertTrue(sessions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Session Properties Tests ==========

    @Test
    fun `saveSession persists all fields correctly`() = runTest {
        val session = WorkoutSession(
            id = "full-session",
            timestamp = 1234567890L,
            mode = "Echo",
            reps = 12,
            weightPerCableKg = 35.5f,
            progressionKg = 2.5f,
            duration = 60000L,
            totalReps = 15,
            warmupReps = 3,
            workingReps = 12,
            isJustLift = true,
            stopAtTop = true,
            eccentricLoad = 120,
            echoLevel = 2,
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            routineSessionId = "routine-123",
            routineName = "Push Day",
            safetyFlags = 1,
            deloadWarningCount = 2,
            romViolationCount = 1,
            spotterActivations = 0,
            // Summary metrics
            peakForceConcentricA = 100f,
            peakForceConcentricB = 95f,
            peakForceEccentricA = 110f,
            peakForceEccentricB = 105f,
            avgForceConcentricA = 80f,
            avgForceConcentricB = 75f,
            avgForceEccentricA = 85f,
            avgForceEccentricB = 80f,
            heaviestLiftKg = 40f,
            totalVolumeKg = 1200f,
            estimatedCalories = 25f,
            warmupAvgWeightKg = 20f,
            workingAvgWeightKg = 35f,
            burnoutAvgWeightKg = 30f,
            peakWeightKg = 40f,
            rpe = 8
        )

        repository.saveSession(session)

        val retrieved = repository.getSession("full-session")!!
        assertEquals(session.id, retrieved.id)
        assertEquals(session.timestamp, retrieved.timestamp)
        assertEquals(session.mode, retrieved.mode)
        assertEquals(session.reps, retrieved.reps)
        assertEquals(session.weightPerCableKg, retrieved.weightPerCableKg)
        assertEquals(session.progressionKg, retrieved.progressionKg)
        assertEquals(session.duration, retrieved.duration)
        assertEquals(session.totalReps, retrieved.totalReps)
        assertEquals(session.warmupReps, retrieved.warmupReps)
        assertEquals(session.workingReps, retrieved.workingReps)
        assertEquals(session.isJustLift, retrieved.isJustLift)
        assertEquals(session.stopAtTop, retrieved.stopAtTop)
        assertEquals(session.eccentricLoad, retrieved.eccentricLoad)
        assertEquals(session.echoLevel, retrieved.echoLevel)
        assertEquals(session.exerciseId, retrieved.exerciseId)
        assertEquals(session.exerciseName, retrieved.exerciseName)
        assertEquals(session.routineSessionId, retrieved.routineSessionId)
        assertEquals(session.routineName, retrieved.routineName)
        // Safety fields
        assertEquals(session.safetyFlags, retrieved.safetyFlags)
        assertEquals(session.deloadWarningCount, retrieved.deloadWarningCount)
        assertEquals(session.romViolationCount, retrieved.romViolationCount)
        assertEquals(session.spotterActivations, retrieved.spotterActivations)
        // Summary metrics
        assertEquals(session.peakForceConcentricA, retrieved.peakForceConcentricA)
        assertEquals(session.peakForceConcentricB, retrieved.peakForceConcentricB)
        assertEquals(session.rpe, retrieved.rpe)
    }

    // ========== Helper Methods ==========

    private fun createTestSession(
        id: String = "test-session",
        timestamp: Long = System.currentTimeMillis()
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "OldSchool",
        reps = 10,
        weightPerCableKg = 25f,
        totalReps = 10,
        workingReps = 10,
        exerciseId = "test-exercise",
        exerciseName = "Test Exercise"
    )
}
