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
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
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
    }
}
