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
import com.devil.phoenixproject.database.PhoenixDatabase
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
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.testutil.seedExercise
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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

    private lateinit var database: com.devil.phoenixproject.database.PhoenixDatabase
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
    fun `full backup preserves deleted routines and their children in both exporters`() = runTest {
        workoutRepository.saveRoutine(
            buildRoutine("routine-active", "Active", "exercise-active", "Bench Press"),
        )
        workoutRepository.saveRoutine(
            buildRoutine("routine-deleted", "Deleted", "exercise-deleted", "Row"),
        )
        database.phoenixDatabaseQueries.softDeleteRoutine(
            deletedAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_001L,
            id = "routine-deleted",
        )

        val legacy = backupManager.exportAllData()
        assertEquals(listOf("routine-active", "routine-deleted"), legacy.data.routines.map { it.id })
        assertEquals(1_700_000_000_000L, legacy.data.routines.first { it.id == "routine-deleted" }.deletedAt)
        assertEquals(listOf("routine-active-exercise-active", "routine-deleted-exercise-deleted"), legacy.data.routineExercises.map { it.id })

        val streamingPath = backupManager.exportToCachePublic()
        val streaming = testJson.decodeFromString<BackupData>(File(streamingPath).readText())
        assertEquals(listOf("routine-active", "routine-deleted"), streaming.data.routines.map { it.id })
        assertEquals(1_700_000_000_000L, streaming.data.routines.first { it.id == "routine-deleted" }.deletedAt)
        assertEquals(listOf("routine-active-exercise-active", "routine-deleted-exercise-deleted"), streaming.data.routineExercises.map { it.id })
        File(streamingPath).delete()
    }

    @Test
    fun `normal backup excludes local active workout runtime recovery data`() = runTest {
        workoutRepository.saveSession(
            WorkoutSession(
                id = "ordinary-exported-session",
                routineSessionId = "ordinary-routine-session",
                exerciseName = "Bench Press",
                totalReps = 8,
                workingReps = 8,
            ),
        )
        database.phoenixDatabaseQueries.replaceActiveWorkoutRuntime(
            profile_id = "runtime-only-profile-key-673",
            routine_session_id = "runtime-only-session-key-673",
            document_version = 1,
            runtime_json = """{"version":1,"sourceStableSessionId":"runtime-secret-673"}""",
            updated_at_epoch_ms = 1_700_000_000_000,
        )

        val exportedJson = backupManager.exportToJson()
        val decoded = testJson.decodeFromString<BackupData>(exportedJson)

        assertEquals(listOf("ordinary-exported-session"), decoded.data.workoutSessions.map { it.id })
        assertFalse(exportedJson.contains("runtime-only-profile-key-673"))
        assertFalse(exportedJson.contains("runtime-only-session-key-673"))
        assertFalse(exportedJson.contains("runtime-secret-673"))
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
        database.phoenixDatabaseQueries.softDeleteSessionsByRoutineSessionId(
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
    fun `full backup excludes soft-deleted personal records`() = runTest {
        fun insertPersonalRecord(exerciseId: String, uuid: String) {
            database.phoenixDatabaseQueries.insertRecord(
                exerciseId = exerciseId,
                exerciseName = exerciseId,
                weight = 80.0,
                reps = 5L,
                oneRepMax = 93.33,
                achievedAt = 1_700_000_000_000L,
                workoutMode = "Old School",
                prType = "MAX_WEIGHT",
                volume = 400.0,
                phase = "COMBINED",
                profile_id = "default",
                cable_count = 2L,
                uuid = uuid,
            )
        }

        insertPersonalRecord("active-pr", "12345678-1234-4abc-8def-1234567890ab")
        insertPersonalRecord("deleted-pr", "22345678-1234-4abc-8def-1234567890ab")
        val deleted = database.phoenixDatabaseQueries
            .selectRecordsByExercise("deleted-pr", profileId = "default")
            .executeAsOne()
        database.phoenixDatabaseQueries.softDeletePRById(
            deletedAt = 1_700_000_123_000L,
            updatedAt = 1_700_000_123_000L,
            id = deleted.id,
            profileId = "default",
        )

        val legacyBackup = backupManager.exportAllData()
        val streamedBackup = testJson.decodeFromString<BackupData>(backupManager.exportToJson())

        assertEquals(listOf("active-pr"), legacyBackup.data.personalRecords.map { it.exerciseId })
        assertEquals(listOf("active-pr"), streamedBackup.data.personalRecords.map { it.exerciseId })
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
    fun `restoring a pre-remap backup re-points legacy catalogue ids onto their replacements`() = runTest {
        database.seedExercise("ZZ92N8QsBdp6HCh3", name = "Bench Press", archived = true)
        database.seedExercise("Barbell_Bench_Press_-_Medium_Grip", name = "Barbell Bench Press - Medium Grip")
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-pre-remap",
                        timestamp = 1_700_000_000_000,
                        mode = "Old School",
                        targetReps = 5,
                        weightPerCableKg = 40f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 5,
                        warmupReps = 0,
                        workingReps = 5,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "ZZ92N8QsBdp6HCh3",
                        exerciseName = "Bench Press",
                        routineSessionId = null,
                        routineName = null,
                        routineId = null,
                    ),
                ),
            ),
        )

        assertTrue(backupManager.importFromJson(testJson.encodeToString(backup)).isSuccess)

        assertEquals(
            "Barbell_Bench_Press_-_Medium_Grip",
            database.phoenixDatabaseQueries.selectSessionById("session-pre-remap").executeAsOne().exerciseId,
        )
    }

    @Test
    fun `restoring a pre-remap backup onto a fresh install translates legacy catalogue ids`() = runTest {
        // Fresh install: only the current catalogue, no archived legacy row to hang a remap on.
        database.seedExercise("Barbell_Bench_Press_-_Medium_Grip", name = "Barbell Bench Press - Medium Grip")
        val backup = BackupData(
            version = 1,
            exportedAt = "2026-02-21T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-fresh-install",
                        timestamp = 1_700_000_000_000,
                        mode = "Old School",
                        targetReps = 5,
                        weightPerCableKg = 40f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 5,
                        warmupReps = 0,
                        workingReps = 5,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseId = "ZZ92N8QsBdp6HCh3",
                        exerciseName = "Bench Press",
                        routineSessionId = null,
                        routineName = null,
                        routineId = null,
                    ),
                ),
                personalRecords = listOf(
                    PersonalRecordBackup(
                        exerciseId = "ZZ92N8QsBdp6HCh3",
                        exerciseName = "Bench Press",
                        weight = 80f,
                        reps = 5,
                        oneRepMax = 90f,
                        achievedAt = 1_700_000_000_000,
                        workoutMode = "OldSchool",
                    ),
                ),
            ),
        )

        assertTrue(backupManager.importFromJson(testJson.encodeToString(backup)).isSuccess)

        assertEquals(
            "Barbell_Bench_Press_-_Medium_Grip",
            database.phoenixDatabaseQueries.selectSessionById("session-fresh-install").executeAsOne().exerciseId,
        )
        val prs = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("Barbell_Bench_Press_-_Medium_Grip")
            .executeAsList()
        assertEquals(listOf(80.0), prs.map { it.weight })
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

        val imported = database.phoenixDatabaseQueries
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

        val imported = database.phoenixDatabaseQueries
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
        database.phoenixDatabaseQueries.insertSession(
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

        val imported = database.phoenixDatabaseQueries
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

        val imported = database.phoenixDatabaseQueries
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
        database.phoenixDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-1",
            session_id = "session-export-test",
            planned_set_id = null,
            routine_exercise_id = "routine-exercise-export",
            set_number = 1,
            set_type = "STANDARD",
            attempt_number = 3,
            actual_reps = 10,
            actual_weight_kg = 50.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1700000060000L,
            set_end_reason = "TARGET_REPS_REACHED",
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
        assertEquals("routine-exercise-export", backupData.data.completedSets[0].routineExerciseId)
        assertEquals(3, backupData.data.completedSets[0].attemptNumber)

        // Verify it can be re-imported (import compatibility)
        // First delete the session so import has room
        database.phoenixDatabaseQueries.deleteSession("session-export-test")
        database.phoenixDatabaseQueries.deleteCompletedSetsBySession("session-export-test")

        val importResult = backupManager.importFromJson(fileContent)
        assertTrue(importResult.isSuccess, "Should be importable")
        assertEquals(1, importResult.getOrThrow().sessionsImported)
        assertEquals(1, importResult.getOrThrow().completedSetsImported)
        val reimported = database.phoenixDatabaseQueries.selectCompletedSetById("cs-1").executeAsOne()
        assertEquals("routine-exercise-export", reimported.routine_exercise_id)
        assertEquals(3L, reimported.attempt_number)

        // Clean up
        File(filePath).delete()
    }

    @Test
    fun `exportSession packages referenced profile and custom exercise for fresh restore`() = runTest {
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(
            id = "user-alpha",
            name = "Alpha",
            colorIndex = 3L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        queries.insertDefaultProfilePreferences("user-alpha", 1L)
        queries.setActiveProfile("user-alpha")
        val custom = customExerciseBackup("custom-export-session")
        insertCustomExercise(database, custom)

        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-custom-profile",
                exerciseId = custom.id,
                exerciseName = custom.name,
                timestamp = 1_700_000_000_000L,
                mode = "OLD_SCHOOL",
                reps = 8,
                weightPerCableKg = 40f,
                duration = 90_000L,
                totalReps = 8,
                workingReps = 8,
                profileId = "user-alpha",
            ),
        )

        val result = backupManager.exportSession("session-custom-profile")
        assertTrue(result.isSuccess, "exportSession should succeed: ${result.exceptionOrNull()?.message}")
        val filePath = result.getOrThrow()
        val fileContent = File(filePath).readText()
        val backupData = testJson.decodeFromString<BackupData>(fileContent)

        assertEquals(listOf("user-alpha"), backupData.data.userProfiles.map { it.id })
        assertEquals("Alpha", backupData.data.userProfiles.single().name)
        assertEquals(listOf(custom.id), backupData.data.customExercises.map { it.id })
        assertEquals(custom.id, backupData.data.workoutSessions.single().exerciseId)
        assertEquals("user-alpha", backupData.data.workoutSessions.single().profileId)

        val targetDatabase = createTestDatabase()
        val targetManager = TestDataBackupManager(targetDatabase)
        val importResult = targetManager.importFromJson(fileContent).getOrThrow()
        assertEquals(0, importResult.entitiesWithErrors)
        assertEquals(1, importResult.sessionsImported)
        assertEquals(1, importResult.customExercisesImported)
        assertNotNull(targetDatabase.phoenixDatabaseQueries.getProfileById("user-alpha").executeAsOneOrNull())
        val restoredSession = targetDatabase.phoenixDatabaseQueries.selectSessionById("session-custom-profile").executeAsOne()
        assertEquals("user-alpha", restoredSession.profile_id)
        assertEquals(custom.id, restoredSession.exerciseId)
        assertEquals(
            1L,
            targetDatabase.phoenixDatabaseQueries.selectExerciseById(custom.id).executeAsOne().isCustom,
        )

        File(filePath).delete()
    }

    @Test
    fun `buffered and streaming completed set imports canonicalize unknown end reasons`() = runTest {
        val backup = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-08-14T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-future-reason",
                        timestamp = 1L,
                        mode = "OldSchool",
                        targetReps = 8,
                        weightPerCableKg = 40f,
                        progressionKg = 0f,
                        duration = 0L,
                        totalReps = 8,
                        warmupReps = 0,
                        workingReps = 8,
                        isJustLift = false,
                        stopAtTop = false,
                    ),
                ),
                completedSets = listOf(
                    CompletedSetBackup(
                        id = "set-future-reason",
                        sessionId = "session-future-reason",
                        setNumber = 1,
                        actualReps = 8,
                        actualWeightKg = 40f,
                        completedAt = 2L,
                        setEndReason = "FUTURE_REASON",
                        plannedSetId = "missing-planned-set",
                        routineExerciseId = "routine-exercise-import",
                        attemptNumber = 2,
                    ),
                ),
            ),
        )
        val payload = testJson.encodeToString(backup)

        val bufferedResult = backupManager.importFromJson(payload).getOrThrow()
        assertEquals(1, bufferedResult.repairedReferences)
        assertEquals(
            "UNKNOWN",
            database.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne().set_end_reason,
        )
        assertEquals(
            "routine-exercise-import" to 2L,
            database.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne()
                .let { it.routine_exercise_id to it.attempt_number },
        )
        assertNull(database.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne().planned_set_id)

        val streamingDatabase = createTestDatabase()
        val streamingManager = TestDataBackupManager(streamingDatabase)
        val streamingResult = streamingManager.importFromStringStreaming(payload).getOrThrow()
        assertEquals(1, streamingResult.repairedReferences)
        assertEquals(
            "UNKNOWN",
            streamingDatabase.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne().set_end_reason,
        )
        assertEquals(
            "routine-exercise-import" to 2L,
            streamingDatabase.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne()
                .let { it.routine_exercise_id to it.attempt_number },
        )
        assertNull(streamingDatabase.phoenixDatabaseQueries.selectCompletedSetById("set-future-reason").executeAsOne().planned_set_id)
    }

    @Test
    fun `buffered completed set import canonicalizes negative attempt to one`() = runTest {
        assertBufferedInvalidAttemptCanonicalized(-4)
    }

    @Test
    fun `streaming completed set import canonicalizes zero attempt to one`() = runTest {
        assertStreamingInvalidAttemptCanonicalized(0)
    }

    private fun invalidAttemptPayload(sessionId: String, setId: String, invalidAttempt: Int): String =
        testJson.encodeToString(
            BackupData(
                version = CURRENT_BACKUP_VERSION,
                exportedAt = "2026-08-14T00:00:00Z",
                appVersion = "test",
                data = BackupContent(
                    workoutSessions = listOf(
                        WorkoutSessionBackup(
                            id = sessionId,
                            timestamp = 1L,
                            mode = "OldSchool",
                            targetReps = 8,
                            weightPerCableKg = 40f,
                            progressionKg = 0f,
                            duration = 0L,
                            totalReps = 8,
                            warmupReps = 0,
                            workingReps = 8,
                            isJustLift = false,
                            stopAtTop = false,
                        ),
                    ),
                    completedSets = listOf(
                        CompletedSetBackup(
                            id = setId,
                            sessionId = sessionId,
                            setNumber = 0,
                            actualReps = 8,
                            actualWeightKg = 40f,
                            completedAt = 2L,
                            routineExerciseId = "invalid-import-occurrence",
                            attemptNumber = invalidAttempt,
                        ),
                    ),
                ),
            ),
        )

    private suspend fun assertBufferedInvalidAttemptCanonicalized(invalidAttempt: Int) {
        assertTrue(backupManager.importFromJson(invalidAttemptPayload("buffered-invalid-session", "buffered-invalid-set", invalidAttempt)).isSuccess)
        assertEquals(
            1L,
            database.phoenixDatabaseQueries.selectCompletedSetById("buffered-invalid-set").executeAsOne().attempt_number,
        )
    }

    private suspend fun assertStreamingInvalidAttemptCanonicalized(invalidAttempt: Int) {
        val streamingDatabase = createTestDatabase()
        val streamingManager = TestDataBackupManager(streamingDatabase)
        assertTrue(streamingManager.importFromStringStreaming(invalidAttemptPayload("streaming-invalid-session", "streaming-invalid-set", invalidAttempt)).isSuccess)
        assertEquals(
            1L,
            streamingDatabase.phoenixDatabaseQueries.selectCompletedSetById("streaming-invalid-set").executeAsOne().attempt_number,
        )
    }

    @Test
    fun `buffered and streaming completed set exports canonicalize unknown end reasons`() = runTest {
        workoutRepository.saveSession(
            WorkoutSession(
                id = "session-export-future-reason",
                timestamp = 1L,
                mode = "OldSchool",
                reps = 8,
                weightPerCableKg = 40f,
                totalReps = 8,
                workingReps = 8,
            ),
        )
        database.phoenixDatabaseQueries.insertCompletedSetIgnore(
            id = "set-export-future-reason",
            session_id = "session-export-future-reason",
            planned_set_id = null,
            routine_exercise_id = "routine-exercise-export",
            set_number = 1L,
            set_type = "STANDARD",
            attempt_number = 3L,
            actual_reps = 8L,
            actual_weight_kg = 40.0,
            logged_rpe = null,
            is_pr = 0L,
            completed_at = 2L,
            set_end_reason = "FUTURE_REASON",
        )

        val buffered = backupManager.exportAllData()
        val streamingPath = backupManager.exportToCachePublic()
        val streaming = testJson.decodeFromString<BackupData>(File(streamingPath).readText())

        assertEquals("UNKNOWN", buffered.data.completedSets.single().setEndReason)
        assertEquals("UNKNOWN", streaming.data.completedSets.single().setEndReason)
        assertEquals("routine-exercise-export", buffered.data.completedSets.single().routineExerciseId)
        assertEquals(3, buffered.data.completedSets.single().attemptNumber)
        assertEquals("routine-exercise-export", streaming.data.completedSets.single().routineExerciseId)
        assertEquals(3, streaming.data.completedSets.single().attemptNumber)
        File(streamingPath).delete()
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
        database.phoenixDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-bench",
            session_id = "routine-bench",
            planned_set_id = null,
            routine_exercise_id = null,
            set_number = 1,
            set_type = "STANDARD",
            attempt_number = 1,
            actual_reps = 10,
            actual_weight_kg = 50.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1_700_000_006_000L,
            set_end_reason = "TARGET_REPS_REACHED",
        )
        database.phoenixDatabaseQueries.insertCompletedSetIgnore(
            id = "cs-row",
            session_id = "routine-row",
            planned_set_id = null,
            routine_exercise_id = null,
            set_number = 1,
            set_type = "STANDARD",
            attempt_number = 1,
            actual_reps = 10,
            actual_weight_kg = 40.0,
            logged_rpe = null,
            is_pr = 0,
            completed_at = 1_700_000_106_000L,
            set_end_reason = "TARGET_REPS_REACHED",
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
    fun `exportRoutine packages referenced profile and custom exercises for fresh restore`() = runTest {
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(
            id = "user-beta",
            name = "Beta",
            colorIndex = 4L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        queries.insertDefaultProfilePreferences("user-beta", 1L)
        queries.setActiveProfile("user-beta")
        val custom = customExerciseBackup("custom-export-routine")
        insertCustomExercise(database, custom)
        database.seedExercise("exercise-catalog-row", "Row", isCustom = false)
        val sharedRoutineSessionId = "routine-session-custom-profile"

        workoutRepository.saveSession(
            WorkoutSession(
                id = "routine-custom-bench",
                exerciseId = custom.id,
                exerciseName = custom.name,
                routineSessionId = sharedRoutineSessionId,
                routineName = "Custom Day",
                timestamp = 1_700_000_000_000L,
                mode = "OLD_SCHOOL",
                reps = 8,
                weightPerCableKg = 40f,
                duration = 90_000L,
                totalReps = 8,
                workingReps = 8,
                profileId = "user-beta",
            ),
        )
        workoutRepository.saveSession(
            WorkoutSession(
                id = "routine-catalog-row",
                exerciseId = "exercise-catalog-row",
                exerciseName = "Row",
                routineSessionId = sharedRoutineSessionId,
                routineName = "Custom Day",
                timestamp = 1_700_000_100_000L,
                mode = "OLD_SCHOOL",
                reps = 10,
                weightPerCableKg = 30f,
                duration = 80_000L,
                totalReps = 10,
                workingReps = 10,
                profileId = "user-beta",
            ),
        )

        val result = backupManager.exportRoutine(sharedRoutineSessionId)
        assertTrue(result.isSuccess, "exportRoutine should succeed: ${result.exceptionOrNull()?.message}")
        val filePath = result.getOrThrow()
        val fileContent = File(filePath).readText()
        val backupData = testJson.decodeFromString<BackupData>(fileContent)

        assertEquals(listOf("user-beta"), backupData.data.userProfiles.map { it.id })
        assertEquals(listOf(custom.id), backupData.data.customExercises.map { it.id })
        assertEquals(
            setOf("routine-custom-bench", "routine-catalog-row"),
            backupData.data.workoutSessions.map { it.id }.toSet(),
        )

        val targetDatabase = createTestDatabase()
        targetDatabase.seedExercise("exercise-catalog-row", "Row", isCustom = false)
        val targetManager = TestDataBackupManager(targetDatabase)
        val importResult = targetManager.importFromJson(fileContent).getOrThrow()
        assertEquals(0, importResult.entitiesWithErrors)
        assertEquals(2, importResult.sessionsImported)
        assertEquals(1, importResult.customExercisesImported)
        assertNotNull(targetDatabase.phoenixDatabaseQueries.getProfileById("user-beta").executeAsOneOrNull())
        val restoredCustom = targetDatabase.phoenixDatabaseQueries.selectSessionById("routine-custom-bench").executeAsOne()
        val restoredCatalog = targetDatabase.phoenixDatabaseQueries.selectSessionById("routine-catalog-row").executeAsOne()
        assertEquals("user-beta", restoredCustom.profile_id)
        assertEquals(custom.id, restoredCustom.exerciseId)
        assertEquals("user-beta", restoredCatalog.profile_id)
        assertEquals("exercise-catalog-row", restoredCatalog.exerciseId)

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
        val queries = database.phoenixDatabaseQueries

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
            deletedAt = null,
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
        val queries = database.phoenixDatabaseQueries

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
            deletedAt = null,
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

    @Test
    fun `staged restore validates complete source before writing any rows`() = runTest {
        val payload = testJson.encodeToString(
            BackupData(
                exportedAt = "2026-09-20T00:00:00Z",
                appVersion = "test",
                data = BackupContent(
                    workoutSessions = listOf(
                        WorkoutSessionBackup(
                            id = "must-not-land",
                            timestamp = 1L,
                            mode = "OldSchool",
                            targetReps = 1,
                            weightPerCableKg = 1f,
                            progressionKg = 0f,
                            duration = 1L,
                            totalReps = 1,
                            warmupReps = 0,
                            workingReps = 1,
                            isJustLift = false,
                            stopAtTop = false,
                        ),
                    ),
                ),
            ),
        ) + " trailing"

        val result = backupManager.importFromStringStreaming(payload)

        assertTrue(result.isFailure)
        assertNull(database.phoenixDatabaseQueries.selectSessionById("must-not-land").executeAsOneOrNull())
    }

    @Test
    fun `duplicate known section is rejected before database replay`() = runTest {
        val payload = """{
            "version":6,"exportedAt":"2026-09-20T00:00:00Z","appVersion":"test",
            "data":{"routines":[],"routines":[]}
        }""".trimIndent()

        val result = backupManager.importFromStringStreaming(payload)

        assertTrue(result.isFailure)
        assertTrue(database.phoenixDatabaseQueries.selectAllRoutineIds().executeAsList().isEmpty())
    }

    @Test
    fun `unknown length source above fifty mebibytes is consumed once and restores known section`() = runTest {
        val padding = "x".repeat(64 * 1_024)
        val source = RepeatedJsonTokenSource(
            prefix = "{\"version\":6,\"exportedAt\":\"2026-09-20T00:00:00Z\",\"appVersion\":\"test\",\"data\":{\"futurePadding\":[",
            repeatedToken = padding,
            repetitions = 801,
            suffix = "],\"routines\":[{\"id\":\"large-source-routine\",\"name\":\"Large\",\"createdAt\":1,\"profileId\":\"default\"}]}}",
        )

        val result = backupManager.importFromSourceStreaming(source).getOrThrow()

        assertEquals(1, source.openCount)
        assertTrue(source.charactersRead > 50L * 1_024L * 1_024L)
        assertEquals(1, result.routinesImported)
        assertNotNull(database.phoenixDatabaseQueries.selectRoutineById("large-source-routine").executeAsOneOrNull())
    }

    @Test
    fun `cancellation interrupts blocking staging reads and leaves database untouched`() = runTest {
        lateinit var importJob: kotlinx.coroutines.Deferred<Result<ImportResult>>
        val source = RepeatedJsonTokenSource(
            prefix = "{\"version\":6,\"exportedAt\":\"2026-09-20T00:00:00Z\",\"appVersion\":\"test\",\"data\":{\"futurePadding\":[",
            repeatedToken = "x".repeat(64 * 1_024),
            repetitions = 2_000,
            suffix = "],\"routines\":[{\"id\":\"cancelled-routine\",\"name\":\"Cancelled\",\"createdAt\":1,\"profileId\":\"default\"}]}}",
            cancelAfterCharacters = 32_000L,
            onCancelThreshold = { importJob.cancel() },
        )
        importJob = async { backupManager.importFromSourceStreaming(source) }

        assertFailsWith<CancellationException> { importJob.await() }

        assertNull(database.phoenixDatabaseQueries.selectRoutineById("cancelled-routine").executeAsOneOrNull())
        assertTrue(source.closed)
    }

    @Test
    fun `staging write failure cleans temporary state before any database replay`() = runTest {
        val failingStaging = FailingBackupImportStagingArea("routines")
        val manager = TestDataBackupManager(database, stagingAreaFactory = { failingStaging })
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                routines = listOf(RoutineBackup("stage-failure-routine", "Failure", createdAt = 1L, profileId = "default")),
            ),
        )

        val result = manager.importFromJson(testJson.encodeToString(payload))

        assertTrue(result.isFailure)
        assertTrue(failingStaging.cleanedUp)
        assertNull(database.phoenixDatabaseQueries.selectRoutineById("stage-failure-routine").executeAsOneOrNull())
    }

    @Test
    fun `staging cleanup preserves recent sibling and removes only stale prior import`() {
        val parent = File(System.getProperty("java.io.tmpdir"), "phoenix-backup-import")
        parent.mkdirs()
        val marker = System.nanoTime()
        val recent = File(parent, "stage-${System.currentTimeMillis()}-recent-$marker").apply { mkdirs() }
        val stale = File(parent, "stage-${System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1_000L}-stale-$marker")
            .apply { mkdirs() }

        val staging = createBackupImportStagingArea()
        try {
            assertTrue(recent.isDirectory, "active/recent sibling staging must not be deleted")
            assertFalse(stale.exists(), "abandoned staging older than the retention window must be deleted")
        } finally {
            staging.cleanup()
            recent.deleteRecursively()
            stale.deleteRecursively()
        }
    }

    @Test
    fun `staged restore reorders parents and restores custom exercise routine graph`() = runTest {
        val backup = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                customExercises = listOf(customExerciseBackup("custom-order")),
                routines = listOf(RoutineBackup("routine-order", "Order", createdAt = 1L)),
                supersets = listOf(SupersetBackup("superset-order", "routine-order", "Pair")),
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "routine-exercise-order",
                        routineId = "routine-order",
                        exerciseName = "Custom order",
                        exerciseMuscleGroup = "Back",
                        exerciseDefaultCableConfig = "DOUBLE",
                        exerciseId = "custom-order",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "8",
                        weightPerCableKg = 10f,
                        supersetId = "superset-order",
                    ),
                ),
            ),
        )
        val root = testJson.encodeToJsonElement(backup).jsonObject
        val data = root.getValue("data").jsonObject
        val permuted = buildJsonObject {
            put("version", root.getValue("version"))
            put("exportedAt", root.getValue("exportedAt"))
            put("appVersion", root.getValue("appVersion"))
            put("data", buildJsonObject {
                put("routineExercises", data.getValue("routineExercises"))
                put("supersets", data.getValue("supersets"))
                put("routines", data.getValue("routines"))
                put("customExercises", data.getValue("customExercises"))
            })
        }.toString()

        val result = backupManager.importFromStringStreaming(permuted).getOrThrow()

        assertEquals(1, result.customExercisesImported)
        assertEquals(1, result.routinesImported)
        assertEquals(1, result.supersetsImported)
        assertEquals(1, result.routineExercisesImported)
        val restored = database.phoenixDatabaseQueries.selectAllRoutineExercisesSync().executeAsList().single()
        assertEquals("custom-order", restored.exerciseId)
        assertEquals("superset-order", restored.supersetId)
    }

    @Test
    fun `retry restores missing children below an existing matching parent`() = runTest {
        val parent = RoutineBackup("retry-routine", "Retry", createdAt = 1L, profileId = "default")
        backupManager.importFromJson(
            testJson.encodeToString(
                BackupData(exportedAt = "2026-09-20T00:00:00Z", appVersion = "test", data = BackupContent(routines = listOf(parent))),
            ),
        ).getOrThrow()

        val retry = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                routines = listOf(parent),
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "retry-child",
                        routineId = parent.id,
                        exerciseName = "Copied fields survive",
                        exerciseMuscleGroup = "Core",
                        exerciseEquipment = "ROPE",
                        exerciseDefaultCableConfig = "DOUBLE",
                        exerciseId = "missing-optional-exercise",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "10",
                        weightPerCableKg = 5f,
                        supersetId = "missing-optional-superset",
                    ),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(retry)).getOrThrow()

        assertEquals(1, result.routineExercisesImported)
        assertEquals(2, result.repairedReferences)
        val child = database.phoenixDatabaseQueries.selectAllRoutineExercisesSync().executeAsList().single()
        assertNull(child.exerciseId)
        assertNull(child.supersetId)
        assertEquals("Copied fields survive", child.exerciseName)
        assertEquals("ROPE", child.exerciseEquipment)
    }

    @Test
    fun `retry restores stable metric below existing matching session without duplication`() = runTest {
        val session = WorkoutSessionBackup(
            id = "metric-retry-session",
            timestamp = 1L,
            mode = "OldSchool",
            targetReps = 1,
            weightPerCableKg = 1f,
            progressionKg = 0f,
            duration = 1L,
            totalReps = 1,
            warmupReps = 0,
            workingReps = 1,
            isJustLift = false,
            stopAtTop = false,
            profileId = "default",
        )
        val first = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(workoutSessions = listOf(session)),
        )
        backupManager.importFromJson(testJson.encodeToString(first)).getOrThrow()
        val retry = first.copy(
            data = first.data.copy(
                metricSamples = listOf(
                    MetricSampleBackup(
                        id = 99L,
                        sessionId = session.id,
                        timestamp = 2L,
                        position = 1f,
                        velocity = 2f,
                        load = 3f,
                        power = 4f,
                    ),
                ),
            ),
        )

        val inserted = backupManager.importFromJson(testJson.encodeToString(retry)).getOrThrow()
        val duplicate = backupManager.importFromJson(testJson.encodeToString(retry)).getOrThrow()

        assertEquals(1, inserted.metricsImported)
        assertEquals(1, duplicate.metricsSkipped)
        assertEquals(1, database.phoenixDatabaseQueries.selectMetricsBySession(session.id).executeAsList().size)
    }

    @Test
    fun `restoring missing workout children reopens an acknowledged parent exactly once`() = runTest {
        val queries = database.phoenixDatabaseQueries
        val session = WorkoutSessionBackup(
            id = "restore-dirty-session",
            timestamp = 1L,
            mode = "OldSchool",
            targetReps = 1,
            weightPerCableKg = 1f,
            progressionKg = 0f,
            duration = 1L,
            totalReps = 1,
            warmupReps = 0,
            workingReps = 1,
            isJustLift = false,
            stopAtTop = false,
            profileId = "default",
        )
        val parentOnly = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(workoutSessions = listOf(session)),
        )
        backupManager.importFromJson(testJson.encodeToString(parentOnly)).getOrThrow()
        val initial = queries.selectSessionById(session.id).executeAsOne()
        queries.ackWorkoutComponentSnapshot(initial.local_sync_generation, session.id)
        assertTrue(queries.selectDirtyWorkoutPortalParentIds("default").executeAsList().isEmpty())

        val withChildren = parentOnly.copy(
            data = parentOnly.data.copy(
                metricSamples = listOf(
                    MetricSampleBackup(
                        id = 701L,
                        sessionId = session.id,
                        timestamp = 2L,
                        position = 1f,
                        velocity = 2f,
                        load = 3f,
                        power = 4f,
                    ),
                ),
                completedSets = listOf(
                    CompletedSetBackup(
                        id = "restore-dirty-set",
                        sessionId = session.id,
                        setNumber = 1,
                        actualReps = 1,
                        actualWeightKg = 1f,
                        completedAt = 3L,
                    ),
                ),
                sessionNotes = listOf(
                    SessionNotesBackup(session.id, "restored note", 4L),
                ),
            ),
        )

        val inserted = backupManager.importFromJson(testJson.encodeToString(withChildren)).getOrThrow()
        assertEquals(1, inserted.metricsImported)
        assertEquals(1, inserted.completedSetsImported)
        assertEquals(1, inserted.sessionNotesImported)
        assertEquals(listOf(session.id), queries.selectDirtyWorkoutPortalParentIds("default").executeAsList())
        assertEquals(session.id, queries.selectLiveWorkoutComponentsForPortalParents("default", listOf(session.id))
            .executeAsOne().id)
        assertEquals(701L, queries.selectMetricsBySession(session.id).executeAsOne().id)
        assertEquals("restore-dirty-set", queries.selectCompletedSetsBySession(session.id).executeAsOne().id)
        assertEquals("restored note", queries.getSessionNotes(session.id).executeAsOne().notes)
        val dirtyGeneration = queries.selectSessionById(session.id).executeAsOne().local_sync_generation
        queries.ackWorkoutComponentSnapshot(initial.local_sync_generation, session.id)
        assertEquals(listOf(session.id), queries.selectDirtyWorkoutPortalParentIds("default").executeAsList())

        val duplicate = backupManager.importFromJson(testJson.encodeToString(withChildren)).getOrThrow()
        assertEquals(1, duplicate.metricsSkipped)
        assertEquals(1, duplicate.completedSetsSkipped)
        assertEquals(1, duplicate.sessionNotesSkipped)
        assertEquals(dirtyGeneration, queries.selectSessionById(session.id).executeAsOne().local_sync_generation)
    }

    @Test
    fun `conflicting parent does not poison an independent valid graph`() = runTest {
        val localConflict = RoutineBackup("mixed-conflict", "Local", createdAt = 1L, profileId = "default")
        backupManager.importFromJson(
            testJson.encodeToString(
                BackupData(
                    exportedAt = "2026-09-20T00:00:00Z",
                    appVersion = "test",
                    data = BackupContent(routines = listOf(localConflict)),
                ),
            ),
        ).getOrThrow()

        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                routines = listOf(
                    localConflict.copy(name = "Incoming conflict"),
                    RoutineBackup("mixed-good", "Good", createdAt = 2L, profileId = "default"),
                ),
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "mixed-bad-child",
                        routineId = "mixed-conflict",
                        exerciseName = "Bad child",
                        exerciseMuscleGroup = "Core",
                        exerciseDefaultCableConfig = "DOUBLE",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "5",
                        weightPerCableKg = 5f,
                    ),
                    RoutineExerciseBackup(
                        id = "mixed-good-child",
                        routineId = "mixed-good",
                        exerciseName = "Good child",
                        exerciseMuscleGroup = "Core",
                        exerciseDefaultCableConfig = "DOUBLE",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "5",
                        weightPerCableKg = 5f,
                    ),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertTrue(result.hasPartialFailure)
        assertEquals(1, result.routinesImported)
        assertEquals(1, result.routineExercisesImported)
        assertEquals(null, database.phoenixDatabaseQueries.selectRoutineExerciseById("mixed-bad-child").executeAsOneOrNull())
        assertEquals("mixed-good", database.phoenixDatabaseQueries.selectRoutineExerciseById("mixed-good-child").executeAsOne().routineId)
    }

    @Test
    fun `v6 round trip preserves custom exercise and nullable scoped baseline`() = runTest {
        insertCustomExercise(database, customExerciseBackup("custom-baseline"))
        database.phoenixDatabaseQueries.insertProfileExerciseBaselineIfAbsent(
            profileId = "default",
            exerciseId = "custom-baseline",
            oneRepMaxPerCableKg = null,
            updatedAt = 123L,
            revision = 7L,
        )
        val payload = backupManager.exportToJson()
        val target = createTestDatabase()
        val result = TestDataBackupManager(target).importFromJson(payload).getOrThrow()

        assertEquals(1, result.customExercisesImported)
        assertEquals(1, result.profileExerciseBaselinesImported)
        val baseline = target.phoenixDatabaseQueries
            .selectProfileExerciseBaseline("default", "custom-baseline").executeAsOne()
        assertNull(baseline.one_rep_max_per_cable_kg)
        assertEquals(123L, baseline.updated_at)
        assertEquals(7L, baseline.revision)
    }

    @Test
    fun `immutable deletion owner conflict preserves local row and reports partial failure`() = runTest {
        database.phoenixDatabaseQueries.insertWorkoutDeletionRestoreIfAbsent(
            mutationId = "deletion-conflict",
            ownerUserId = "owner-local",
            profileId = "default",
            scope = "WORKOUT",
            portalSessionId = "portal-1",
            componentSessionId = null,
            deletedAt = 10L,
            acknowledgedAt = null,
            source = "LOCAL",
        )
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                workoutDeletions = listOf(
                    WorkoutDeletionBackup(
                        mutationId = "deletion-conflict",
                        ownerUserId = "owner-backup",
                        profileId = "default",
                        scope = "WORKOUT",
                        portalSessionId = "portal-1",
                        deletedAt = 10L,
                        source = "LOCAL",
                    ),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertTrue(result.hasPartialFailure)
        assertEquals(1, result.entitiesFailed)
        assertEquals("owner-local", database.phoenixDatabaseQueries
            .selectWorkoutDeletionByMutationId("deletion-conflict").executeAsOne().owner_user_id)
    }

    @Test
    fun `duplicate entity in one section is imported once then classified already present`() = runTest {
        val routine = RoutineBackup("duplicate-routine", "Duplicate", createdAt = 1L, profileId = "default")
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(routines = listOf(routine, routine)),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertEquals(1, result.routinesImported)
        assertEquals(1, result.routinesSkipped)
        assertEquals(0, result.entitiesFailed)
        assertEquals(1, database.phoenixDatabaseQueries.selectAllRoutineIds().executeAsList().count { it == routine.id })
    }

    @Test
    fun `streak retry preserves stable id after unrelated autoincrement row`() = runTest {
        database.phoenixDatabaseQueries.insertStreakHistory(
            startDate = 1L,
            endDate = 2L,
            length = 2L,
            profileId = "default",
        )
        val streak = StreakHistoryBackup(
            id = 41L,
            startDate = 10L,
            endDate = 20L,
            length = 11,
            profileId = "default",
        )
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(streakHistory = listOf(streak)),
        )

        val first = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()
        val retry = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertEquals(1, first.streakHistoryImported)
        assertEquals(1, retry.streakHistorySkipped)
        assertEquals(41L, database.phoenixDatabaseQueries.selectAllStreakHistory("default")
            .executeAsList().single { it.startDate == 10L }.id)
    }

    @Test
    fun `fresh owned profile deletion suppresses stale live workout without rebinding owner`() = runTest {
        val profile = UserProfileBackup(
            id = "owned-profile",
            name = "Owned",
            createdAt = 1L,
            supabaseUserId = "account-owner",
        )
        val session = WorkoutSessionBackup(
            id = "deleted-component",
            timestamp = 2L,
            mode = "OldSchool",
            targetReps = 1,
            weightPerCableKg = 1f,
            progressionKg = 0f,
            duration = 1L,
            totalReps = 1,
            warmupReps = 0,
            workingReps = 1,
            isJustLift = false,
            stopAtTop = false,
            routineSessionId = "deleted-parent",
            profileId = profile.id,
        )
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                userProfiles = listOf(profile),
                workoutDeletions = listOf(
                    WorkoutDeletionBackup(
                        mutationId = "delete-owned",
                        ownerUserId = "account-owner",
                        profileId = profile.id,
                        scope = "WORKOUT",
                        portalSessionId = "deleted-parent",
                        deletedAt = 3L,
                        source = "REMOTE",
                    ),
                ),
                workoutSessions = listOf(session),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertEquals(1, result.sessionsSkipped)
        assertNull(database.phoenixDatabaseQueries.selectSessionById(session.id).executeAsOneOrNull())
        assertEquals("account-owner", database.phoenixDatabaseQueries.getProfileById(profile.id)
            .executeAsOne().supabase_user_id)
        assertEquals("account-owner", database.phoenixDatabaseQueries
            .selectWorkoutDeletionByMutationId("delete-owned").executeAsOne().owner_user_id)
    }

    @Test
    fun `retained ownership claim blocks restoring entity into a different target profile`() = runTest {
        val claimed = UserProfileBackup("claim-target", "Target", createdAt = 1L, supabaseUserId = "owner-1")
        val wrong = UserProfileBackup("wrong-target", "Wrong", createdAt = 2L, supabaseUserId = "owner-1")
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                userProfiles = listOf(claimed, wrong),
                localOwnershipClaims = listOf(
                    LocalOwnershipClaimBackup(
                        ownerUserId = "owner-1",
                        entityType = "ROUTINE",
                        entityId = "claimed-routine",
                        mutationId = "transfer-1",
                        sourceProfileId = null,
                        targetProfileId = claimed.id,
                        transferredAt = 5L,
                    ),
                ),
                routines = listOf(
                    RoutineBackup("claimed-routine", "Claimed", createdAt = 3L, profileId = wrong.id),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertEquals(1, result.localOwnershipClaimsImported)
        assertEquals(1, result.entitiesFailed)
        assertNull(database.phoenixDatabaseQueries.selectRoutineById("claimed-routine").executeAsOneOrNull())
        assertEquals(claimed.id, database.phoenixDatabaseQueries
            .selectLocalOwnershipClaim("owner-1", "ROUTINE", "claimed-routine")
            .executeAsOne().target_profile_id)
    }

    private fun customExerciseBackup(id: String) = CustomExerciseBackup(
        id = id,
        name = "Custom $id",
        muscleGroup = "Back",
        muscleGroups = "Back",
        equipment = "ROPE",
        created = 1L,
        updatedAt = 2L,
    )

    private fun insertCustomExercise(database: PhoenixDatabase, exercise: CustomExerciseBackup) {
        database.phoenixDatabaseQueries.insertExerciseIfAbsent(
            id = exercise.id,
            name = exercise.name,
            displayName = exercise.displayName,
            description = exercise.description,
            created = exercise.created,
            muscleGroup = exercise.muscleGroup,
            muscleGroups = exercise.muscleGroups,
            muscles = exercise.muscles,
            equipment = exercise.equipment,
            movement = exercise.movement,
            sidedness = exercise.sidedness,
            grip = exercise.grip,
            gripWidth = exercise.gripWidth,
            minRepRange = exercise.minRepRange?.toDouble(),
            popularity = exercise.popularity.toDouble(),
            archived = if (exercise.archived) 1L else 0L,
            isFavorite = if (exercise.isFavorite) 1L else 0L,
            isCustom = 1L,
            timesPerformed = exercise.timesPerformed.toLong(),
            lastPerformed = exercise.lastPerformed,
            aliases = exercise.aliases,
            defaultCableConfig = exercise.defaultCableConfig,
            one_rep_max_kg = exercise.legacyOneRepMaxKg?.toDouble(),
            mvtOverrideMs = exercise.mvtOverrideMs?.toDouble(),
            isBodyweight = exercise.isBodyweight?.let { if (it) 1L else 0L },
        )
        exercise.updatedAt?.let {
            database.phoenixDatabaseQueries.updateCustomExerciseFromSync(
                name = exercise.name,
                displayName = exercise.displayName,
                muscleGroup = exercise.muscleGroup,
                muscleGroups = exercise.muscleGroups,
                equipment = exercise.equipment,
                defaultCableConfig = exercise.defaultCableConfig,
                updatedAt = it,
                serverId = exercise.serverId,
                deletedAt = exercise.deletedAt,
                id = exercise.id,
            )
        }
    }

    private fun buildRoutine(routineId: String, routineName: String, exerciseId: String, exerciseName: String): Routine {
        database.seedExercise(exerciseId, exerciseName)
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
        val queries = database.phoenixDatabaseQueries
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
    fun `restore preserves newer session notes and timestamped clears`() = runTest {
        val queries = database.phoenixDatabaseQueries
        queries.upsertSessionNotes("newer-note", "local edit", 20L)
        queries.upsertSessionNotes("intentional-clear", null, 30L)
        val payload = BackupData(
            exportedAt = "2026-09-20T00:00:00Z",
            appVersion = "test",
            data = BackupContent(
                sessionNotes = listOf(
                    SessionNotesBackup("newer-note", "older backup", 10L),
                    SessionNotesBackup("intentional-clear", "resurrected text", 10L),
                ),
            ),
        )

        val result = backupManager.importFromJson(testJson.encodeToString(payload)).getOrThrow()

        assertEquals(2, result.entitiesFailed)
        assertEquals("local edit", queries.getSessionNotes("newer-note").executeAsOne().notes)
        val clear = queries.getSessionNotes("intentional-clear").executeAsOne()
        assertNull(clear.notes)
        assertEquals(30L, clear.updatedAt)
    }

    @Test
    fun `v2 export preserves EarnedBadge sync fields so restore does not re-push`() = runTest {
        val queries = database.phoenixDatabaseQueries
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
        val queries = database.phoenixDatabaseQueries
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
            updatedAt = 1_700_000_000_000L,
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
            assertEquals(CURRENT_BACKUP_VERSION, root.getValue("version").jsonPrimitive.content.toInt())
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
            val profiles = fixture.database.phoenixDatabaseQueries.getAllProfiles().executeAsList()
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
            fixture.database.phoenixDatabaseQueries
                .selectSessionById("legacy-fallback-session")
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.phoenixDatabaseQueries
                .selectSessionById(explicitDefault.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.phoenixDatabaseQueries
                .selectSessionById(explicitRepresented.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            represented,
            fixture.database.phoenixDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
    }

    @Test
    fun `legacy fallback never adopts rows away from an existing real profile`() = runTest {
        val realOwner = UserProfileBackup("real-owner", "Real owner", createdAt = 1L)
        val routine = RoutineBackup("owned-routine", "Owned", createdAt = 2L, profileId = realOwner.id)
        val session = WorkoutSessionBackup(
            id = "owned-session",
            timestamp = 3L,
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
            profileId = realOwner.id,
        )
        val seed = BackupData(
            version = 5,
            exportedAt = "seed",
            appVersion = "test",
            data = BackupContent(
                userProfiles = listOf(realOwner),
                routines = listOf(routine),
                workoutSessions = listOf(session),
            ),
        )
        backupManager.importFromJson(testJson.encodeToString(seed)).getOrThrow()

        val conflicting = seed.copy(
            data = BackupContent(
                routines = listOf(routine.copy(profileId = "missing-owner")),
                workoutSessions = listOf(session.copy(profileId = "missing-owner")),
            ),
        )
        val result = backupManager.importFromJson(testJson.encodeToString(conflicting)).getOrThrow()

        assertEquals(2, result.entitiesFailed)
        assertEquals(realOwner.id, database.phoenixDatabaseQueries.selectRoutineById(routine.id).executeAsOne().profile_id)
        assertEquals(realOwner.id, database.phoenixDatabaseQueries.selectSessionById(session.id).executeAsOne().profile_id)
    }

    @Test
    fun `buffered session import adopts explicit owner when its profile is absent`() = runTest {
        val activeProfileId = database.phoenixDatabaseQueries.getActiveProfile().executeAsOne().id
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
            database.phoenixDatabaseQueries.selectSessionById(session.id).executeAsOne().profile_id,
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
        // Same catalog exercise on both devices (routine exercises reference it by FK).
        source.database.seedExercise("exercise-rack-backup", "Weighted Pull Up")
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
        // F-017/A-035 (PR 22): restore drops routine exercises whose Exercise row is missing on the target; remove this target-side seed once restore writes Exercise rows first.
        target.database.seedExercise("exercise-rack-backup", "Weighted Pull Up")

        val importResult = target.manager.importFromJson(backupJson)

        assertTrue(importResult.isSuccess, "v5 backup import must succeed: ${importResult.exceptionOrNull()?.message}")
        assertEquals("vest", target.preferences.get(PROFILE_A).rack.value.items.single().id)
        val importedExercise = target.database.phoenixDatabaseQueries
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
        val sourceExercise = database.phoenixDatabaseQueries
            .selectAllRoutineExercisesSync()
            .executeAsList()
            .single()
        assertEquals("ESTIMATED_1RM", sourceExercise.scalingBasis, "scalingBasis must persist on source DB")

        val backupJson = sourceManager.exportToJson()
        val targetDatabase = createTestDatabase()
        // F-017/A-035 (PR 22): restore drops routine exercises whose Exercise row is missing on the target; remove this target-side seed once restore writes Exercise rows first.
        targetDatabase.seedExercise("exercise-scaling-backup", "Bench Press")
        val targetManager = TestDataBackupManager(targetDatabase)

        val importResult = targetManager.importFromJson(backupJson)
        assertTrue(importResult.isSuccess, "Backup import must succeed: ${importResult.exceptionOrNull()?.message}")

        val importedExercise = targetDatabase.phoenixDatabaseQueries
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
        createTestSchema(driver)
        val fixtureDatabase = PhoenixDatabase(driver)
        val realPreferences = SqlDelightProfilePreferencesRepository(fixtureDatabase)
        val effectivePreferences = preferenceDecorator(realPreferences)
        val safetyStore = SettingsProfileLocalSafetyStore(MapSettings())
        val realUserProfiles = SqlDelightUserProfileRepository(
            database = fixtureDatabase,
            profilePreferencesRepository = effectivePreferences,
            profileLocalSafetyStore = safetyStore,
            gamificationRepository = SqlDelightGamificationRepository(fixtureDatabase),
        )
        fixtureDatabase.phoenixDatabaseQueries.seedMissingProfilePreferences()
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
        fixture.database.phoenixDatabaseQueries.setActiveProfile(PROFILE_A)
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
        if (fixture.database.phoenixDatabaseQueries.getProfileById(id).executeAsOneOrNull() == null) {
            fixture.database.phoenixDatabaseQueries.insertProfile(
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
        fixture.database.phoenixDatabaseQueries.insertDefaultProfilePreferences(id, 1L)
        if (active) fixture.database.phoenixDatabaseQueries.setActiveProfile(id)
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
        val database: PhoenixDatabase,
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
            database: PhoenixDatabase,
            preferences: ProfilePreferencesRepository,
        ): UserProfileRepository = SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = preferences,
            profileLocalSafetyStore = SettingsProfileLocalSafetyStore(MapSettings()),
            gamificationRepository = SqlDelightGamificationRepository(database),
        ).also {
            database.phoenixDatabaseQueries.seedMissingProfilePreferences()
        }
    }

    @Test
    fun `backup round trip keeps each cycle's portal sync base and drops malformed ones`() = runTest {
        val version = "2026-09-19T10:11:12.123456+00:00"
        val q = database.phoenixDatabaseQueries
        q.insertTrainingCycle("cycle-synced", "Synced", null, 1L, 0L, "default", null, 1L, 1L)
        q.updateTrainingCycleServerUpdatedAt(server_updated_at = version, id = "cycle-synced")
        q.insertTrainingCycle("cycle-local", "Local", null, 1L, 0L, "default", null, 1L, 1L)

        val legacy = backupManager.exportAllData()
        assertEquals(version, legacy.data.trainingCycles.first { it.id == "cycle-synced" }.serverUpdatedAt)
        assertNull(legacy.data.trainingCycles.first { it.id == "cycle-local" }.serverUpdatedAt)
        val streamingPath = backupManager.exportToCachePublic()
        val streamingJson = File(streamingPath).readText()
        File(streamingPath).delete()
        assertEquals(
            version,
            testJson.decodeFromString<BackupData>(streamingJson).data.trainingCycles.first { it.id == "cycle-synced" }.serverUpdatedAt,
        )

        val withMalformed = legacy.copy(
            data = legacy.data.copy(
                trainingCycles = legacy.data.trainingCycles.map {
                    if (it.id == "cycle-local") it.copy(serverUpdatedAt = "not-a-timestamp") else it
                },
            ),
        )
        fun storedBase(db: PhoenixDatabase, id: String) =
            db.phoenixDatabaseQueries.selectTrainingCycleById(id).executeAsOne().server_updated_at

        val legacyTarget = createTestDatabase()
        assertTrue(TestDataBackupManager(legacyTarget).importFromJson(testJson.encodeToString(withMalformed)).isSuccess)
        assertEquals(version, storedBase(legacyTarget, "cycle-synced"))
        assertNull(storedBase(legacyTarget, "cycle-local"))

        val streamingTarget = createTestDatabase()
        assertTrue(TestDataBackupManager(streamingTarget).importFromStringStreaming(streamingJson).isSuccess)
        assertEquals(version, storedBase(streamingTarget, "cycle-synced"))
        assertNull(storedBase(streamingTarget, "cycle-local"))
    }

    @Test
    fun `legacy cycle without server version matches existing cycle and restores dependent graph`() = runTest {
        val q = database.phoenixDatabaseQueries
        q.insertTrainingCycle("cycle-existing", "Existing", null, 1L, 0L, "default", null, 1L, 1L)
        q.updateTrainingCycleServerUpdatedAt(
            server_updated_at = "2026-09-19T10:11:12.123456+00:00",
            id = "cycle-existing",
        )
        q.insertCycleDay(
            id = "day-dependent",
            cycle_id = "cycle-existing",
            day_number = 1L,
            name = "Day 1",
            routine_id = null,
            is_rest_day = 0L,
            echo_level = null,
            eccentric_load_percent = null,
            weight_progression_percent = null,
            rep_modifier = null,
            rest_time_override_seconds = null,
        )
        val legacyCompatible = backupManager.exportAllData().let { backup ->
            backup.copy(
                data = backup.data.copy(
                    trainingCycles = backup.data.trainingCycles.map { it.copy(serverUpdatedAt = null) },
                ),
            )
        }

        val target = createTestDatabase()
        val targetQueries = target.phoenixDatabaseQueries
        targetQueries.insertTrainingCycle("cycle-existing", "Existing", null, 1L, 0L, "default", null, 1L, 1L)
        targetQueries.updateTrainingCycleServerUpdatedAt(
            server_updated_at = "2026-09-19T10:11:12.123456+00:00",
            id = "cycle-existing",
        )

        val result = TestDataBackupManager(target).importFromJson(testJson.encodeToString(legacyCompatible))

        assertTrue(result.isSuccess)
        assertNotNull(targetQueries.selectCycleDayById("day-dependent").executeAsOneOrNull())
        assertEquals(
            "2026-09-19T10:11:12.123456+00:00",
            targetQueries.selectTrainingCycleById("cycle-existing").executeAsOne().server_updated_at,
        )
    }

    @Test
    fun `explicit cycle server version mismatch still blocks dependent graph restore`() = runTest {
        val q = database.phoenixDatabaseQueries
        q.insertTrainingCycle("cycle-mismatch", "Existing", null, 1L, 0L, "default", null, 1L, 1L)
        q.updateTrainingCycleServerUpdatedAt(server_updated_at = "2026-09-19T10:00:00Z", id = "cycle-mismatch")
        q.insertCycleDay(
            id = "day-blocked",
            cycle_id = "cycle-mismatch",
            day_number = 1L,
            name = "Day 1",
            routine_id = null,
            is_rest_day = 0L,
            echo_level = null,
            eccentric_load_percent = null,
            weight_progression_percent = null,
            rep_modifier = null,
            rest_time_override_seconds = null,
        )
        val backup = backupManager.exportAllData()

        val target = createTestDatabase()
        val targetQueries = target.phoenixDatabaseQueries
        targetQueries.insertTrainingCycle("cycle-mismatch", "Existing", null, 1L, 0L, "default", null, 1L, 1L)
        targetQueries.updateTrainingCycleServerUpdatedAt(server_updated_at = "2026-09-19T11:00:00Z", id = "cycle-mismatch")

        val result = TestDataBackupManager(target).importFromJson(testJson.encodeToString(backup))

        assertTrue(result.isSuccess)
        assertNull(targetQueries.selectCycleDayById("day-blocked").executeAsOneOrNull())
        assertEquals(
            "2026-09-19T11:00:00Z",
            targetQueries.selectTrainingCycleById("cycle-mismatch").executeAsOne().server_updated_at,
        )
    }

    private class TestDataBackupManager(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        val profilePreferencesRepository: ProfilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
        val userProfileRepository: UserProfileRepository = createTestUserProfileRepository(
            database,
            profilePreferencesRepository,
        ),
        private val stagingAreaFactory: (() -> BackupImportStagingArea)? = null,
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

        suspend fun importFromStringStreaming(value: String): Result<ImportResult> {
            val source = StringBackupStreamSource(value)
            source.open()
            return try {
                importFromStream(source)
            } finally {
                source.close()
            }
        }

        suspend fun importFromSourceStreaming(source: BackupStreamSource): Result<ImportResult> {
            source.open()
            return try {
                importFromStream(source)
            } finally {
                source.close()
            }
        }

        override fun createImportStagingArea(): BackupImportStagingArea =
            stagingAreaFactory?.invoke() ?: super.createImportStagingArea()

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

    private class StringBackupStreamSource(private val value: String) : BackupStreamSource {
        private var index = 0

        override fun open() {
            index = 0
        }
        override fun close() = Unit
        override fun read(): Int = if (index < value.length) value[index++].code else -1
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (index >= value.length) return -1
            val count = minOf(length, value.length - index)
            value.toCharArray(index, index + count).copyInto(buffer, offset)
            index += count
            return count
        }
    }

    private class RepeatedJsonTokenSource(
        private val prefix: String,
        private val repeatedToken: String,
        private val repetitions: Int,
        private val suffix: String,
        private val cancelAfterCharacters: Long? = null,
        private val onCancelThreshold: () -> Unit = {},
    ) : BackupStreamSource {
        var openCount = 0
            private set
        var charactersRead = 0L
            private set
        var closed = false
            private set

        private var current = ""
        private var currentIndex = 0
        private var repetitionsEmitted = 0
        private var phase = 0
        private var cancellationSignalled = false

        override fun open() {
            openCount++
            closed = false
            current = prefix
            currentIndex = 0
            repetitionsEmitted = 0
            phase = 0
            cancellationSignalled = false
        }

        override fun close() {
            closed = true
        }

        override fun read(): Int {
            while (currentIndex >= current.length) {
                current = when {
                    phase == 0 && repetitionsEmitted < repetitions -> {
                        phase = 1
                        val separator = if (repetitionsEmitted == 0) "" else ","
                        repetitionsEmitted++
                        "$separator\"$repeatedToken\""
                    }
                    repetitionsEmitted < repetitions -> {
                        val separator = if (repetitionsEmitted == 0) "" else ","
                        repetitionsEmitted++
                        "$separator\"$repeatedToken\""
                    }
                    phase < 2 -> {
                        phase = 2
                        suffix
                    }
                    else -> return -1
                }
                currentIndex = 0
            }
            val value = current[currentIndex++].code
            charactersRead++
            if (!cancellationSignalled && cancelAfterCharacters != null && charactersRead >= cancelAfterCharacters) {
                cancellationSignalled = true
                onCancelThreshold()
            }
            return value
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            var count = 0
            while (count < length) {
                val value = read()
                if (value < 0) break
                buffer[offset + count] = value.toChar()
                count++
            }
            return if (count == 0) -1 else count
        }
    }

    private class FailingBackupImportStagingArea(
        private val failingSection: String,
    ) : BackupImportStagingArea {
        var cleanedUp = false
            private set

        override fun beginArray(section: String) = Unit

        override fun appendArrayValue(section: String, rawJson: String) {
            if (section == failingSection) throw IllegalStateException("Injected staging write failure")
        }

        override fun endArray(section: String) = Unit
        override fun writeValue(section: String, rawJson: String) = Unit
        override fun openSection(section: String): BackupStreamSource? = null
        override fun cleanup() {
            cleanedUp = true
        }
    }
}
