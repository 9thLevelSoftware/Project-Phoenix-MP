package com.devil.phoenixproject.util

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

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
            reader?.close()
            reader = null
        }

        override fun read(): Int = reader?.read() ?: -1
        override fun read(buffer: CharArray, offset: Int, length: Int): Int = reader?.read(buffer, offset, length) ?: -1
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
            reader?.close()
            reader = null
        }

        override fun read(): Int = reader?.read() ?: -1
        override fun read(buffer: CharArray, offset: Int, length: Int): Int = reader?.read(buffer, offset, length) ?: -1
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

        override fun createBackupWriter(): BackupJsonWriter {
            val tempFile = File.createTempFile("backup-roundtrip-", ".json")
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
        suspend fun importFromStreamPublic(source: BackupStreamSource): Result<ImportResult> = importFromStream(source)

        suspend fun importFromStringStreaming(value: String): Result<ImportResult> {
            val source = StringBackupStreamSource(value)
            source.open()
            return try {
                importFromStreamPublic(source)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun `streaming import skips privacy metadata before data`() = runTest {
        val freshDb = createTestDatabase()
        val freshManager = TestDataBackupManager(freshDb)
        val backupJson = """
            {
              "version": 3,
              "exportedAt": "2026-06-04T12:00:00Z",
              "appVersion": "test",
              "privacy": {
                "classification": "FULL_PERSONAL_DATA",
                "containsAuthTokens": false,
                "nested": {
                  "ignored": true,
                  "values": [1, 2, 3]
                }
              },
              "data": {
                "workoutSessions": []
              }
            }
        """.trimIndent()

        val source = StringBackupStreamSource(backupJson)
        source.open()
        val importResult = freshManager.importFromStreamPublic(source)
        source.close()

        assertTrue(importResult.isSuccess, "Streaming import must skip top-level privacy metadata")
        assertEquals(0, importResult.getOrThrow().sessionsImported)
    }

    @Test
    fun `streaming defers profile state until final root version regardless of field order`() = runTest {
        val v5Fixture = preferenceFixture()
        seedProfiles(v5Fixture)
        val v5Payload = adversarialBackup(
            finalVersion = 5,
            identities = listOf(profileBackup(PROFILE_A, isActive = false)),
            preferences = listOf(
                preferenceEntry(
                    PROFILE_A,
                    core = jsonElement(CoreProfilePreferences(83f, WeightUnit.KG, 2.5f)),
                    rack = jsonElement(RackPreferences(items = listOf(rackItem("v5", "V5", 8f)))),
                ),
            ),
            legacyRack = JsonArray(emptyList()),
        )

        val v5Result = v5Fixture.manager.importFromStringStreaming(v5Payload)

        assertTrue(v5Result.isSuccess, v5Result.exceptionOrNull()?.toString())
        assertEquals(83f, v5Fixture.preferences.get(PROFILE_A).core.value.bodyWeightKg)
        assertEquals(
            listOf("v5"),
            v5Fixture.preferences.get(PROFILE_A).rack.value.items.map { it.id },
            "final v5 preference rack must win while legacy empty rack is ignored",
        )

        val v4Fixture = preferenceFixture()
        seedProfiles(v4Fixture)
        val v4Payload = adversarialBackup(
            finalVersion = 4,
            identities = listOf(profileBackup(PROFILE_A)),
            preferences = listOf(
                preferenceEntry(
                    PROFILE_A,
                    core = jsonElement(CoreProfilePreferences(120f, WeightUnit.LB, 5f)),
                ),
            ),
            legacyRack = JsonArray(emptyList()),
        )

        val v4Result = v4Fixture.manager.importFromStringStreaming(v4Payload)

        assertTrue(v4Result.isSuccess, v4Result.exceptionOrNull()?.toString())
        assertEquals(70f, v4Fixture.preferences.get(PROFILE_A).core.value.bodyWeightKg)
        assertTrue(v4Fixture.preferences.get(PROFILE_A).rack.value.items.isEmpty())
    }

    @Test
    fun `streaming v4 distinguishes missing empty invalid and valid rack payloads`() = runTest {
        suspend fun restore(present: Boolean, raw: JsonElement): Pair<PreferenceFixture, ImportResult> {
            val fixture = preferenceFixture()
            seedProfiles(fixture)
            val data = buildJsonObject {
                put("userProfiles", testJson.encodeToJsonElement(listOf(profileBackup(PROFILE_A))))
                if (present) put("equipmentRackItems", raw)
            }
            val payload = buildJsonObject {
                put("data", data)
                put("version", 4)
                put("exportedAt", "2026-07-12T00:00:00Z")
                put("appVersion", "test")
            }.toString()
            return fixture to fixture.manager.importFromStringStreaming(payload).getOrThrow()
        }

        val missing = restore(false, JsonNull).first
        assertEquals(listOf("a-existing", "shared"), missing.preferences.get(PROFILE_A).rack.value.items.map { it.id })

        listOf(
            JsonNull,
            JsonPrimitive(7),
            JsonArray(listOf(buildJsonObject { put("id", "malformed") })),
        ).forEach { invalid ->
            val restored = restore(true, invalid)
            assertEquals(1, restored.second.entitiesWithErrors)
            assertEquals(
                listOf("a-existing", "shared"),
                restored.first.preferences.get(PROFILE_A).rack.value.items.map { it.id },
            )
        }

        assertTrue(restore(true, JsonArray(emptyList())).first.preferences.get(PROFILE_A).rack.value.items.isEmpty())

        val valid = restore(
            true,
            jsonElement(listOf(
                rackItem("shared", "Imported", 9f),
                rackItem("new", "New", 3f),
            )),
        ).first.preferences.get(PROFILE_A).rack.value.items
        assertEquals(listOf("a-existing", "shared", "new"), valid.map { it.id })
        assertEquals("Imported", valid[1].name)
    }

    @Test
    fun `buffered and streaming preference restores have typed and metadata parity`() = runTest {
        val buffered = preferenceFixture()
        val streamed = preferenceFixture()
        seedProfiles(buffered)
        seedProfiles(streamed)
        val payload = standardBackup(
            version = 5,
            identities = listOf(profileBackup(PROFILE_A), profileBackup(PROFILE_B)),
            preferences = listOf(
                preferenceEntry(
                    PROFILE_A,
                    core = jsonElement(CoreProfilePreferences(88f, WeightUnit.KG, 1.25f)),
                    rack = jsonElement(RackPreferences(items = listOf(rackItem("new-a", "New A", 3f)))),
                    workout = jsonElement(WorkoutPreferences(stopAtTop = true, summaryCountdownSeconds = 20)),
                    led = jsonElement(LedPreferences(colorScheme = 9, discoModeUnlocked = true)),
                    vbt = jsonElement(VbtPreferences(enabled = false, velocityLossThresholdPercent = 50)),
                ),
                preferenceEntry(
                    PROFILE_B,
                    core = jsonElement(CoreProfilePreferences(99f, WeightUnit.LB, 5f)),
                    rack = jsonElement(RackPreferences(items = listOf(rackItem("new-b", "New B", 6f)))),
                    workout = jsonElement(WorkoutPreferences(beepsEnabled = false, summaryCountdownSeconds = 25)),
                    led = jsonElement(LedPreferences(colorScheme = 10)),
                    vbt = jsonElement(VbtPreferences(enabled = true, velocityLossThresholdPercent = 35)),
                ),
            ),
        )

        val bufferedResult = buffered.manager.importFromJson(payload)
        val streamedResult = streamed.manager.importFromStringStreaming(payload)

        assertTrue(bufferedResult.isSuccess, bufferedResult.exceptionOrNull()?.toString())
        assertTrue(streamedResult.isSuccess, streamedResult.exceptionOrNull()?.toString())
        assertEquals(bufferedResult.getOrThrow().entitiesWithErrors, streamedResult.getOrThrow().entitiesWithErrors)
        listOf(PROFILE_A, PROFILE_B).forEach { profileId ->
            val left = buffered.preferences.get(profileId)
            val right = streamed.preferences.get(profileId)
            assertEquals(left.core.value, right.core.value)
            assertEquals(left.rack.value, right.rack.value)
            assertEquals(left.workout.value, right.workout.value)
            assertEquals(left.led.value, right.led.value)
            assertEquals(left.vbt.value, right.vbt.value)
            assertEquals(left.core.metadata.copy(updatedAt = 0), right.core.metadata.copy(updatedAt = 0))
            assertEquals(left.rack.metadata.copy(updatedAt = 0), right.rack.metadata.copy(updatedAt = 0))
            assertEquals(left.workout.metadata.copy(updatedAt = 0), right.workout.metadata.copy(updatedAt = 0))
            assertEquals(left.led.metadata.copy(updatedAt = 0), right.led.metadata.copy(updatedAt = 0))
            assertEquals(left.vbt.metadata.copy(updatedAt = 0), right.vbt.metadata.copy(updatedAt = 0))
        }
    }

    @Test
    fun `streaming active flags never switch target and post-identity failure normalizes then reconciles`() = runTest {
        listOf(
            listOf(false, false),
            listOf(false, true),
            listOf(true, true),
        ).forEach { flags ->
            val fixture = preferenceFixture()
            seedProfiles(fixture)
            val payload = standardBackup(
                version = 5,
                identities = listOf(
                    profileBackup(PROFILE_A, flags[0]),
                    profileBackup(PROFILE_B, flags[1]),
                ),
            )
            assertTrue(fixture.manager.importFromStringStreaming(payload).isSuccess)
            val profiles = fixture.database.vitruvianDatabaseQueries.getAllProfiles().executeAsList()
            assertEquals(PROFILE_A, profiles.single { it.isActive == 1L }.id)
            assertEquals(1, fixture.recordingUserProfiles.reconcileCalls)
        }

        val failed = preferenceFixture()
        seedProfiles(failed)
        val malformedAfterIdentityCommit = """
            {
              "data": {
                "userProfiles": [
                  {"id":"profile-b","name":"Profile B","colorIndex":0,"createdAt":200,"isActive":true}
                ],
                "workoutSessions": [}
              },
              "version": 5,
              "exportedAt": "x",
              "appVersion": "x"
            }
        """.trimIndent()

        val result = failed.manager.importFromStringStreaming(malformedAfterIdentityCommit)

        assertTrue(result.isFailure)
        val profiles = failed.database.vitruvianDatabaseQueries.getAllProfiles().executeAsList()
        assertEquals(PROFILE_A, profiles.single { it.isActive == 1L }.id)
        assertEquals(1, failed.recordingUserProfiles.reconcileCalls)
    }

    @Test
    fun `streaming legacy rows use first represented fallback when no prior active or default exists`() = runTest {
        val fixture = preferenceFixture()
        fixture.driver.execute(null, "DELETE FROM UserProfile WHERE id = 'default'", 0)
        val represented = "first-represented"
        val session = WorkoutSessionBackup(
            id = "stream-legacy-fallback",
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
            profileId = null,
        )
        val payload = buildJsonObject {
            put("data", buildJsonObject {
                put("workoutSessions", JsonArray(listOf(testJson.encodeToJsonElement(session))))
                put("userProfiles", testJson.encodeToJsonElement(listOf(profileBackup(represented))))
            })
            put("version", 5)
            put("exportedAt", "x")
            put("appVersion", "x")
        }.toString()

        val result = fixture.manager.importFromStringStreaming(payload)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(
            represented,
            fixture.database.vitruvianDatabaseQueries
                .selectSessionById(session.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(represented, fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
    }

    @Test
    fun `streaming explicit profile adoption waits for normalized target instead of provisional default`() = runTest {
        val fixture = preferenceFixture()
        val session = WorkoutSessionBackup(
            id = "stream-explicit-adoption",
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
            profileId = "original-owner",
        )
        val representedSession = session.copy(id = "stream-explicit-represented")
        fun payload(rows: List<WorkoutSessionBackup>, identities: List<UserProfileBackup>) = buildJsonObject {
            put("data", buildJsonObject {
                put("workoutSessions", testJson.encodeToJsonElement(rows))
                put("userProfiles", testJson.encodeToJsonElement(identities))
            })
            put("version", 5)
            put("exportedAt", "x")
            put("appVersion", "x")
        }.toString()

        assertTrue(
            fixture.manager.importFromStringStreaming(
                payload(listOf(session, representedSession), emptyList()),
            ).isSuccess,
        )
        fixture.driver.execute(null, "DELETE FROM UserProfile WHERE id = 'default'", 0)

        val result = fixture.manager.importFromStringStreaming(
            payload(
                listOf(
                    session.copy(profileId = "default"),
                    representedSession.copy(profileId = "first-represented"),
                ),
                listOf(profileBackup("first-represented")),
            ),
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(
            "original-owner",
            fixture.database.vitruvianDatabaseQueries.selectSessionById(session.id).executeAsOne().profile_id,
        )
        assertEquals(
            "first-represented",
            fixture.database.vitruvianDatabaseQueries
                .selectSessionById(representedSession.id)
                .executeAsOne()
                .profile_id,
        )
        assertEquals(
            "first-represented",
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
    }

    @Test
    fun `streaming v1 through v3 ignore supplied preferences and legacy rack and preserve local safety`() = runTest {
        for (version in 1..3) {
            val fixture = preferenceFixture()
            seedProfiles(fixture)
            val localSafety = ProfileLocalSafetyPreferences(
                safeWord = "streaming-local-secret-$version",
                safeWordCalibrated = true,
                adultsOnlyConfirmed = true,
                adultsOnlyPrompted = true,
            )
            fixture.safetyStore.write(PROFILE_A, localSafety)
            val before = fixture.preferences.get(PROFILE_A)
            val payload = buildJsonObject {
                put("version", version)
                put("exportedAt", "x")
                put("appVersion", "x")
                put("data", buildJsonObject {
                    put("userProfiles", testJson.encodeToJsonElement(listOf(profileBackup(PROFILE_A))))
                    put("profilePreferences", JsonArray(listOf(
                        preferenceEntry(
                            PROFILE_A,
                            core = jsonElement(CoreProfilePreferences(120f, WeightUnit.LB, 10f)),
                            rack = jsonElement(RackPreferences()),
                        ),
                    )))
                    put("equipmentRackItems", JsonArray(emptyList()))
                })
            }.toString()

            val result = fixture.manager.importFromStringStreaming(payload)

            assertTrue(result.isSuccess, "v$version: ${result.exceptionOrNull()}")
            val after = fixture.preferences.get(PROFILE_A)
            assertEquals(before.core.value, after.core.value, "v$version core")
            assertEquals(before.rack.value, after.rack.value, "v$version rack")
            assertEquals(localSafety, fixture.safetyStore.read(PROFILE_A), "v$version local safety")
        }
    }

    @Test
    fun `streaming v6 restores known fields ignores unknown fields and preserves local safety`() = runTest {
        val fixture = preferenceFixture()
        seedProfiles(fixture)
        val localSafety = ProfileLocalSafetyPreferences("v6-stream-secret", true, true, true)
        fixture.safetyStore.write(PROFILE_A, localSafety)
        val entry = buildJsonObject {
            put("profileId", PROFILE_A)
            put("core", buildJsonObject {
                put("bodyWeightKg", 88f)
                put("weightUnit", "KG")
                put("weightIncrement", 2.5f)
                put("futureSectionField", JsonArray(listOf(JsonPrimitive(1))))
            })
            put("futureEntryField", buildJsonObject { put("ignored", true) })
        }
        val payload = buildJsonObject {
            put("futureRootField", buildJsonObject { put("ignored", true) })
            put("data", buildJsonObject {
                put("futureDataField", JsonPrimitive("ignored"))
                put("profilePreferences", JsonArray(listOf(entry)))
                put("equipmentRackItems", JsonArray(emptyList()))
                put("userProfiles", testJson.encodeToJsonElement(listOf(profileBackup(PROFILE_A))))
            })
            put("version", 6)
            put("exportedAt", "x")
            put("appVersion", "x")
        }.toString()

        val result = fixture.manager.importFromStringStreaming(payload)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
        assertEquals(0, result.getOrThrow().entitiesWithErrors)
        assertEquals(88f, fixture.preferences.get(PROFILE_A).core.value.bodyWeightKg)
        assertEquals(
            listOf("a-existing", "shared"),
            fixture.preferences.get(PROFILE_A).rack.value.items.map { it.id },
            "v6 must ignore the legacy rack",
        )
        assertEquals(localSafety, fixture.safetyStore.read(PROFILE_A))
    }

    @Test
    fun `streaming preference failure remains primary and reconcile failure is suppressed once`() = runTest {
        val restoreFailure = IllegalStateException("streaming preference storage failed")
        val reconcileFailure = IllegalArgumentException("streaming reconcile failed")
        val fixture = preferenceFixture(
            preferenceDecorator = { delegate ->
                FaultingProfilePreferencesRepository(delegate, restoreFailure)
            },
            reconciliationFailure = reconcileFailure,
        )
        val payload = standardBackup(
            version = 5,
            identities = listOf(profileBackup("default")),
            preferences = listOf(
                preferenceEntry(
                    "default",
                    core = jsonElement(CoreProfilePreferences(80f, WeightUnit.KG, 2.5f)),
                ),
            ),
        )

        val result = fixture.manager.importFromStringStreaming(payload)

        assertTrue(result.isFailure)
        assertSame(restoreFailure, result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()!!.suppressed.any { it.message == reconcileFailure.message })
        assertEquals(1, fixture.recordingUserProfiles.reconcileCalls)
    }

    private fun preferenceFixture(
        preferenceDecorator: (ProfilePreferencesRepository) -> ProfilePreferencesRepository = { it },
        reconciliationFailure: Throwable? = null,
    ): PreferenceFixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        val database = VitruvianDatabase(driver)
        val preferences = preferenceDecorator(SqlDelightProfilePreferencesRepository(database))
        val safetyStore = SettingsProfileLocalSafetyStore(MapSettings())
        val realUserProfiles = SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = preferences,
            profileLocalSafetyStore = safetyStore,
            gamificationRepository = SqlDelightGamificationRepository(database),
        )
        database.vitruvianDatabaseQueries.seedMissingProfilePreferences()
        val recording = RecordingUserProfileRepository(realUserProfiles, reconciliationFailure)
        return PreferenceFixture(
            driver = driver,
            database = database,
            preferences = preferences,
            safetyStore = safetyStore,
            recordingUserProfiles = recording,
            manager = TestDataBackupManager(database, preferences, recording),
        )
    }

    private suspend fun seedProfiles(fixture: PreferenceFixture) {
        insertProfile(fixture.database, PROFILE_A, 100L)
        insertProfile(fixture.database, PROFILE_B, 200L)
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
        fixture.preferences.updateVbt(PROFILE_A, VbtPreferences(enabled = false, velocityLossThresholdPercent = 30), 14L)

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
        fixture.preferences.updateVbt(PROFILE_B, VbtPreferences(enabled = true, velocityLossThresholdPercent = 40), 24L)
    }

    private fun insertProfile(database: VitruvianDatabase, id: String, createdAt: Long) {
        if (database.vitruvianDatabaseQueries.getProfileById(id).executeAsOneOrNull() == null) {
            database.vitruvianDatabaseQueries.insertProfile(id, id, 0L, createdAt, 0L)
        }
        database.vitruvianDatabaseQueries.insertDefaultProfilePreferences(id, 1L)
    }

    private inline fun <reified T> jsonElement(value: T): JsonElement = testJson.encodeToJsonElement(value)

    private fun rackItem(id: String, name: String, weightKg: Float) = RackItem(
        id = id,
        name = name,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = RackItemBehavior.ADDED_RESISTANCE,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun profileBackup(id: String, isActive: Boolean = false) = UserProfileBackup(
        id = id,
        name = id,
        colorIndex = 0,
        createdAt = if (id == PROFILE_A) 100L else 200L,
        isActive = isActive,
    )

    private fun preferenceEntry(
        profileId: String,
        core: JsonElement? = null,
        rack: JsonElement? = null,
        workout: JsonElement? = null,
        led: JsonElement? = null,
        vbt: JsonElement? = null,
    ) = buildJsonObject {
        put("profileId", profileId)
        core?.let { put("core", it) }
        rack?.let { put("rack", it) }
        workout?.let { put("workout", it) }
        led?.let { put("led", it) }
        vbt?.let { put("vbt", it) }
    }

    private fun standardBackup(
        version: Int,
        identities: List<UserProfileBackup>,
        preferences: List<JsonElement> = emptyList(),
    ): String = buildJsonObject {
        put("version", version)
        put("exportedAt", "2026-07-12T00:00:00Z")
        put("appVersion", "test")
        put("data", buildJsonObject {
            put("userProfiles", testJson.encodeToJsonElement(identities))
            put("profilePreferences", JsonArray(preferences))
        })
    }.toString()

    private fun adversarialBackup(
        finalVersion: Int,
        identities: List<UserProfileBackup>,
        preferences: List<JsonElement>,
        legacyRack: JsonElement,
    ): String = buildJsonObject {
        put("data", buildJsonObject {
            put("profilePreferences", JsonArray(preferences))
            put("equipmentRackItems", legacyRack)
            put("userProfiles", testJson.encodeToJsonElement(identities))
            put("futureData", buildJsonObject { put("ignored", true) })
        })
        put("futureRoot", JsonArray(listOf(JsonPrimitive("ignored"))))
        put("version", finalVersion)
        put("exportedAt", "2026-07-12T00:00:00Z")
        put("appVersion", "test")
    }.toString()

    private data class PreferenceFixture(
        val driver: SqlDriver,
        val database: VitruvianDatabase,
        val preferences: ProfilePreferencesRepository,
        val safetyStore: SettingsProfileLocalSafetyStore,
        val recordingUserProfiles: RecordingUserProfileRepository,
        val manager: TestDataBackupManager,
    )

    private class RecordingUserProfileRepository(
        private val delegate: UserProfileRepository,
        private val reconciliationFailure: Throwable? = null,
    ) : UserProfileRepository by delegate {
        var reconcileCalls: Int = 0
            private set

        override suspend fun reconcileActiveProfileContext() {
            reconcileCalls++
            reconciliationFailure?.let { throw it }
            delegate.reconcileActiveProfileContext()
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

    private companion object {
        const val PROFILE_A = "profile-a"
        const val PROFILE_B = "profile-b"

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

    @Test
    fun `streaming import round-trip preserves all entities and profile preferences`() = runTest {
        // 1. Create original database and populate
        val originalFixture = preferenceFixture()
        seedProfiles(originalFixture)
        val originalDb = originalFixture.database
        val originalRepo = SqlDelightWorkoutRepository(originalDb, FakeExerciseRepository())
        val originalManager = originalFixture.manager

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
            uuid = null,
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
        assertEquals(setOf("default", PROFILE_A, PROFILE_B), originalBackup.data.userProfiles.map { it.id }.toSet())
        val originalPreferenceEntries = testJson.parseToJsonElement(exportedJson).jsonObject
            .getValue("data").jsonObject.getValue("profilePreferences").jsonArray
            .associateBy { it.jsonObject.getValue("profileId").jsonPrimitive.content }
        assertEquals(setOf("default", PROFILE_A, PROFILE_B), originalPreferenceEntries.keys)
        val originalA = originalPreferenceEntries.getValue(PROFILE_A).jsonObject
        listOf("core", "rack", "workout", "led", "vbt").forEach { section ->
            assertTrue(section in originalA, "source export must include $section")
        }

        // 3. Create FRESH database and import via streaming
        val freshFixture = preferenceFixture()
        val freshDb = freshFixture.database
        val freshManager = freshFixture.manager

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
        assertEquals(
            originalBackup.data.userProfiles.map { it.id }.toSet(),
            reExportedBackup.data.userProfiles.map { it.id }.toSet(),
            "Profile identities must survive streaming round-trip",
        )
        val reExportedPreferenceEntries = testJson.parseToJsonElement(reExportedJson).jsonObject
            .getValue("data").jsonObject.getValue("profilePreferences").jsonArray
            .associateBy { it.jsonObject.getValue("profileId").jsonPrimitive.content }
        assertEquals(
            originalPreferenceEntries,
            reExportedPreferenceEntries,
            "All five preference sections must survive streaming round-trip",
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
