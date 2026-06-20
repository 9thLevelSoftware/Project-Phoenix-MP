package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

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

        repository.getAllSessions("default").test {
            val sessions = awaitItem()
            assertEquals(3, sessions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRecentSessions respects limit`() = runTest {
        repeat(5) { i ->
            repository.saveSession(
                createTestSession(
                    id = "session-$i",
                    timestamp =
                        i.toLong() * 1000,
                ),
            )
        }

        repository.getRecentSessions("default", 3).test {
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

        repository.getAllSessions("default").test {
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
            rpe = 8,
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

    @Test
    fun `getRoutineById heals missing exerciseId by resolving exercise name`() = runTest {
        val customExercise = Exercise(
            id = "custom_bayesian_curl",
            name = "Bayesian Cable Curl",
            muscleGroup = "Biceps",
            equipment = "Cable",
            isCustom = true,
        )
        exerciseRepository.addExercise(customExercise)

        val legacyRoutine = Routine(
            id = "routine-legacy",
            name = "Legacy Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-1",
                    exercise = Exercise(
                        id = null,
                        name = "Bayesian Cable Curl",
                        muscleGroup = "Biceps",
                        equipment = "Cable",
                    ),
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 12.5f,
                ),
            ),
        )
        repository.saveRoutine(legacyRoutine)

        val loaded = repository.getRoutineById("routine-legacy")
        assertNotNull(loaded)
        assertEquals("custom_bayesian_curl", loaded.exercises.first().exercise.id)

        // Verify DB self-heal so subsequent loads don't regress to null ID.
        val healedRow = database.vitruvianDatabaseQueries.selectExercisesByRoutine("routine-legacy").executeAsOne()
        assertEquals("custom_bayesian_curl", healedRow.exerciseId)
    }

    @Test
    fun `getRoutineById auto-creates custom exercise when exerciseId is null and findByName fails`() = runTest {
        // No exercise added to exerciseRepository — findByName will return null

        val routineWithNullId = Routine(
            id = "routine-null-ex",
            name = "Null Exercise Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-null",
                    exercise = Exercise(
                        id = null,
                        name = "Bayesian Cable Curl",
                        muscleGroup = "Biceps",
                        equipment = "Cable",
                    ),
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 12.5f,
                ),
            ),
        )
        repository.saveRoutine(routineWithNullId)

        val loaded = repository.getRoutineById("routine-null-ex")
        assertNotNull(loaded)
        val exercise = loaded.exercises.first().exercise

        // Exercise ID must NOT be null — it should have been auto-created
        assertNotNull(exercise.id, "Exercise ID should not be null after self-healing")
        assertEquals("Bayesian Cable Curl", exercise.name)

        // Verify DB was healed — subsequent loads should also have the ID
        val reloaded = repository.getRoutineById("routine-null-ex")
        assertNotNull(reloaded)
        assertEquals(exercise.id, reloaded!!.exercises.first().exercise.id)
    }

    // ========== Profile ID Preservation Tests ==========

    @Test
    fun `getAllRoutines preserves profileId from database`() = runTest {
        val routine = Routine(
            id = "routine-profile-b",
            name = "Profile B Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-1",
                    exercise = Exercise(
                        id = "bench",
                        name = "Bench Press",
                        muscleGroup = "Chest",
                    ),
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 50f,
                ),
            ),
            profileId = "profile-b",
        )
        exerciseRepository.addExercise(Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest"))
        repository.saveRoutine(routine)

        repository.getAllRoutines("profile-b").test {
            val routines = awaitItem()
            assertEquals(1, routines.size)
            assertEquals("profile-b", routines.first().profileId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRoutineById preserves profileId from database`() = runTest {
        val routine = Routine(
            id = "routine-profile-c",
            name = "Profile C Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-1",
                    exercise = Exercise(
                        id = "bench",
                        name = "Bench Press",
                        muscleGroup = "Chest",
                    ),
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 50f,
                ),
            ),
            profileId = "profile-c",
        )
        exerciseRepository.addExercise(Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest"))
        repository.saveRoutine(routine)

        val loaded = repository.getRoutineById("routine-profile-c")
        assertNotNull(loaded)
        assertEquals("profile-c", loaded.profileId)
    }

    // ========== Profile Cascade Reassignment Tests ==========

    @Test
    fun `routines survive profile deletion via reassignment`() = runTest {
        val routine = Routine(
            id = "routine-orphan",
            name = "Orphan Routine",
            exercises = emptyList(),
            profileId = "profile-to-delete",
        )
        repository.saveRoutine(routine)

        // Verify it's saved under profile-to-delete
        val rawBefore = database.vitruvianDatabaseQueries
            .selectRoutineById("routine-orphan")
            .executeAsOneOrNull()
        assertEquals("profile-to-delete", rawBefore?.profile_id)

        // Simulate cascade reassignment (what deleteProfile does)
        database.vitruvianDatabaseQueries.reassignRoutineProfile("default", "profile-to-delete")

        // Verify routine now belongs to default profile
        val rawAfter = database.vitruvianDatabaseQueries
            .selectRoutineById("routine-orphan")
            .executeAsOneOrNull()
        assertEquals("default", rawAfter?.profile_id)

        // Verify it shows up in getAllRoutines for default profile
        repository.getAllRoutines("default").test {
            val routines = awaitItem()
            assertTrue(routines.any { it.id == "routine-orphan" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Helper Methods ==========

    private fun createTestSession(id: String = "test-session", timestamp: Long = System.currentTimeMillis()) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "OldSchool",
        reps = 10,
        weightPerCableKg = 25f,
        totalReps = 10,
        workingReps = 10,
        exerciseId = "test-exercise",
        exerciseName = "Test Exercise",
    )

    // ========== ISSUE #586: ROUTINE-TIME-ESTIMATION SQL ELIGIBILITY ==========

    /**
     * Issue #586 regression: `getAverageSetDurationMs` and `getSessionCountForExercise`
     * must use the same eligibility filter so the SQL count threshold and the SQL
     * average are derived from the same rows. Otherwise stale, sync-deleted,
     * zero-rep, or implausibly-long rows (wall-clock elapsed time written by
     * ActiveSessionEngine) can become the average and break routine time estimation.
     */
    @Test
    fun `getAverageSetDurationMs and getSessionCountForExercise ignore deleted zero-rep and implausibly-long rows`() = runTest {
        val exerciseId = "586-bench"
        val profileId = "default"

        // Plausible completed row (45s avg): eligible.
        repository.saveSession(
            createTestSession(id = "586-good-1", timestamp = 1000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 45_000L,
                    workingReps = 10,
                    profileId = profileId,
                ),
        )
        repository.saveSession(
            createTestSession(id = "586-good-2", timestamp = 2000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 60_000L,
                    workingReps = 12,
                    profileId = profileId,
                ),
        )
        repository.saveSession(
            createTestSession(id = "586-good-3", timestamp = 3000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 30_000L,
                    workingReps = 8,
                    profileId = profileId,
                ),
        )

        // Implausibly-long row (2 hours): wall-clock elapsed time, not a set.
        // Must be excluded by the SQL duration cap. To prove the *duration* is what
        // disqualifies this row, force totalReps/workingReps both to 0 so the new
        // (workingReps > 0 OR totalReps > 0) eligibility condition is also false.
        repository.saveSession(
            createTestSession(id = "586-corrupt-long", timestamp = 4000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 2L * 60L * 60L * 1000L, // 2 hours
                    totalReps = 0,
                    workingReps = 0,
                    profileId = profileId,
                ),
        )

        // Zero-working-rep row (canceled/warmup-only): must be excluded.
        // Also force totalReps = 0 so it is unambiguously a zero-rep row.
        repository.saveSession(
            createTestSession(id = "586-zero-rep", timestamp = 5000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 60_000L,
                    totalReps = 0,
                    workingReps = 0,
                    profileId = profileId,
                ),
        )

        // Sync-deleted row: must be excluded. Keep workingReps/totalReps at default
        // so deletion is the only thing disqualifying it.
        repository.saveSession(
            createTestSession(id = "586-deleted", timestamp = 6000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 60_000L,
                    profileId = profileId,
                ),
        )
        database.vitruvianDatabaseQueries.softDeleteSession(
            id = "586-deleted",
            deletedAt = 6500L,
            updatedAt = 6500L,
        )

        // Different exercise — must not affect the average or count for `exerciseId`.
        repository.saveSession(
            createTestSession(id = "586-other-ex", timestamp = 7000L)
                .copy(
                    exerciseId = "other-exercise",
                    duration = 600_000L, // 10 minutes
                    workingReps = 10,
                    profileId = profileId,
                ),
        )

        // Different profile — must not affect this profile's average or count.
        repository.saveSession(
            createTestSession(id = "586-other-profile", timestamp = 8000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 600_000L, // 10 minutes
                    workingReps = 10,
                    profileId = "other-profile",
                ),
        )

        // Expected average over the 3 plausible rows: (45 + 60 + 30) / 3 = 45_000ms
        val expectedAvg = (45_000L + 60_000L + 30_000L) / 3L

        val avg = repository.getAverageSetDurationMs(exerciseId, profileId)
        val count = repository.getSessionCountForExercise(exerciseId, profileId)

        assertEquals(
            expectedAvg,
            avg,
            "Average must exclude implausibly-long, zero-rep, and sync-deleted rows",
        )
        assertEquals(
            3L,
            count,
            "Count must use the same eligibility filter as the average query",
        )
    }

    /**
     * Issue #586 boundary check: rows at exactly the 20-minute eligibility cap
     * (matching MAX_PLAUSIBLE_SET_DURATION_MS in the estimator) are still counted
     * and included in the average so the SQL filter and the estimator guard agree.
     */
    @Test
    fun `rows at exactly 20 minute duration are eligible for average and count`() = runTest {
        val exerciseId = "586-boundary"
        val profileId = "default"
        val boundaryMs = 20L * 60L * 1000L

        repository.saveSession(
            createTestSession(id = "586-b-1", timestamp = 1000L)
                .copy(exerciseId = exerciseId, duration = boundaryMs, workingReps = 10, profileId = profileId),
        )
        repository.saveSession(
            createTestSession(id = "586-b-2", timestamp = 2000L)
                .copy(exerciseId = exerciseId, duration = boundaryMs, workingReps = 10, profileId = profileId),
        )
        repository.saveSession(
            createTestSession(id = "586-b-3", timestamp = 3000L)
                .copy(exerciseId = exerciseId, duration = boundaryMs, workingReps = 10, profileId = profileId),
        )

        assertEquals(boundaryMs, repository.getAverageSetDurationMs(exerciseId, profileId))
        assertEquals(3L, repository.getSessionCountForExercise(exerciseId, profileId))
    }

    /**
     * Issue #586 boundary check: a row one millisecond above the 20-minute cap
     * must be excluded by the SQL filter even when its other fields look fine.
     */
    @Test
    fun `row one ms over 20 minute duration is excluded by SQL filter`() = runTest {
        val exerciseId = "586-over-boundary"
        val profileId = "default"

        repository.saveSession(
            createTestSession(id = "586-ob-1", timestamp = 1000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 20L * 60L * 1000L + 1L,
                    workingReps = 10,
                    profileId = profileId,
                ),
        )

        assertNull(
            repository.getAverageSetDurationMs(exerciseId, profileId),
            "A row one ms over the 20-minute cap must not contribute to the average",
        )
        assertEquals(
            0L,
            repository.getSessionCountForExercise(exerciseId, profileId),
            "An over-cap row must not be counted as a session",
        )
    }

    /**
     * Issue #586 follow-up: rows that only populate `totalReps` (with
     * `workingReps == 0`) are still treated as completed sets by the rest of the
     * schema (selectCompletedHealthExportCandidates, selectSessionsByRoutineSessionId,
     * selectSessionsForPhasePRBackfill). They must contribute to the historical
     * average and count for routine time estimation so imported, legacy, or tagged
     * JustLift sessions don't silently disappear from history.
     */
    @Test
    fun `totalReps-only rows are eligible for average and count`() = runTest {
        val exerciseId = "586-totalreps-only"
        val profileId = "default"

        // totalReps-only rows: workingReps == 0, but the row is completed.
        repository.saveSession(
            createTestSession(id = "586-tr-1", timestamp = 1000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 40_000L,
                    totalReps = 10,
                    workingReps = 0,
                    profileId = profileId,
                ),
        )
        repository.saveSession(
            createTestSession(id = "586-tr-2", timestamp = 2000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 50_000L,
                    totalReps = 12,
                    workingReps = 0,
                    profileId = profileId,
                ),
        )

        assertEquals(
            45_000L,
            repository.getAverageSetDurationMs(exerciseId, profileId),
            "totalReps-only rows must be included in the historical average",
        )
        assertEquals(
            2L,
            repository.getSessionCountForExercise(exerciseId, profileId),
            "totalReps-only rows must be counted as completed sessions",
        )

        // A row with both totalReps == 0 AND workingReps == 0 is a true zero-rep
        // row and must still be excluded.
        repository.saveSession(
            createTestSession(id = "586-zero-both", timestamp = 3000L)
                .copy(
                    exerciseId = exerciseId,
                    duration = 60_000L,
                    totalReps = 0,
                    workingReps = 0,
                    profileId = profileId,
                ),
        )

        assertEquals(
            45_000L,
            repository.getAverageSetDurationMs(exerciseId, profileId),
            "Rows with both totalReps == 0 and workingReps == 0 must not change the average",
        )
        assertEquals(
            2L,
            repository.getSessionCountForExercise(exerciseId, profileId),
            "Rows with both totalReps == 0 and workingReps == 0 must not be counted",
        )
    }
}
