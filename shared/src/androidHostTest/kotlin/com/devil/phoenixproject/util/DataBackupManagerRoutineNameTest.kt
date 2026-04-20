package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

class DataBackupManagerRoutineNameTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var backupManager: TestDataBackupManager
    private val testJson = Json { encodeDefaults = true }

    @Before
    fun setup() {
        database = createTestDatabase()
        workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        backupManager = TestDataBackupManager(database)
    }

    @Test
    fun `exportAllData resolves placeholder routine name when mapping is unique`() = runTest {
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-upper",
                routineName = "Upper Day",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-legacy-1",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
                routineSessionId = null,
                routineName = "Bench Press",
                totalReps = 10,
                workingReps = 10,
            ),
        )

        val backup = backupManager.exportAllData()
        val exportedSession = backup.data.workoutSessions.first { it.id == "session-legacy-1" }
        assertEquals("Upper Day", exportedSession.routineName)
        assertNull(exportedSession.routineSessionId, "Should not fabricate routineSessionId for legacy sessions")
    }

    @Test
    fun `exportAllData leaves routine name unset when exercise maps to multiple routines`() = runTest {
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-a",
                routineName = "Push A",
                exerciseId = "exercise-shared",
                exerciseName = "Incline Press",
            ),
        )
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-b",
                routineName = "Push B",
                exerciseId = "exercise-shared",
                exerciseName = "Incline Press",
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-legacy-2",
                exerciseId = "exercise-shared",
                exerciseName = "Incline Press",
                routineName = "Incline Press",
                totalReps = 8,
                workingReps = 8,
            ),
        )

        val backup = backupManager.exportAllData()
        val exportedSession = backup.data.workoutSessions.first { it.id == "session-legacy-2" }
        assertNull(exportedSession.routineName)
    }

    @Test
    fun `importFromJson restores routine name from routineId when present`() = runTest {
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-import-1",
                        timestamp = 1_700_000_000_000,
                        mode = "Old School",
                        targetReps = 10,
                        weightPerCableKg = 10f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 10,
                        warmupReps = 0,
                        workingReps = 10,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "exercise-row",
                        exerciseName = "Row",
                        routineSessionId = null,
                        routineName = null,
                        routineId = "routine-import",
                    ),
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-import",
                        name = "Tuesday Upper",
                        createdAt = 1_700_000_000_000,
                    ),
                ),
            ),
        )

        val importResult = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(importResult.isSuccess)

        val imported = database.vitruvianDatabaseQueries
            .selectSessionById("session-import-1")
            .executeAsOneOrNull()
        assertNotNull(imported)
        assertEquals("Tuesday Upper", imported.routineName)
        assertNull(imported.routineSessionId, "Should not fabricate routineSessionId on import")
    }

    @Test
    fun `importFromJson infers routine name from unique exercise mapping when routineId missing`() = runTest {
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-import-2",
                        timestamp = 1_700_000_000_001,
                        mode = "Old School",
                        targetReps = 10,
                        weightPerCableKg = 10f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 10,
                        warmupReps = 0,
                        workingReps = 10,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "exercise-curl",
                        exerciseName = "Bicep Curl",
                        routineSessionId = null,
                        routineName = "Bicep Curl",
                        routineId = null,
                    ),
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-arms",
                        name = "Arms Day",
                        createdAt = 1_700_000_000_000,
                    ),
                ),
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "routine-exercise-curl",
                        routineId = "routine-arms",
                        exerciseName = "Bicep Curl",
                        exerciseMuscleGroup = "Biceps",
                        exerciseDefaultCableConfig = "DOUBLE",
                        exerciseId = "exercise-curl",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "10,10,10",
                        weightPerCableKg = 8f,
                    ),
                ),
            ),
        )

        val importResult = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(importResult.isSuccess)

        val imported = database.vitruvianDatabaseQueries
            .selectSessionById("session-import-2")
            .executeAsOneOrNull()
        assertNotNull(imported)
        assertEquals("Arms Day", imported.routineName)
    }

    @Test
    fun `exportAllData filters garbage routine name from external import`() = runTest {
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-upper",
                routineName = "Upper Day",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-garbage-1",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
                routineSessionId = null,
                routineName = "Imported Strength Training Session",
                totalReps = 10,
                workingReps = 10,
            ),
        )

        val backup = backupManager.exportAllData()
        val exportedSession = backup.data.workoutSessions.first { it.id == "session-garbage-1" }
        // Should infer "Upper Day" instead of keeping garbage name
        assertEquals("Upper Day", exportedSession.routineName)
    }

    @Test
    fun `exportAllData filters garbage routine name to null when no inference possible`() = runTest {
        // No routines defined — inference will fail
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-garbage-2",
                exerciseId = "exercise-something",
                exerciseName = "Some Exercise",
                routineSessionId = null,
                routineName = "Imported Strength Training Session",
                totalReps = 5,
                workingReps = 5,
            ),
        )

        val backup = backupManager.exportAllData()
        val exportedSession = backup.data.workoutSessions.first { it.id == "session-garbage-2" }
        assertNull(exportedSession.routineName, "Garbage routine name should be filtered to null when no inference available")
    }

    @Test
    fun `exportAllData strips fabricated legacy_session routineSessionId`() = runTest {
        // Simulate a session that was previously imported with a fabricated legacy_session_* ID
        database.vitruvianDatabaseQueries.insertSession(
            id = "session-fabricated-1",
            timestamp = 1_700_000_000_000,
            mode = "Old School",
            targetReps = 10,
            weightPerCableKg = 10.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 10,
            warmupReps = 0,
            workingReps = 10,
            isJustLift = 0,
            stopAtTop = 0,
            eccentricLoad = 100,
            echoLevel = 0,
            exerciseId = "exercise-press",
            exerciseName = "Chest Press",
            routineSessionId = "legacy_session_session-fabricated-1",
            routineName = "Upper Day",
            routineId = null,
            safetyFlags = 0,
            deloadWarningCount = 0,
            romViolationCount = 0,
            spotterActivations = 0,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "default",
        )

        val backup = backupManager.exportAllData()
        val exportedSession = backup.data.workoutSessions.first { it.id == "session-fabricated-1" }
        assertNull(exportedSession.routineSessionId, "Fabricated legacy_session_* ID should be stripped on export")
        assertEquals("Upper Day", exportedSession.routineName, "Routine name should be preserved")
    }

    @Test
    fun `importFromJson strips fabricated legacy_session routineSessionId`() = runTest {
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-import-fabricated",
                        timestamp = 1_700_000_000_003,
                        mode = "Old School",
                        targetReps = 10,
                        weightPerCableKg = 10f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 10,
                        warmupReps = 0,
                        workingReps = 10,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "exercise-squat",
                        exerciseName = "Squat",
                        routineSessionId = "legacy_session_session-import-fabricated",
                        routineName = "Leg Day",
                        routineId = null,
                    ),
                ),
            ),
        )

        val importResult = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(importResult.isSuccess)

        val imported = database.vitruvianDatabaseQueries
            .selectSessionById("session-import-fabricated")
            .executeAsOneOrNull()
        assertNotNull(imported)
        assertNull(imported.routineSessionId, "Fabricated legacy_session_* ID should be stripped on import")
        assertEquals("Leg Day", imported.routineName, "Routine name should be preserved")
    }

    @Test
    fun `importFromJson filters garbage routine name`() = runTest {
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-import-garbage",
                        timestamp = 1_700_000_000_002,
                        mode = "Old School",
                        targetReps = 10,
                        weightPerCableKg = 10f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 10,
                        warmupReps = 0,
                        workingReps = 10,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "exercise-unknown",
                        exerciseName = "Unknown Exercise",
                        routineSessionId = null,
                        routineName = "Imported Strength Training Session",
                        routineId = null,
                    ),
                ),
            ),
        )

        val importResult = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(importResult.isSuccess)

        val imported = database.vitruvianDatabaseQueries
            .selectSessionById("session-import-garbage")
            .executeAsOneOrNull()
        assertNotNull(imported)
        assertNull(imported.routineName, "Garbage routine name should be filtered out on import")
    }

    // --- Per-session auto-backup (exportSession) tests ---

    @Test
    fun `exportSession produces import-compatible BackupData with session and completedSets`() = runTest {
        // Insert a session
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-export-test",
                exerciseId = "exercise-squat",
                exerciseName = "Squat",
                timestamp = 1700000000000L,
                mode = "OLD_SCHOOL",
                reps = 10,
                weightPerCableKg = 50f,
                duration = 120_000L,
                totalReps = 10,
                workingReps = 10,
            ),
        )

        // Insert a completed set for that session
        database.vitruvianDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-1",
            session_id = "session-export-test",
            planned_set_id = null,
            set_number = 1,
            set_type = "STANDARD",
            actual_reps = 10,
            actual_weight_kg = 50.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1700000060000L,
        )

        // Export just this session
        val result = backupManager.exportSession("session-export-test")
        assertTrue(result.isSuccess, "exportSession should succeed")

        val filePath = result.getOrThrow()
        assertTrue(filePath.contains("phoenix-workout-"), "Filename should follow convention")
        assertTrue(filePath.contains("session-export-test"), "Filename should contain full sessionId")

        // Read the written file and verify it's valid, import-compatible BackupData
        val fileContent = File(filePath).readText()
        val backupData = testJson.decodeFromString<BackupData>(fileContent)

        assertEquals(1, backupData.data.workoutSessions.size, "Should contain exactly 1 session")
        assertEquals("session-export-test", backupData.data.workoutSessions[0].id)
        assertEquals(1, backupData.data.completedSets.size, "Should include completedSets for the session")
        assertEquals("cs-1", backupData.data.completedSets[0].id)
        assertEquals("session-export-test", backupData.data.completedSets[0].sessionId)

        // Verify it can be re-imported (import compatibility)
        // First delete the session so import has room
        database.vitruvianDatabaseQueries.deleteSession("session-export-test")
        database.vitruvianDatabaseQueries.deleteCompletedSetsBySession("session-export-test")

        val importResult = backupManager.importFromJson(fileContent)
        assertTrue(importResult.isSuccess, "Should be importable")
        assertEquals(1, importResult.getOrThrow().sessionsImported)
        assertEquals(1, importResult.getOrThrow().completedSetsImported)

        // Clean up
        File(filePath).delete()
    }

    @Test
    fun `exportSession returns failure for non-existent session`() = runTest {
        val result = backupManager.exportSession("non-existent-session")
        assertTrue(result.isFailure, "Should fail for non-existent session")
        assertTrue(result.exceptionOrNull()?.message?.contains("Session not found") == true)
    }

    /**
     * Regression test for #324: restoring a legacy backup (null profileId) while the active
     * profile is NOT "default" must adopt skipped records into the active profile, not
     * reassign them to "default" (which would make them invisible).
     */
    @Test
    fun `restore legacy backup adopts skipped records into active profile not default`() = runTest {
        val queries = database.vitruvianDatabaseQueries

        // 1. Create a non-default profile and make it active
        queries.insertProfile(
            id = "userA",
            name = "User A",
            colorIndex = 1L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )

        // 2. Insert a session and routine owned by "userA"
        queries.insertSession(
            id = "session-existing",
            timestamp = 1_700_000_000_000,
            mode = "Old School",
            targetReps = 10,
            weightPerCableKg = 20.0,
            progressionKg = 0.0,
            duration = 60_000L,
            totalReps = 10,
            warmupReps = 0,
            workingReps = 10,
            isJustLift = 0,
            stopAtTop = 0,
            eccentricLoad = 100,
            echoLevel = 0,
            exerciseId = "exercise-bench",
            exerciseName = "Bench Press",
            routineSessionId = null,
            routineName = null,
            routineId = null,
            safetyFlags = 0,
            deloadWarningCount = 0,
            romViolationCount = 0,
            spotterActivations = 0,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "userA",
        )
        queries.insertRoutine(
            id = "routine-existing",
            name = "Upper Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 3,
            profile_id = "userA",
        )

        // 3. Build a legacy backup with null profileId containing the same IDs
        val legacyBackup = BackupData(
            version = 1,
            exportedAt = "2026-03-29T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-existing",
                        timestamp = 1_700_000_000_000,
                        mode = "Old School",
                        targetReps = 10,
                        weightPerCableKg = 20f,
                        progressionKg = 0f,
                        duration = 60_000L,
                        totalReps = 10,
                        warmupReps = 0,
                        workingReps = 10,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "exercise-bench",
                        exerciseName = "Bench Press",
                        profileId = null, // legacy backup — no profileId
                    ),
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-existing",
                        name = "Upper Day",
                        createdAt = 1_700_000_000_000,
                        profileId = null, // legacy backup — no profileId
                    ),
                ),
            ),
        )

        // 4. Import the legacy backup
        val result = backupManager.importFromJson(testJson.encodeToString(legacyBackup))
        assertTrue(result.isSuccess)

        // 5. Verify: records should still belong to "userA" (the active profile),
        //    NOT reassigned to "default"
        val session = queries.selectSessionById("session-existing").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("userA", session.profile_id, "Skipped session must stay in active profile, not be reassigned to default")

        val routine = queries.selectRoutineById("routine-existing").executeAsOneOrNull()
        assertNotNull(routine)
        assertEquals("userA", routine.profile_id, "Skipped routine must stay in active profile, not be reassigned to default")
    }

    /**
     * Multi-profile restore: a backup with explicit profileId for another profile must NOT
     * be adopted into the active profile. This prevents cross-contamination when restoring
     * a full multi-profile backup.
     */
    @Test
    fun `restore with explicit foreign profileId does not adopt into active profile`() = runTest {
        val queries = database.vitruvianDatabaseQueries

        // 1. Create two profiles; make "userA" active
        queries.insertProfile(id = "userA", name = "User A", colorIndex = 1L, createdAt = 1_700_000_000_000, isActive = 1L)
        queries.insertProfile(id = "userB", name = "User B", colorIndex = 2L, createdAt = 1_700_000_000_001, isActive = 0L)

        // 2. Insert a session owned by "userB"
        queries.insertSession(
            id = "session-b", timestamp = 1_700_000_000_000, mode = "Old School",
            targetReps = 10, weightPerCableKg = 20.0, progressionKg = 0.0,
            duration = 60_000L, totalReps = 10, warmupReps = 0, workingReps = 10,
            isJustLift = 0, stopAtTop = 0, eccentricLoad = 100, echoLevel = 0,
            exerciseId = "exercise-bench", exerciseName = "Bench Press",
            routineSessionId = null, routineName = null, routineId = null,
            safetyFlags = 0, deloadWarningCount = 0, romViolationCount = 0, spotterActivations = 0,
            peakForceConcentricA = null, peakForceConcentricB = null,
            peakForceEccentricA = null, peakForceEccentricB = null,
            avgForceConcentricA = null, avgForceConcentricB = null,
            avgForceEccentricA = null, avgForceEccentricB = null,
            heaviestLiftKg = null, totalVolumeKg = null, cableCount = null,
            estimatedCalories = null, warmupAvgWeightKg = null, workingAvgWeightKg = null,
            burnoutAvgWeightKg = null, peakWeightKg = null, rpe = null,
            avgMcvMmS = null, avgAsymmetryPercent = null, totalVelocityLossPercent = null,
            dominantSide = null, strengthProfile = null, formScore = null,
            profile_id = "userB",
        )
        queries.insertRoutine(
            id = "routine-b", name = "Leg Day", description = "",
            createdAt = 1_700_000_000_000, lastUsed = null, useCount = 1, profile_id = "userB",
        )

        // 3. Restore a backup that explicitly says these rows belong to "userB"
        val backup = BackupData(
            version = 1, exportedAt = "2026-03-29T00:00:00Z", appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-b", timestamp = 1_700_000_000_000, mode = "Old School",
                        targetReps = 10, weightPerCableKg = 20f, progressionKg = 0f,
                        duration = 60_000L, totalReps = 10, warmupReps = 0, workingReps = 10,
                        isJustLift = false, stopAtTop = false,
                        exerciseId = "exercise-bench", exerciseName = "Bench Press",
                        profileId = "userB", // explicit foreign profile
                    ),
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-b", name = "Leg Day", createdAt = 1_700_000_000_000,
                        profileId = "userB", // explicit foreign profile
                    ),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(result.isSuccess)

        // 4. Verify: records must still belong to "userB", not adopted into "userA"
        val session = queries.selectSessionById("session-b").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("userB", session.profile_id, "Session with explicit foreign profileId must not be adopted")

        val routine = queries.selectRoutineById("routine-b").executeAsOneOrNull()
        assertNotNull(routine)
        assertEquals("userB", routine.profile_id, "Routine with explicit foreign profileId must not be adopted")
    }

    private fun buildRoutine(routineId: String, routineName: String, exerciseId: String, exerciseName: String): Routine {
        val exercise = Exercise(
            id = exerciseId,
            name = exerciseName,
            muscleGroup = "Chest",
        )
        val routineExercise = RoutineExercise(
            id = "$routineId-$exerciseId",
            exercise = exercise,
            orderIndex = 0,
            weightPerCableKg = 10f,
        )
        return Routine(
            id = routineId,
            name = routineName,
            exercises = listOf(routineExercise),
        )
    }

    // --- v2 backup schema drift regression tests (Reddit beta report 2026-04-19) ---
    //
    // Users reported: "backups from latest version will crash the app. A back up from
    // a month ago worked." Root cause: BackupModels drifted from schema (SessionNotes
    // table added, EarnedBadge/GamificationStats sync fields, CycleDay per-day overrides)
    // which produced misleading round-trips and — in corner cases — per-row failures that
    // aborted the whole import.
    //
    // These tests lock in the v2 behaviour:
    //   1. Export-then-import preserves the new fields end-to-end.
    //   2. Legacy v1 backups still import (forward compat).
    //   3. A single malformed entity does not torpedo the whole import.

    @Test
    fun `v2 round-trip preserves SessionNotes data`() = runTest {
        val queries = database.vitruvianDatabaseQueries
        queries.upsertSessionNotes(
            routineSessionId = "rs-notes-1",
            notes = "felt strong today; DOMS in triceps",
            updatedAt = 1_700_000_000_000L,
        )

        val backup = backupManager.exportAllData()
        assertEquals(CURRENT_BACKUP_VERSION, backup.version, "Fresh exports must advertise v$CURRENT_BACKUP_VERSION")
        assertEquals(1, backup.data.sessionNotes.size)
        assertEquals("felt strong today; DOMS in triceps", backup.data.sessionNotes[0].notes)

        // Clear and re-import
        queries.upsertSessionNotes(routineSessionId = "rs-notes-1", notes = null, updatedAt = null)
        val reimport = backupManager.importFromJson(testJson.encodeToString(backup))
        assertTrue(reimport.isSuccess, "Round-trip import must succeed: ${reimport.exceptionOrNull()?.message}")
        assertEquals(0, reimport.getOrThrow().entitiesWithErrors, "Clean round-trip must not produce skipped rows")
    }

    @Test
    fun `v2 export preserves EarnedBadge sync fields so restore does not re-push`() = runTest {
        val queries = database.vitruvianDatabaseQueries
        // Insert a badge that has already been pushed to the portal (serverId set,
        // updatedAt set). The backup must preserve these so a restore does not
        // produce a phantom duplicate on the server.
        queries.insertEarnedBadgeFullIgnore(
            badgeId = "first_pr",
            earnedAt = 1_700_000_000_000L,
            celebratedAt = 1_700_000_010_000L,
            updatedAt = 1_700_000_020_000L,
            serverId = "srv-abc-123",
            deletedAt = null,
            profile_id = "default",
        )

        val backup = backupManager.exportAllData()
        val backedUp = backup.data.earnedBadges.single { it.badgeId == "first_pr" }
        assertEquals(1_700_000_020_000L, backedUp.updatedAt, "updatedAt must survive export")
        assertEquals("srv-abc-123", backedUp.serverId, "serverId must survive export")
        assertEquals(null, backedUp.deletedAt, "deletedAt null must survive export")
    }

    @Test
    fun `v2 export preserves CycleDay per-day progression overrides`() = runTest {
        val queries = database.vitruvianDatabaseQueries
        // Build a cycle with a day that has all new per-day override fields populated.
        queries.insertTrainingCycle(
            id = "cycle-drift",
            name = "Drift Test Cycle",
            description = null,
            created_at = 1_700_000_000_000L,
            is_active = 0L,
            profile_id = "default",
        )
        queries.insertCycleDay(
            id = "day-drift",
            cycle_id = "cycle-drift",
            day_number = 1L,
            name = "Heavy Day",
            routine_id = null,
            is_rest_day = 0L,
            echo_level = "HIGH",
            eccentric_load_percent = 110L,
            weight_progression_percent = 2.5,
            rep_modifier = -2L,
            rest_time_override_seconds = 180L,
        )

        val backup = backupManager.exportAllData()
        val backedUp = backup.data.cycleDays.single { it.id == "day-drift" }
        assertEquals("HIGH", backedUp.echoLevel, "echo_level must round-trip")
        assertEquals(110, backedUp.eccentricLoadPercent, "eccentric_load_percent must round-trip")
        assertEquals(2.5f, backedUp.weightProgressionPercent, "weight_progression_percent must round-trip")
        assertEquals(-2, backedUp.repModifier, "rep_modifier must round-trip")
        assertEquals(180, backedUp.restTimeOverrideSeconds, "rest_time_override_seconds must round-trip")
    }

    @Test
    fun `v1 backup imports without crashing despite missing fields`() = runTest {
        // Simulate a legacy (v1) backup JSON with no `sessionNotes` array and no
        // EarnedBadge sync fields. kotlinx.serialization defaults must fill them in.
        val v1Json = """
            {
              "version": 1,
              "exportedAt": "2026-03-19T12:00:00Z",
              "appVersion": "test-v1",
              "data": {
                "workoutSessions": [],
                "metricSamples": [],
                "routines": [],
                "routineExercises": [],
                "supersets": [],
                "personalRecords": [],
                "trainingCycles": [],
                "cycleDays": [],
                "cycleProgress": [],
                "cycleProgressions": [],
                "plannedSets": [],
                "completedSets": [],
                "progressionEvents": [],
                "earnedBadges": [
                  { "id": 1, "badgeId": "old_badge", "earnedAt": 1700000000000, "celebratedAt": null, "profileId": "default" }
                ],
                "streakHistory": [],
                "gamificationStats": null,
                "userProfiles": []
              }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(v1Json)
        assertTrue(result.isSuccess, "v1 backup must import cleanly: ${result.exceptionOrNull()?.message}")
        val imported = result.getOrThrow()
        assertEquals(1, imported.earnedBadgesImported, "v1 badge must import")
        assertEquals(0, imported.sessionNotesImported, "v1 has no notes — counter stays 0")
        assertEquals(0, imported.entitiesWithErrors, "v1 must not trigger per-entity errors")
    }

    @Test
    fun `malformed top-level JSON surfaces specific error instead of crashing`() = runTest {
        // Deliberately malformed: trailing comma, missing required fields.
        val junk = """{ "version": 2, "exportedAt": "x", "appVersion": "x", "data": { "workoutSessions": [{}] } }"""
        val result = backupManager.importFromJson(junk)
        assertTrue(result.isFailure, "Malformed JSON must fail fast with a typed error")
        val error = result.exceptionOrNull()!!
        // The hardening wraps deserialization failures in IllegalArgumentException with
        // a human-readable prefix so the UI can surface a friendly message.
        assertTrue(
            error is IllegalArgumentException,
            "Expected IllegalArgumentException, got ${error::class.simpleName}: ${error.message}",
        )
        assertTrue(
            error.message?.contains("malformed or produced by an incompatible") == true,
            "Error message must explain the failure mode — got: ${error.message}",
        )
    }

    private class TestDataBackupManager(database: com.devil.phoenixproject.database.VitruvianDatabase) : BaseDataBackupManager(database) {

        override fun createBackupWriter(): BackupJsonWriter {
            val tempFile = File.createTempFile("backup-test-", ".json")
            return BackupJsonWriter(tempFile.absolutePath)
        }

        override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)

        override suspend fun saveToFile(backup: BackupData): Result<String> {
            error("Not needed for tests")
        }

        override suspend fun importFromFile(filePath: String): Result<ImportResult> {
            error("Not needed for tests")
        }

        override suspend fun shareBackup() = Unit

        override fun getSessionBackupDirectory(): String {
            val dir = File(System.getProperty("java.io.tmpdir"), "PhoenixBackupsTest")
            if (!dir.exists()) dir.mkdirs()
            return dir.absolutePath
        }

        override fun listBackupFileSizes(): List<Long> {
            val dir = File(getSessionBackupDirectory())
            return dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.map { it.length() }
                ?: emptyList()
        }

        override fun openBackupFolder() = Unit
        override fun pruneOldBackups(keepCount: Int) = Unit
    }
}
