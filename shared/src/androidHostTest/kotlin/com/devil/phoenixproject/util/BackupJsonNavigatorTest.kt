package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// =============================================================================
// Part 1: BackupJsonNavigator unit tests
// =============================================================================

class BackupJsonNavigatorTest {

    private val testJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * In-memory [BackupStreamSource] that feeds a raw JSON string to the navigator.
     */
    private class StringBackupStreamSource(private val json: String) : BackupStreamSource {
        private var reader: java.io.StringReader? = null
        override fun open() {
            reader = java.io.StringReader(json)
        }

        override fun close() {
            reader?.close(); reader = null
        }

        override fun read(): Int = reader?.read() ?: -1
        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            reader?.read(buffer, offset, length) ?: -1
    }

    private fun navigatorFor(json: String): BackupJsonNavigator {
        val source = StringBackupStreamSource(json)
        source.open()
        return BackupJsonNavigator(source)
    }

    // --- Test 1 ---------------------------------------------------------------

    @Test
    fun `parse simple object with scalars`() {
        val nav = navigatorFor("""{"version":2,"name":"test","active":true}""")

        nav.beginObject()

        assertTrue(nav.hasNextInObject())
        assertEquals("version", nav.nextName())
        assertEquals(2, nav.nextInt())

        assertTrue(nav.hasNextInObject())
        assertEquals("name", nav.nextName())
        assertEquals("test", nav.nextString())

        assertTrue(nav.hasNextInObject())
        assertEquals("active", nav.nextName())
        assertEquals(true, nav.nextBoolean())

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 2 ---------------------------------------------------------------

    @Test
    fun `parse nested object`() {
        val nav = navigatorFor("""{"data":{"count":5}}""")

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("data", nav.nextName())

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("count", nav.nextName())
        assertEquals(5, nav.nextInt())
        assertFalse(nav.hasNextInObject())
        nav.endObject()

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 3 ---------------------------------------------------------------

    @Test
    fun `parse array of objects`() {
        val nav = navigatorFor("""[{"id":"a"},{"id":"b"}]""")

        nav.beginArray()

        assertTrue(nav.hasNextInArray())
        val first = nav.nextValueAsString()
        assertEquals("""{"id":"a"}""", first)

        assertTrue(nav.hasNextInArray())
        val second = nav.nextValueAsString()
        assertEquals("""{"id":"b"}""", second)

        assertFalse(nav.hasNextInArray())
        nav.endArray()
    }

    // --- Test 4 ---------------------------------------------------------------

    @Test
    fun `nextValueAsString preserves raw JSON`() {
        val nav = navigatorFor("""{"key":{"nested":[1,2,3],"str":"hello"}}""")

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("key", nav.nextName())

        val rawValue = nav.nextValueAsString()
        assertEquals("""{"nested":[1,2,3],"str":"hello"}""", rawValue)

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 5 ---------------------------------------------------------------

    @Test
    fun `skipValue skips nested structures`() {
        val nav = navigatorFor("""{"skip":{"deep":[1,2,[3,4]]},"keep":"yes"}""")

        nav.beginObject()

        assertTrue(nav.hasNextInObject())
        assertEquals("skip", nav.nextName())
        nav.skipValue()

        assertTrue(nav.hasNextInObject())
        assertEquals("keep", nav.nextName())
        assertEquals("yes", nav.nextString())

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 6 ---------------------------------------------------------------

    @Test
    fun `handles escaped strings in objects`() {
        val nav = navigatorFor("""{"msg":"he said \"hi\""}""")

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("msg", nav.nextName())
        assertEquals("he said \"hi\"", nav.nextString())
        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 7 ---------------------------------------------------------------

    @Test
    fun `handles strings with braces via nextValueAsString`() {
        val nav = navigatorFor("""{"key":"val{ue}"}""")

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("key", nav.nextName())

        val raw = nav.nextValueAsString()
        // Raw JSON should preserve the quoted string exactly
        assertEquals(""""val{ue}"""", raw)

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 8 ---------------------------------------------------------------

    @Test
    fun `handles null values`() {
        val nav = navigatorFor("""{"a":null,"b":42}""")

        nav.beginObject()

        assertTrue(nav.hasNextInObject())
        assertEquals("a", nav.nextName())
        assertTrue(nav.peekIsNull())
        nav.skipNull()

        assertTrue(nav.hasNextInObject())
        assertEquals("b", nav.nextName())
        assertEquals(42, nav.nextInt())

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 9 ---------------------------------------------------------------

    @Test
    fun `handles empty arrays`() {
        val nav = navigatorFor("""{"items":[]}""")

        nav.beginObject()
        assertTrue(nav.hasNextInObject())
        assertEquals("items", nav.nextName())

        nav.beginArray()
        assertFalse(nav.hasNextInArray())
        nav.endArray()

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 10 --------------------------------------------------------------

    @Test
    fun `skipValue handles all types`() {
        val nav = navigatorFor("""{"a":"str","b":42,"c":true,"d":false,"e":null,"f":[1],"g":{"x":1}}""")

        nav.beginObject()

        // "a":"str"
        assertTrue(nav.hasNextInObject())
        assertEquals("a", nav.nextName())
        nav.skipValue()

        // "b":42
        assertTrue(nav.hasNextInObject())
        assertEquals("b", nav.nextName())
        nav.skipValue()

        // "c":true
        assertTrue(nav.hasNextInObject())
        assertEquals("c", nav.nextName())
        nav.skipValue()

        // "d":false
        assertTrue(nav.hasNextInObject())
        assertEquals("d", nav.nextName())
        nav.skipValue()

        // "e":null
        assertTrue(nav.hasNextInObject())
        assertEquals("e", nav.nextName())
        nav.skipValue()

        // "f":[1]
        assertTrue(nav.hasNextInObject())
        assertEquals("f", nav.nextName())
        nav.skipValue()

        // "g":{"x":1}
        assertTrue(nav.hasNextInObject())
        assertEquals("g", nav.nextName())
        nav.skipValue()

        assertFalse(nav.hasNextInObject())
        nav.endObject()
    }

    // --- Test 11 --------------------------------------------------------------

    @Test
    fun `backup structure navigation`() {
        val jsonStr = """
            {
                "version": 2,
                "exportedAt": "2025-01-01",
                "appVersion": "0.7.0",
                "data": {
                    "workoutSessions": [
                        {
                            "id": "s1",
                            "timestamp": 1000,
                            "mode": "Old School",
                            "targetReps": 10,
                            "weightPerCableKg": 20.0,
                            "progressionKg": 0.0,
                            "duration": 60000,
                            "totalReps": 10,
                            "warmupReps": 0,
                            "workingReps": 10,
                            "isJustLift": false,
                            "stopAtTop": false
                        }
                    ],
                    "metricSamples": [
                        {
                            "sessionId": "s1",
                            "timestamp": 1001,
                            "position": 0.5,
                            "velocity": 1.0,
                            "load": 20.0,
                            "power": 100.0
                        }
                    ],
                    "routines": []
                }
            }
        """.trimIndent()

        val nav = navigatorFor(jsonStr)
        nav.beginObject()

        var version = 0
        val sessionJsonList = mutableListOf<String>()
        val metricJsonList = mutableListOf<String>()

        while (nav.hasNextInObject()) {
            when (nav.nextName()) {
                "version" -> version = nav.nextInt()
                "exportedAt" -> nav.skipValue()
                "appVersion" -> nav.skipValue()
                "data" -> {
                    nav.beginObject()
                    while (nav.hasNextInObject()) {
                        when (nav.nextName()) {
                            "workoutSessions" -> {
                                nav.beginArray()
                                while (nav.hasNextInArray()) {
                                    sessionJsonList.add(nav.nextValueAsString())
                                }
                                nav.endArray()
                            }

                            "metricSamples" -> {
                                nav.beginArray()
                                while (nav.hasNextInArray()) {
                                    metricJsonList.add(nav.nextValueAsString())
                                }
                                nav.endArray()
                            }

                            else -> nav.skipValue()
                        }
                    }
                    nav.endObject()
                }

                else -> nav.skipValue()
            }
        }
        nav.endObject()

        assertEquals(2, version)
        assertEquals(1, sessionJsonList.size)
        assertEquals(1, metricJsonList.size)

        // Verify the extracted session JSON is deserializable
        val session = testJson.decodeFromString<WorkoutSessionBackup>(sessionJsonList[0])
        assertEquals("s1", session.id)
        assertEquals(1000L, session.timestamp)
        assertEquals("Old School", session.mode)
        assertEquals(10, session.targetReps)
        assertEquals(20.0f, session.weightPerCableKg)

        // Verify the extracted metric JSON is deserializable
        val metric = testJson.decodeFromString<MetricSampleBackup>(metricJsonList[0])
        assertEquals("s1", metric.sessionId)
        assertEquals(1001L, metric.timestamp)
    }
}

// =============================================================================
// Part 2: Streaming import round-trip integration test
// =============================================================================

class StreamingImportRoundTripTest {

    private val testJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private class StringBackupStreamSource(private val json: String) : BackupStreamSource {
        private var reader: java.io.StringReader? = null
        override fun open() {
            reader = java.io.StringReader(json)
        }

        override fun close() {
            reader?.close(); reader = null
        }

        override fun read(): Int = reader?.read() ?: -1
        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            reader?.read(buffer, offset, length) ?: -1
    }

    private class TestDataBackupManager(
        database: com.devil.phoenixproject.database.VitruvianDatabase,
    ) : BaseDataBackupManager(database) {

        override fun createBackupWriter(): BackupJsonWriter {
            val tempFile = File.createTempFile("backup-roundtrip-", ".json")
            return BackupJsonWriter(tempFile.absolutePath)
        }

        override suspend fun finalizeExport(tempFilePath: String): Result<String> =
            Result.success(tempFilePath)

        override suspend fun saveToFile(backup: BackupData): Result<String> {
            error("Not needed for tests")
        }

        override suspend fun importFromFile(filePath: String): Result<ImportResult> {
            error("Not needed for tests")
        }

        override suspend fun shareBackup() = Unit

        override fun getSessionBackupDirectory(): String {
            val dir = File(System.getProperty("java.io.tmpdir"), "PhoenixBackupsRoundTrip")
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

        /** Public wrapper exposing the protected [importFromStream] for testing. */
        fun importFromStreamPublic(source: BackupStreamSource): Result<ImportResult> =
            importFromStream(source)
    }

    @Test
    fun `streaming import round-trip preserves all entities`() = runTest {
        // 1. Create original database and populate
        val originalDb = createTestDatabase()
        val originalRepo = SqlDelightWorkoutRepository(originalDb, FakeExerciseRepository())
        val originalManager = TestDataBackupManager(originalDb)

        // Insert sessions
        originalRepo.saveSession(
            WorkoutSession(
                id = "rt-session-1",
                exerciseId = "rt-exercise-bench",
                exerciseName = "Bench Press",
                timestamp = 1_700_000_000_000L,
                mode = "Old School",
                reps = 10,
                weightPerCableKg = 50f,
                duration = 120_000L,
                totalReps = 10,
                workingReps = 10,
            ),
        )
        originalRepo.saveSession(
            WorkoutSession(
                id = "rt-session-2",
                exerciseId = "rt-exercise-squat",
                exerciseName = "Squat",
                timestamp = 1_700_000_100_000L,
                mode = "Echo",
                reps = 8,
                weightPerCableKg = 80f,
                duration = 90_000L,
                totalReps = 8,
                workingReps = 8,
            ),
        )

        // Insert metrics for first session
        originalDb.vitruvianDatabaseQueries.insertMetric(
            sessionId = "rt-session-1",
            timestamp = 1_700_000_000_100L,
            position = 0.5,
            positionB = null,
            velocity = 1.0,
            velocityB = null,
            load = 50.0,
            loadB = null,
            power = 200.0,
            status = 0,
        )
        originalDb.vitruvianDatabaseQueries.insertMetric(
            sessionId = "rt-session-1",
            timestamp = 1_700_000_000_200L,
            position = 0.8,
            positionB = null,
            velocity = 0.7,
            velocityB = null,
            load = 50.0,
            loadB = null,
            power = 180.0,
            status = 0,
        )

        // Insert a routine with exercises
        val exercise = Exercise(
            id = "rt-exercise-bench",
            name = "Bench Press",
            muscleGroup = "Chest",
        )
        val routineExercise = RoutineExercise(
            id = "rt-re-1",
            exercise = exercise,
            orderIndex = 0,
            weightPerCableKg = 50f,
        )
        originalRepo.saveRoutine(
            Routine(
                id = "rt-routine-1",
                name = "Upper Day",
                exercises = listOf(routineExercise),
            ),
        )

        // Insert a personal record
        originalDb.vitruvianDatabaseQueries.insertRecord(
            exerciseId = "rt-exercise-bench",
            exerciseName = "Bench Press",
            weight = 100.0,
            reps = 1,
            oneRepMax = 100.0,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = "MAX_WEIGHT",
            volume = 100.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = 2,
        )

        // 2. Export from original DB
        val exportedJson = originalManager.exportToJson()
        val originalBackup = testJson.decodeFromString<BackupData>(exportedJson)

        // Verify original has the expected entity counts
        assertEquals(2, originalBackup.data.workoutSessions.size, "Original should have 2 sessions")
        assertEquals(2, originalBackup.data.metricSamples.size, "Original should have 2 metrics")
        assertEquals(1, originalBackup.data.routines.size, "Original should have 1 routine")
        assertEquals(1, originalBackup.data.routineExercises.size, "Original should have 1 routine exercise")
        assertTrue(originalBackup.data.personalRecords.isNotEmpty(), "Original should have personal records")

        // 3. Create FRESH database and import via streaming
        val freshDb = createTestDatabase()
        val freshManager = TestDataBackupManager(freshDb)

        val source = StringBackupStreamSource(exportedJson)
        source.open()
        val importResult = freshManager.importFromStreamPublic(source)
        source.close()

        assertTrue(importResult.isSuccess, "Streaming import must succeed: ${importResult.exceptionOrNull()?.message}")

        val result = importResult.getOrThrow()
        assertEquals(0, result.entitiesWithErrors, "Round-trip import must not produce entity errors")

        // 4. Export from fresh DB
        val reExportedJson = freshManager.exportToJson()
        val reExportedBackup = testJson.decodeFromString<BackupData>(reExportedJson)

        // 5. Compare entity counts match
        assertEquals(
            originalBackup.data.workoutSessions.size,
            reExportedBackup.data.workoutSessions.size,
            "Session count must match after round-trip",
        )
        assertEquals(
            originalBackup.data.metricSamples.size,
            reExportedBackup.data.metricSamples.size,
            "Metric count must match after round-trip",
        )
        assertEquals(
            originalBackup.data.routines.size,
            reExportedBackup.data.routines.size,
            "Routine count must match after round-trip",
        )
        assertEquals(
            originalBackup.data.routineExercises.size,
            reExportedBackup.data.routineExercises.size,
            "Routine exercise count must match after round-trip",
        )
        assertEquals(
            originalBackup.data.personalRecords.size,
            reExportedBackup.data.personalRecords.size,
            "Personal record count must match after round-trip",
        )

        // 6. Verify import counts match
        assertEquals(2, result.sessionsImported, "Should import 2 sessions")
        assertEquals(2, result.metricsImported, "Should import 2 metrics")
        assertEquals(1, result.routinesImported, "Should import 1 routine")
        assertEquals(1, result.routineExercisesImported, "Should import 1 routine exercise")
        assertTrue(result.personalRecordsImported > 0, "Should import personal records")

        // 7. Spot-check key values survived the round-trip
        val reExportedSession = reExportedBackup.data.workoutSessions.first { it.id == "rt-session-1" }
        assertEquals("Bench Press", reExportedSession.exerciseName)
        assertEquals(50f, reExportedSession.weightPerCableKg)
        assertEquals("Old School", reExportedSession.mode)

        val reExportedRoutine = reExportedBackup.data.routines.first { it.id == "rt-routine-1" }
        assertEquals("Upper Day", reExportedRoutine.name)
    }
}
