package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                exerciseName = "Bench Press"
            )
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-legacy-1",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
                routineSessionId = null,
                routineName = "Bench Press",
                totalReps = 10,
                workingReps = 10
            )
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
                exerciseName = "Incline Press"
            )
        )
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-b",
                routineName = "Push B",
                exerciseId = "exercise-shared",
                exerciseName = "Incline Press"
            )
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-legacy-2",
                exerciseId = "exercise-shared",
                exerciseName = "Incline Press",
                routineName = "Incline Press",
                totalReps = 8,
                workingReps = 8
            )
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
                        routineId = "routine-import"
                    )
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-import",
                        name = "Tuesday Upper",
                        createdAt = 1_700_000_000_000
                    )
                )
            )
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
                        routineId = null
                    )
                ),
                routines = listOf(
                    RoutineBackup(
                        id = "routine-arms",
                        name = "Arms Day",
                        createdAt = 1_700_000_000_000
                    )
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
                        weightPerCableKg = 8f
                    )
                )
            )
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
                exerciseName = "Bench Press"
            )
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-garbage-1",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
                routineSessionId = null,
                routineName = "Imported Strength Training Session",
                totalReps = 10,
                workingReps = 10
            )
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
                workingReps = 5
            )
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
                        routineId = null
                    )
                )
            )
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
                        routineId = null
                    )
                )
            )
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
                workingReps = 10
            )
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
            completed_at = 1700000060000L
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

    private fun buildRoutine(
        routineId: String,
        routineName: String,
        exerciseId: String,
        exerciseName: String
    ): Routine {
        val exercise = Exercise(
            id = exerciseId,
            name = exerciseName,
            muscleGroup = "Chest"
        )
        val routineExercise = RoutineExercise(
            id = "$routineId-$exerciseId",
            exercise = exercise,
            orderIndex = 0,
            weightPerCableKg = 10f
        )
        return Routine(
            id = routineId,
            name = routineName,
            exercises = listOf(routineExercise)
        )
    }

    private class TestDataBackupManager(database: com.devil.phoenixproject.database.VitruvianDatabase) :
        BaseDataBackupManager(database) {

        override fun createBackupWriter(): BackupJsonWriter {
            val tempFile = File.createTempFile("backup-test-", ".json")
            return BackupJsonWriter(tempFile.absolutePath)
        }

        override suspend fun finalizeExport(tempFilePath: String): Result<String> {
            return Result.success(tempFilePath)
        }

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
