package com.devil.phoenixproject.util

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `full backup excludes soft-deleted workout sessions`() = runTest {
        workoutRepository.saveSession(
            WorkoutSession(
                id = "active-session",
                routineSessionId = "active-routine",
                exerciseName = "Bench Press",
                totalReps = 10,
                workingReps = 10,
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "deleted-session",
                routineSessionId = "deleted-routine",
                exerciseName = "Incline Fly",
                totalReps = 0,
                workingReps = 0,
            ),
        )
        database.vitruvianDatabaseQueries.softDeleteSessionsByRoutineSessionId(
            deletedAt = 1_700_000_123_000L,
            updatedAt = 1_700_000_123_000L,
            routineSessionId = "deleted-routine",
        )

        val legacyBackup = backupManager.exportAllData()
        val streamedBackup = testJson.decodeFromString<BackupData>(backupManager.exportToJson())

        assertEquals(listOf("active-session"), legacyBackup.data.workoutSessions.map { it.id })
        assertEquals(listOf("active-session"), streamedBackup.data.workoutSessions.map { it.id })
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
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
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

    // --- Per-routine auto-backup (exportRoutine) tests — Issue #525 ---

    @Test
    fun `exportRoutine collapses multiple WorkoutSession rows sharing one routineSessionId into one backup`() = runTest {
        val sharedRoutineSessionId = "routine-session-shared"
        // Two sessions in the same routine: bench, then row
        workoutRepository.saveSession(
            WorkoutSession(
                id = "routine-bench",
                exerciseId = "exercise-bench",
                exerciseName = "Bench Press",
                routineSessionId = sharedRoutineSessionId,
                routineName = "Push Day",
                timestamp = 1_700_000_000_000L,
                mode = "OLD_SCHOOL",
                reps = 10,
                weightPerCableKg = 50f,
                duration = 120_000L,
                totalReps = 10,
                workingReps = 10,
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "routine-row",
                exerciseId = "exercise-row",
                exerciseName = "Row",
                routineSessionId = sharedRoutineSessionId,
                routineName = "Push Day",
                timestamp = 1_700_000_100_000L,
                mode = "OLD_SCHOOL",
                reps = 10,
                weightPerCableKg = 40f,
                duration = 120_000L,
                totalReps = 10,
                workingReps = 10,
            ),
        )
        // A control session NOT in this routine must NOT show up
        workoutRepository.saveSession(
            WorkoutSession(
                id = "routine-curl",
                exerciseId = "exercise-curl",
                exerciseName = "Curl",
                routineSessionId = "different-routine-session",
                routineName = "Arms Day",
                timestamp = 1_700_000_200_000L,
                mode = "OLD_SCHOOL",
                reps = 10,
                weightPerCableKg = 20f,
                duration = 60_000L,
                totalReps = 10,
                workingReps = 10,
            ),
        )
        // One completed set per routine session
        database.vitruvianDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-bench",
            session_id = "routine-bench",
            planned_set_id = null,
            set_number = 1,
            set_type = "STANDARD",
            actual_reps = 10,
            actual_weight_kg = 50.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1_700_000_006_000L,
        )
        database.vitruvianDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-row",
            session_id = "routine-row",
            planned_set_id = null,
            set_number = 1,
            set_type = "STANDARD",
            actual_reps = 10,
            actual_weight_kg = 40.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1_700_000_106_000L,
        )

        val result = backupManager.exportRoutine(sharedRoutineSessionId)
        assertTrue(result.isSuccess, "exportRoutine should succeed: ${result.exceptionOrNull()?.message}")

        val filePath = result.getOrThrow()
        assertTrue(filePath.contains("phoenix-routine-"), "Filename should follow routine convention")
        assertTrue(filePath.contains(sharedRoutineSessionId), "Filename should contain routineSessionId")

        val fileContent = File(filePath).readText()
        val backupData = testJson.decodeFromString<BackupData>(fileContent)

        // Both routine sessions in, control session out
        val sessionIds = backupData.data.workoutSessions.map { it.id }.toSet()
        assertEquals(setOf("routine-bench", "routine-row"), sessionIds)
        // Completed sets from BOTH sessions in the routine must be included
        val completedSetIds = backupData.data.completedSets.map { it.id }.toSet()
        assertEquals(setOf("cs-bench", "cs-row"), completedSetIds)
        // completedSets.sessionId references must match the included sessions
        val completedSetSessionIds = backupData.data.completedSets.map { it.sessionId }.toSet()
        assertEquals(setOf("routine-bench", "routine-row"), completedSetSessionIds)

        File(filePath).delete()
    }

    @Test
    fun `exportRoutine returns failure when no sessions share the routineSessionId`() = runTest {
        val result = backupManager.exportRoutine("non-existent-routine")
        assertTrue(result.isFailure, "Should fail for unknown routineSessionId")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("No sessions found for routine") == true,
            "Error message should explain failure mode: ${result.exceptionOrNull()?.message}",
        )
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
        queries.insertDefaultProfilePreferences("userA", 1L)
        queries.setActiveProfile("userA")

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
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
        queries.insertRoutine(
            id = "routine-existing",
            name = "Upper Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 3,
            profile_id = "userA",
            groupId = null,
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
        queries.insertDefaultProfilePreferences("userA", 1L)
        queries.insertDefaultProfilePreferences("userB", 1L)
        queries.setActiveProfile("userA")

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
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
        queries.insertRoutine(
            id = "routine-b",
            name = "Leg Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 1,
            profile_id = "userB",
            groupId = null,
        )

        // 3. Restore a backup that explicitly says these rows belong to "userB"
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-03-29T00:00:00Z",
            appVersion = "test",
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
                        id = "routine-b",
                        name = "Leg Day",
                        createdAt = 1_700_000_000_000,
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
            template_id = null,
            week_number = 1L,
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
    fun `v5 buffered and streaming exports include valid profile sections and exclude legacy and local-only state`() = runTest {
        val fixture = profileFixture()
        seedDistinctProfilePreferences(fixture)
        fixture.safetyStore.write(
            PROFILE_A,
            ProfileLocalSafetyPreferences(
                safeWord = "distinctive-do-not-export-phrase",
                safeWordCalibrated = true,
                adultsOnlyConfirmed = true,
                adultsOnlyPrompted = true,
            ),
        )
        executeSql(
            fixture.driver,
            "UPDATE UserProfilePreferences SET workout_preferences_json = ? WHERE profile_id = ?",
            "{broken-workout-json",
            PROFILE_B,
        )

        val buffered = fixture.manager.exportToJson()
        val streaming = File(fixture.manager.exportToCachePublic()).readText()

        listOf(buffered, streaming).forEach { output ->
            val root = testJson.parseToJsonElement(output).jsonObject
            val data = root.getValue("data").jsonObject
            assertEquals(5, root.getValue("version").jsonPrimitive.content.toInt())
            assertEquals(setOf(PROFILE_A, PROFILE_B, "default"), data.getValue("userProfiles").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                .toSet())
            val preferenceEntries = data.getValue("profilePreferences").jsonArray
                .associateBy { it.jsonObject.getValue("profileId").jsonPrimitive.content }
            assertEquals(3, preferenceEntries.size)
            assertEquals("70.0", preferenceEntries.getValue(PROFILE_A).jsonObject
                .getValue("core").jsonObject.getValue("bodyWeightKg").jsonPrimitive.content)
            assertTrue("rack" in preferenceEntries.getValue(PROFILE_A).jsonObject)
            assertTrue("led" in preferenceEntries.getValue(PROFILE_A).jsonObject)
            assertTrue("vbt" in preferenceEntries.getValue(PROFILE_A).jsonObject)
            assertFalse("workout" in preferenceEntries.getValue(PROFILE_B).jsonObject)
            assertFalse("equipmentRackItems" in data)
            listOf(
                "localGeneration",
                "serverRevision",
                "dirty",
                "distinctive-do-not-export-phrase",
                "safeWord",
                "safe_word",
                "calibrated",
                "adultsOnly",
                "adult_confirm",
                "adult_prompt",
            ).forEach { forbidden -> assertFalse(output.contains(forbidden, ignoreCase = true), forbidden) }
        }
    }

    @Test
    fun `v1 through v3 ignore supplied preferences and legacy rack`() = runTest {
        for (version in 1..3) {
            val fixture = profileFixture()
            seedDistinctProfilePreferences(fixture)
            val beforeA = fixture.preferences.get(PROFILE_A)
            val payload = rawBackupJson(
                version = version,
                identities = listOf(profileBackup(PROFILE_A)),
                profilePreferences = listOf(
                    preferenceEntry(
                        PROFILE_A,
                        core = jsonElement(CoreProfilePreferences(120f, WeightUnit.LB, 10f)),
                        rack = jsonElement(RackPreferences()),
                    ),
                ),
                legacyRackPresent = true,
                legacyRack = JsonArray(emptyList()),
            )

            val result = fixture.manager.importFromJson(payload)

            assertTrue(result.isSuccess, "v$version: ${result.exceptionOrNull()}")
            val afterA = fixture.preferences.get(PROFILE_A)
            assertEquals(beforeA.core.value, afterA.core.value, "v$version core")
            assertEquals(beforeA.rack.value, afterA.rack.value, "v$version rack")
        }
    }

    @Test
    fun `v4 legacy rack preserves all raw states and targets represented profiles exactly once`() = runTest {
        suspend fun importRack(
            present: Boolean,
            raw: JsonElement,
            identities: List<UserProfileBackup> = listOf(profileBackup(PROFILE_A), profileBackup(PROFILE_B)),
        ): Pair<PreferenceFixture, ImportResult> {
            val fixture = profileFixture()
            seedDistinctProfilePreferences(fixture)
            val payload = rawBackupJson(
                version = 4,
                identities = identities,
                profilePreferences = listOf(
                    preferenceEntry(PROFILE_A, rack = jsonElement(RackPreferences())),
                ),
                legacyRackPresent = present,
                legacyRack = raw,
            )
            return fixture to fixture.manager.importFromJson(payload).getOrThrow()
        }

        val missing = importRack(false, JsonNull).first
        assertEquals(listOf("a-existing", "shared"), missing.preferences.get(PROFILE_A).rack.value.items.map { it.id })

        val explicitNull = importRack(true, JsonNull)
        assertEquals(1, explicitNull.second.entitiesWithErrors)
        assertEquals(listOf("a-existing", "shared"), explicitNull.first.preferences.get(PROFILE_A).rack.value.items.map { it.id })

        val scalar = importRack(true, JsonPrimitive("wrong-kind"))
        assertEquals(1, scalar.second.entitiesWithErrors)
        assertEquals(listOf("a-existing", "shared"), scalar.first.preferences.get(PROFILE_A).rack.value.items.map { it.id })

        val malformedArray = importRack(
            true,
            JsonArray(listOf(buildJsonObject { put("id", "missing-required-fields") })),
        )
        assertEquals(1, malformedArray.second.entitiesWithErrors)
        assertEquals(listOf("a-existing", "shared"), malformedArray.first.preferences.get(PROFILE_A).rack.value.items.map { it.id })

        val empty = importRack(true, JsonArray(emptyList())).first
        assertTrue(empty.preferences.get(PROFILE_A).rack.value.items.isEmpty())
        assertTrue(empty.preferences.get(PROFILE_B).rack.value.items.isEmpty())

        val replacement = rackItem("shared", "Imported shared", 99f)
        val appended = rackItem("new-item", "New item", 3f)
        val merged = importRack(true, jsonElement(listOf(replacement, appended))).first
        assertEquals(
            listOf("a-existing", "shared", "new-item"),
            merged.preferences.get(PROFILE_A).rack.value.items.map { it.id },
        )
        assertEquals("Imported shared", merged.preferences.get(PROFILE_A).rack.value.items[1].name)
        assertEquals(
            listOf("b-existing", "shared", "new-item"),
            merged.preferences.get(PROFILE_B).rack.value.items.map { it.id },
        )

        val fallback = importRack(true, JsonArray(emptyList()), identities = emptyList()).first
        assertTrue(fallback.preferences.get(PROFILE_A).rack.value.items.isEmpty())
        assertEquals(
            listOf("b-existing", "shared"),
            fallback.preferences.get(PROFILE_B).rack.value.items.map { it.id },
            "active fallback must not also mutate unrelated profiles",
        )
    }

    @Test
    fun `v5 restores valid sections independently through repository metadata semantics and allowlist`() = runTest {
        val fixture = profileFixture()
        seedDistinctProfilePreferences(fixture)
        insertProfile(fixture, PROFILE_C, active = false)
        fixture.preferences.updateCore(PROFILE_C, CoreProfilePreferences(55f, WeightUnit.KG, 1f), 2L)
        executeSql(
            fixture.driver,
            "UPDATE UserProfilePreferences SET core_server_revision = 42, core_local_generation = 7, core_dirty = 0 WHERE profile_id = ?",
            PROFILE_A,
        )
        fixture.safetyStore.write(PROFILE_A, ProfileLocalSafetyPreferences("target-secret", true, true, true))
        val beforeA = fixture.preferences.get(PROFILE_A)
        val beforeB = fixture.preferences.get(PROFILE_B)
        val payload = rawBackupJson(
            version = 5,
            identities = listOf(profileBackup(PROFILE_A), profileBackup(PROFILE_B)),
            profilePreferences = listOf(
                preferenceEntry(
                    PROFILE_A,
                    core = JsonPrimitive("wrong-kind"),
                    workout = jsonElement(WorkoutPreferences(stopAtTop = true, summaryCountdownSeconds = 30)),
                    led = jsonElement(LedPreferences(colorScheme = 11, discoModeUnlocked = true)),
                ),
                preferenceEntry(
                    PROFILE_B,
                    core = jsonElement(CoreProfilePreferences(101f, WeightUnit.LB, 5f)),
                    vbt = jsonElement(VbtPreferences(enabled = false, velocityLossThresholdPercent = 45)),
                ),
                preferenceEntry(
                    PROFILE_C,
                    core = jsonElement(CoreProfilePreferences(130f, WeightUnit.KG, 2f)),
                ),
                preferenceEntry(
                    "missing-identity",
                    core = jsonElement(CoreProfilePreferences(140f, WeightUnit.KG, 2f)),
                ),
            ),
            legacyRackPresent = true,
            legacyRack = JsonArray(emptyList()),
        )

        val result = fixture.manager.importFromJson(payload)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(1, result.getOrThrow().entitiesWithErrors)
        val afterA = fixture.preferences.get(PROFILE_A)
        val afterB = fixture.preferences.get(PROFILE_B)
        assertEquals(beforeA.core.value, afterA.core.value, "invalid core is non-destructive")
        assertEquals(42L, afterA.core.metadata.serverRevision)
        assertEquals(7L, afterA.core.metadata.localGeneration)
        assertFalse(afterA.core.metadata.dirty)
        assertEquals(30, afterA.workout.value.summaryCountdownSeconds)
        assertEquals(11, afterA.led.value.colorScheme)
        assertEquals(101f, afterB.core.value.bodyWeightKg)
        assertEquals(beforeB.core.metadata.serverRevision, afterB.core.metadata.serverRevision)
        assertEquals(beforeB.core.metadata.localGeneration + 1, afterB.core.metadata.localGeneration)
        assertTrue(afterB.core.metadata.dirty)
        assertEquals(55f, fixture.preferences.get(PROFILE_C).core.value.bodyWeightKg)
        assertEquals("target-secret", fixture.safetyStore.read(PROFILE_A).safeWord)
        assertEquals(1, fixture.userProfiles.reconcileCalls)
        assertEquals(70f, fixture.userProfiles.observedPreferences?.core?.value?.bodyWeightKg)
    }

    @Test
    fun `v5 ignores legacy rack and v6 restores known fields while dropping unknown fields`() = runTest {
        val fixture = profileFixture()
        seedDistinctProfilePreferences(fixture)
        val entry = buildJsonObject {
            put("profileId", PROFILE_A)
            put("core", buildJsonObject {
                put("bodyWeightKg", 88f)
                put("weightUnit", "KG")
                put("weightIncrement", 2.5f)
                put("unknownCoreField", true)
            })
            put("unknownEntryField", buildJsonObject { put("nested", true) })
        }
        val v6 = rawBackupJson(
            version = 6,
            identities = listOf(profileBackup(PROFILE_A)),
            profilePreferences = listOf(entry),
            legacyRackPresent = true,
            legacyRack = JsonArray(emptyList()),
            unknownData = true,
            unknownRoot = true,
        )

        val result = fixture.manager.importFromJson(v6)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(88f, fixture.preferences.get(PROFILE_A).core.value.bodyWeightKg)
        assertEquals(
            listOf("a-existing", "shared"),
            fixture.preferences.get(PROFILE_A).rack.value.items.map { it.id },
            "v5+ must ignore a supplied legacy rack",
        )
    }

    @Test
    fun `backup active flags are informational and reconciliation failures do not replace restore failures`() = runTest {
        listOf(
            listOf(false, false),
            listOf(false, true),
            listOf(true, true),
        ).forEach { flags ->
            val fixture = profileFixture()
            seedDistinctProfilePreferences(fixture)
            val payload = rawBackupJson(
                version = 5,
                identities = listOf(
                    profileBackup(PROFILE_A, flags[0]),
                    profileBackup(PROFILE_B, flags[1]),
                ),
            )
            assertTrue(fixture.manager.importFromJson(payload).isSuccess)
            val profiles = fixture.database.vitruvianDatabaseQueries.getAllProfiles().executeAsList()
            assertEquals(PROFILE_A, profiles.single { it.isActive == 1L }.id)
        }

        val restoreFailure = IllegalStateException("preference storage failed")
        val reconcileFailure = IllegalArgumentException("reconcile failed")
        val fixture = profileFixture(
            preferenceDecorator = { delegate ->
                FaultingProfilePreferencesRepository(delegate, restoreFailure)
            },
            reconcileFailure = reconcileFailure,
        )
        val payload = rawBackupJson(
            version = 5,
            identities = listOf(profileBackup("default")),
            profilePreferences = listOf(
                preferenceEntry(
                    "default",
                    core = jsonElement(CoreProfilePreferences(80f, WeightUnit.KG, 2.5f)),
                ),
            ),
        )

        val result = fixture.manager.importFromJson(payload)

        assertTrue(result.isFailure)
        assertSame(restoreFailure, result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()!!.suppressed.any { it.message == reconcileFailure.message })
        assertEquals(1, fixture.userProfiles.reconcileCalls)
    }

    @Test
    fun `identity query failure is fatal for buffered and streaming export and deletes partial output`() = runTest {
        val driver = FailingIdentityQueryDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val fixture = profileFixture(driver)
        seedDistinctProfilePreferences(fixture)
        driver.failIdentityQueries = true

        val bufferedFailure = runCatching { fixture.manager.exportAllData() }.exceptionOrNull()
        assertNotNull(bufferedFailure, "buffered export must not substitute an empty identity list")

        val streamingFailure = runCatching { fixture.manager.exportToCachePublic() }.exceptionOrNull()
        assertNotNull(streamingFailure, "streaming export must not substitute an empty identity list")
        val partialPath = assertNotNull(fixture.manager.lastWriterPath)
        assertFalse(File(partialPath).exists(), "failed streaming export must delete its partial file")
    }

    @Test
    fun `missing profile preference aggregate is fatal for both exports and deletes streaming partial output`() = runTest {
        val fixture = profileFixture()
        seedDistinctProfilePreferences(fixture)
        executeSql(
            fixture.driver,
            "DELETE FROM UserProfilePreferences WHERE profile_id = ?",
            PROFILE_B,
        )

        val bufferedFailure = runCatching { fixture.manager.exportAllData() }.exceptionOrNull()
        assertNotNull(bufferedFailure, "buffered export must fail when an identity aggregate is missing")

        val streamingFailure = runCatching { fixture.manager.exportToCachePublic() }.exceptionOrNull()
        assertNotNull(streamingFailure, "streaming export must fail when an identity aggregate is missing")
        val partialPath = assertNotNull(fixture.manager.lastWriterPath)
        assertFalse(File(partialPath).exists(), "failed streaming export must delete its partial file")
    }

    @Test
    fun `buffered legacy and absent explicit owners use represented fallback when active is absent`() = runTest {
        val fixture = profileFixture()
        val represented = "first-represented"
        fun session(id: String, profileId: String?) = WorkoutSessionBackup(
            id = id,
            timestamp = 1L,
            mode = "Old School",
            targetReps = 1,
            weightPerCableKg = 1f,
            progressionKg = 0f,
            duration = 1L,
            totalReps = 1,
            warmupReps = 0,
            workingReps = 1,
            isJustLift = false,
            stopAtTop = false,
            profileId = profileId,
        )
        val explicitDefault = session("explicit-default", "original-owner")
        val explicitRepresented = session("explicit-represented", "original-owner")
        val seedResult = fixture.manager.importFromJson(
            testJson.encodeToString(
                BackupData(
                    version = 5,
                    exportedAt = "seed",
                    appVersion = "test",
                    data = BackupContent(
                        workoutSessions = listOf(explicitDefault, explicitRepresented),
                    ),
                ),
            ),
        )
        assertTrue(seedResult.isSuccess, seedResult.exceptionOrNull()?.toString())
        executeSql(fixture.driver, "DELETE FROM UserProfile WHERE id = ?", "default")
        val payload = BackupData(
            version = 5,
            exportedAt = "2026-07-12T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                userProfiles = listOf(profileBackup(represented)),
                workoutSessions = listOf(
                    session("legacy-fallback-session", null),
                    explicitDefault.copy(profileId = "default"),
                    explicitRepresented.copy(profileId = represented),
                ),
            ),
        )

        val result = fixture.manager.importFromJson(testJson.encodeToString(payload))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(
            represented,
            fixture.database.vitruvianDatabaseQueries
                .selectSessionById("legacy-fallback-session")
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.vitruvianDatabaseQueries
                .selectSessionById(explicitDefault.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.vitruvianDatabaseQueries
                .selectSessionById(explicitRepresented.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
    }

    @Test
    fun `buffered session import adopts explicit owner when its profile is absent`() = runTest {
        val activeProfileId = database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id
        val session = WorkoutSessionBackup(
            id = "buffered-missing-profile-session",
            timestamp = 1L,
            mode = "Old School",
            targetReps = 1,
            weightPerCableKg = 1f,
            progressionKg = 0f,
            duration = 1L,
            totalReps = 1,
            warmupReps = 0,
            workingReps = 1,
            isJustLift = false,
            stopAtTop = false,
            profileId = "deleted-source-profile",
        )
        val payload = BackupData(
            version = 5,
            exportedAt = "2026-07-15T00:00:00Z",
            appVersion = "test",
            data = BackupContent(workoutSessions = listOf(session)),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload))

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(
            activeProfileId,
            database.vitruvianDatabaseQueries.selectSessionById(session.id).executeAsOne().profile_id,
        )
    }

    @Test
    fun `v5 round-trip restores profile rack and routine rack defaults`() = runTest {
        val source = profileFixture()
        seedDistinctProfilePreferences(source)
        source.preferences.updateRack(
            PROFILE_A,
            RackPreferences(items = listOf(
                RackItem(
                    id = "vest",
                    name = "Weighted Vest",
                    category = RackItemCategory.WEIGHTED_VEST,
                    weightKg = 10f,
                    behavior = RackItemBehavior.ADDED_RESISTANCE,
                ),
            )),
            30L,
        )
        val sourceWorkoutRepository = SqlDelightWorkoutRepository(source.database, FakeExerciseRepository())
        sourceWorkoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-rack-backup",
                routineName = "Rack Backup",
                exerciseId = "exercise-rack-backup",
                exerciseName = "Weighted Pull Up",
            ).let { routine ->
                routine.copy(
                    exercises = routine.exercises.map { exercise ->
                        exercise.copy(defaultRackItemIds = listOf("vest"))
                    },
                )
            },
        )

        val backupJson = source.manager.exportToJson()
        val target = profileFixture()

        val importResult = target.manager.importFromJson(backupJson)

        assertTrue(importResult.isSuccess, "v5 backup import must succeed: ${importResult.exceptionOrNull()?.message}")
        assertEquals("vest", target.preferences.get(PROFILE_A).rack.value.items.single().id)
        val importedExercise = target.database.vitruvianDatabaseQueries
            .selectAllRoutineExercisesSync()
            .executeAsList()
            .single()
        assertEquals("""["vest"]""", importedExercise.defaultRackItemIds)
    }

    @Test
    fun `backup round-trip preserves routine exercise scalingBasis`() = runTest {
        // Issue #517 Phase 3: a user who sets scalingBasis (e.g. ESTIMATED_1RM) must not
        // silently lose it across export -> import. Mirrors the v4 rack-defaults round-trip.
        val sourceManager = TestDataBackupManager(database)
        workoutRepository.saveRoutine(
            buildRoutine(
                routineId = "routine-scaling-backup",
                routineName = "Scaling Backup",
                exerciseId = "exercise-scaling-backup",
                exerciseName = "Bench Press",
            ).let { routine ->
                routine.copy(
                    exercises = routine.exercises.map { exercise ->
                        exercise.copy(scalingBasis = ScalingBasis.ESTIMATED_1RM)
                    },
                )
            },
        )

        // Confirm it was persisted on the source DB before export.
        val sourceExercise = database.vitruvianDatabaseQueries
            .selectAllRoutineExercisesSync()
            .executeAsList()
            .single()
        assertEquals("ESTIMATED_1RM", sourceExercise.scalingBasis, "scalingBasis must persist on source DB")

        val backupJson = sourceManager.exportToJson()
        val targetDatabase = createTestDatabase()
        val targetManager = TestDataBackupManager(targetDatabase)

        val importResult = targetManager.importFromJson(backupJson)
        assertTrue(importResult.isSuccess, "Backup import must succeed: ${importResult.exceptionOrNull()?.message}")

        val importedExercise = targetDatabase.vitruvianDatabaseQueries
            .selectAllRoutineExercisesSync()
            .executeAsList()
            .single()
        assertEquals(
            "ESTIMATED_1RM",
            importedExercise.scalingBasis,
            "scalingBasis must survive export -> import round-trip",
        )
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

    private fun profileFixture(
        driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
        preferenceDecorator: (ProfilePreferencesRepository) -> ProfilePreferencesRepository = { it },
        reconcileFailure: Throwable? = null,
    ): PreferenceFixture {
        VitruvianDatabase.Schema.create(driver)
        val fixtureDatabase = VitruvianDatabase(driver)
        val realPreferences = SqlDelightProfilePreferencesRepository(fixtureDatabase)
        val effectivePreferences = preferenceDecorator(realPreferences)
        val safetyStore = SettingsProfileLocalSafetyStore(MapSettings())
        val realUserProfiles = SqlDelightUserProfileRepository(
            database = fixtureDatabase,
            profilePreferencesRepository = effectivePreferences,
            profileLocalSafetyStore = safetyStore,
            gamificationRepository = SqlDelightGamificationRepository(fixtureDatabase),
        )
        fixtureDatabase.vitruvianDatabaseQueries.seedMissingProfilePreferences()
        val recordingUserProfiles = RecordingUserProfileRepository(
            delegate = realUserProfiles,
            preferences = effectivePreferences,
            reconciliationFailure = reconcileFailure,
        )
        return PreferenceFixture(
            driver = driver,
            database = fixtureDatabase,
            preferences = effectivePreferences,
            safetyStore = safetyStore,
            userProfiles = recordingUserProfiles,
            manager = TestDataBackupManager(
                database = fixtureDatabase,
                profilePreferencesRepository = effectivePreferences,
                userProfileRepository = recordingUserProfiles,
            ),
        )
    }

    private suspend fun seedDistinctProfilePreferences(fixture: PreferenceFixture) {
        insertProfile(fixture, PROFILE_A, active = false)
        insertProfile(fixture, PROFILE_B, active = false)
        fixture.database.vitruvianDatabaseQueries.setActiveProfile(PROFILE_A)
        fixture.preferences.updateCore(PROFILE_A, CoreProfilePreferences(70f, WeightUnit.KG, 2.5f), 10L)
        fixture.preferences.updateRack(
            PROFILE_A,
            RackPreferences(items = listOf(
                rackItem("a-existing", "A existing", 1f),
                rackItem("shared", "A shared", 2f),
            )),
            11L,
        )
        fixture.preferences.updateWorkout(
            PROFILE_A,
            WorkoutPreferences(stopAtTop = true, summaryCountdownSeconds = 5),
            12L,
        )
        fixture.preferences.updateLed(PROFILE_A, LedPreferences(colorScheme = 2), 13L)
        fixture.preferences.updateVbt(
            PROFILE_A,
            VbtPreferences(enabled = false, velocityLossThresholdPercent = 30),
            14L,
        )

        fixture.preferences.updateCore(PROFILE_B, CoreProfilePreferences(90f, WeightUnit.LB, 5f), 20L)
        fixture.preferences.updateRack(
            PROFILE_B,
            RackPreferences(items = listOf(
                rackItem("b-existing", "B existing", 4f),
                rackItem("shared", "B shared", 5f),
            )),
            21L,
        )
        fixture.preferences.updateWorkout(
            PROFILE_B,
            WorkoutPreferences(beepsEnabled = false, summaryCountdownSeconds = 15),
            22L,
        )
        fixture.preferences.updateLed(PROFILE_B, LedPreferences(colorScheme = 7), 23L)
        fixture.preferences.updateVbt(
            PROFILE_B,
            VbtPreferences(enabled = true, velocityLossThresholdPercent = 40),
            24L,
        )
    }

    private fun insertProfile(fixture: PreferenceFixture, id: String, active: Boolean) {
        if (fixture.database.vitruvianDatabaseQueries.getProfileById(id).executeAsOneOrNull() == null) {
            fixture.database.vitruvianDatabaseQueries.insertProfile(
                id = id,
                name = id,
                colorIndex = 0L,
                createdAt = when (id) {
                    PROFILE_A -> 100L
                    PROFILE_B -> 200L
                    else -> 300L
                },
                isActive = if (active) 1L else 0L,
            )
        }
        fixture.database.vitruvianDatabaseQueries.insertDefaultProfilePreferences(id, 1L)
        if (active) fixture.database.vitruvianDatabaseQueries.setActiveProfile(id)
    }

    private inline fun <reified T> jsonElement(value: T): JsonElement =
        testJson.encodeToJsonElement(value)

    private fun rackItem(id: String, name: String, weight: Float) = RackItem(
        id = id,
        name = name,
        category = RackItemCategory.OTHER,
        weightKg = weight,
        behavior = RackItemBehavior.ADDED_RESISTANCE,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun profileBackup(id: String, active: Boolean = false) = UserProfileBackup(
        id = id,
        name = id,
        colorIndex = 0,
        createdAt = when (id) {
            PROFILE_A -> 100L
            PROFILE_B -> 200L
            else -> 300L
        },
        isActive = active,
    )

    private fun preferenceEntry(
        profileId: String,
        core: JsonElement? = null,
        rack: JsonElement? = null,
        workout: JsonElement? = null,
        led: JsonElement? = null,
        vbt: JsonElement? = null,
    ): JsonElement = buildJsonObject {
        put("profileId", profileId)
        core?.let { put("core", it) }
        rack?.let { put("rack", it) }
        workout?.let { put("workout", it) }
        led?.let { put("led", it) }
        vbt?.let { put("vbt", it) }
    }

    private fun rawBackupJson(
        version: Int,
        identities: List<UserProfileBackup> = emptyList(),
        profilePreferences: List<JsonElement> = emptyList(),
        legacyRackPresent: Boolean = false,
        legacyRack: JsonElement = JsonNull,
        unknownData: Boolean = false,
        unknownRoot: Boolean = false,
    ): String = buildJsonObject {
        put("version", version)
        put("exportedAt", "2026-07-12T00:00:00Z")
        put("appVersion", "test")
        put("data", buildJsonObject {
            put("userProfiles", testJson.encodeToJsonElement(identities))
            put("profilePreferences", JsonArray(profilePreferences))
            if (legacyRackPresent) put("equipmentRackItems", legacyRack)
            if (unknownData) put("futureData", buildJsonObject { put("ignored", true) })
        })
        if (unknownRoot) put("futureRoot", JsonArray(listOf(JsonPrimitive(1))))
    }.toString()

    private fun executeSql(driver: SqlDriver, sql: String, vararg values: Any?) {
        driver.execute(null, sql, values.size) {
            values.forEachIndexed { index, value ->
                when (value) {
                    null -> bindString(index, null)
                    is String -> bindString(index, value)
                    is Long -> bindLong(index, value)
                    is Int -> bindLong(index, value.toLong())
                    is Double -> bindDouble(index, value)
                    else -> error("Unsupported SQL value: $value")
                }
            }
        }
    }

    private data class PreferenceFixture(
        val driver: SqlDriver,
        val database: VitruvianDatabase,
        val preferences: ProfilePreferencesRepository,
        val safetyStore: ProfileLocalSafetyStore,
        val userProfiles: RecordingUserProfileRepository,
        val manager: TestDataBackupManager,
    )

    private class RecordingUserProfileRepository(
        private val delegate: UserProfileRepository,
        private val preferences: ProfilePreferencesRepository,
        private val reconciliationFailure: Throwable? = null,
    ) : UserProfileRepository by delegate {
        var reconcileCalls: Int = 0
            private set
        var observedPreferences: UserProfilePreferences? = null
            private set

        override suspend fun reconcileActiveProfileContext() {
            reconcileCalls++
            reconciliationFailure?.let { throw it }
            delegate.reconcileActiveProfileContext()
            delegate.activeProfile.value?.id?.let { activeId ->
                observedPreferences = preferences.get(activeId)
            }
        }
    }

    private class FaultingProfilePreferencesRepository(
        private val delegate: ProfilePreferencesRepository,
        private val failure: Throwable,
    ) : ProfilePreferencesRepository by delegate {
        override suspend fun updateCore(profileId: String, value: CoreProfilePreferences, now: Long) {
            throw failure
        }
    }

    private class FailingIdentityQueryDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var failIdentityQueries: Boolean = false

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (failIdentityQueries &&
                (identifier == SELECT_ALL_USER_PROFILES_IDENTIFIER || sql.contains("FROM UserProfile"))
            ) {
                throw IllegalStateException("injected identity export query failure")
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        private companion object {
            const val SELECT_ALL_USER_PROFILES_IDENTIFIER = -107_782_522
        }
    }

    private companion object {
        const val PROFILE_A = "profile-a"
        const val PROFILE_B = "profile-b"
        const val PROFILE_C = "profile-c"

        fun createTestUserProfileRepository(
            database: VitruvianDatabase,
            preferences: ProfilePreferencesRepository,
        ): UserProfileRepository = SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = preferences,
            profileLocalSafetyStore = SettingsProfileLocalSafetyStore(MapSettings()),
            gamificationRepository = SqlDelightGamificationRepository(database),
        ).also {
            database.vitruvianDatabaseQueries.seedMissingProfilePreferences()
        }
    }

    private class TestDataBackupManager(
        database: com.devil.phoenixproject.database.VitruvianDatabase,
        val profilePreferencesRepository: ProfilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
        val userProfileRepository: UserProfileRepository = createTestUserProfileRepository(
            database,
            profilePreferencesRepository,
        ),
    ) : BaseDataBackupManager(
        database,
        profilePreferencesRepository,
        userProfileRepository,
    ) {

        var lastWriterPath: String? = null
            private set

        override fun createBackupWriter(): BackupJsonWriter {
            val tempFile = File.createTempFile("backup-test-", ".json")
            lastWriterPath = tempFile.absolutePath
            return BackupJsonWriter(tempFile.absolutePath)
        }

        suspend fun exportToCachePublic(): String = exportToCache()

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
