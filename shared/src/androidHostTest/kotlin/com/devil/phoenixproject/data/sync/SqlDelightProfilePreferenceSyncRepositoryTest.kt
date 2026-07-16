package com.devil.phoenixproject.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class SqlDelightProfilePreferenceSyncRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: VitruvianDatabase
    private lateinit var foundationRepository: SqlDelightProfilePreferencesRepository
    private lateinit var codec: ProfilePreferenceSyncCodec
    private lateinit var repository: SqlDelightProfilePreferenceSyncRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        database = VitruvianDatabase(driver)
        foundationRepository = SqlDelightProfilePreferencesRepository(database)
        codec = ProfilePreferenceSyncCodec()
        repository = SqlDelightProfilePreferenceSyncRepository(database, codec)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun createProfile(id: String) {
        database.vitruvianDatabaseQueries.insertProfile(id, id, 0, 1, 0)
    }

    private fun assertProfileDoesNotExist(id: String) {
        assertNull(database.vitruvianDatabaseQueries.getProfileById(id).executeAsOneOrNull())
    }

    @Test
    fun `dirty snapshot reconstructs row owned LED and VBT values as objects`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateLed(
            "profile-a",
            LedPreferences(colorScheme = 7, discoModeUnlocked = true),
            now = 20,
        )
        foundationRepository.updateVbt(
            "profile-a",
            VbtPreferences(enabled = false, velocityLossThresholdPercent = 35),
            now = 30,
        )

        val snapshot = repository.snapshotDirtySections()
        val led = snapshot.valid.single { it.key.section == ProfilePreferenceSectionName.LED }
        val vbt = snapshot.valid.single { it.key.section == ProfilePreferenceSectionName.VBT }

        assertEquals(7, led.payload.getValue("ledColorSchemeId").jsonPrimitive.int)
        assertTrue(led.payload.getValue("preferences") is JsonObject)
        assertNull(led.payload.getValue("preferences").jsonObject["colorScheme"])
        assertFalse(vbt.payload.getValue("vbtEnabled").jsonPrimitive.boolean)
        assertTrue(vbt.payload.getValue("preferences") is JsonObject)
        assertNull(vbt.payload.getValue("preferences").jsonObject["enabled"])
        assertFalse(led.payload.toString().contains("\\\"{", ignoreCase = false))
        assertFalse(vbt.payload.toString().contains("\\\"{", ignoreCase = false))
    }

    @Test
    fun `malformed dirty document is reported and valid sibling remains syncable`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        database.vitruvianDatabaseQueries.updateWorkoutProfilePreferences(
            workout_preferences_json = "{broken",
            workout_updated_at = 20,
            profile_id = "profile-a",
        )
        foundationRepository.updateRack(
            "profile-a",
            RackPreferences(items = listOf(RackItem(id = "rack-1", name = "Bar", weightKg = 20f))),
            now = 30,
        )

        val snapshot = repository.snapshotDirtySections()

        assertTrue(snapshot.valid.any { it.key.section == ProfilePreferenceSectionName.RACK })
        assertTrue(snapshot.valid.none { it.key.section == ProfilePreferenceSectionName.WORKOUT })
        assertEquals(ProfilePreferenceSectionName.WORKOUT, snapshot.unsyncable.single().key.section)
        assertEquals(
            ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT.name,
            snapshot.unsyncable.single().reason,
        )
    }

    private fun forceDirtyCoreRevision(profileId: String, revision: Long) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE UserProfilePreferences
                   SET core_server_revision = ?, core_dirty = 1
                 WHERE profile_id = ?
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, revision)
            bindString(1, profileId)
        }
    }

    @Test
    fun `base revision max is syncable while max plus one is a dead letter`() = runTest {
        listOf(
            "profile-max" to MAX_EXACT_JSON_INTEGER,
            "profile-over" to MAX_EXACT_JSON_INTEGER + 1,
        ).forEach { (profileId, revision) ->
            createProfile(profileId)
            foundationRepository.insertDefaults(profileId)
            forceDirtyCoreRevision(profileId, revision)
        }

        val first = repository.snapshotDirtySections()
        val max = first.valid.single {
            it.key.localProfileId == "profile-max" &&
                it.key.section == ProfilePreferenceSectionName.CORE
        }
        assertEquals(MAX_EXACT_JSON_INTEGER, max.baseRevision)
        assertTrue(first.valid.none {
            it.key.localProfileId == "profile-over" &&
                it.key.section == ProfilePreferenceSectionName.CORE
        })
        val issue = first.unsyncable.single {
            it.key.localProfileId == "profile-over" &&
                it.key.section == ProfilePreferenceSectionName.CORE
        }
        assertEquals(
            foundationRepository.get("profile-over").core.metadata.localGeneration,
            issue.localGeneration,
        )
        assertEquals(ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER.name, issue.reason)
        assertTrue(foundationRepository.get("profile-over").core.metadata.dirty)

        val second = repository.snapshotDirtySections()
        assertEquals(
            setOf("profile-over"),
            second.unsyncable.filter { it.key.section == ProfilePreferenceSectionName.CORE }
                .map { it.key.localProfileId }
                .toSet(),
        )
    }

    @Test
    fun `rack signed timestamp bounds stay JSON numbers and overflow is dead lettered`() = runTest {
        suspend fun writeRack(
            profileId: String,
            createdAt: Long,
            updatedAt: Long,
            duplicateName: Boolean = false,
        ) {
            createProfile(profileId)
            foundationRepository.insertDefaults(profileId)
            val items = buildList {
                add(
                    RackItem(
                        id = "rack-1",
                        name = "Same name",
                        weightKg = 20f,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                )
                if (duplicateName) {
                    add(
                        RackItem(
                            id = "rack-2",
                            name = "Same name",
                            weightKg = 10f,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                        ),
                    )
                }
            }
            foundationRepository.updateRack(profileId, RackPreferences(items = items), now = 30)
        }
        writeRack(
            "rack-bounds",
            createdAt = MIN_EXACT_JSON_INTEGER,
            updatedAt = MAX_EXACT_JSON_INTEGER,
            duplicateName = true,
        )
        writeRack("rack-created-over", MAX_EXACT_JSON_INTEGER + 1, 0)
        writeRack("rack-updated-under", 0, MIN_EXACT_JSON_INTEGER - 1)

        val snapshot = repository.snapshotDirtySections()
        val bounds = snapshot.valid.single {
            it.key.localProfileId == "rack-bounds" &&
                it.key.section == ProfilePreferenceSectionName.RACK
        }
        val firstItem = bounds.payload.getValue("items").jsonArray.first().jsonObject
        assertFalse(firstItem.getValue("createdAt").jsonPrimitive.isString)
        assertFalse(firstItem.getValue("updatedAt").jsonPrimitive.isString)
        assertEquals(MIN_EXACT_JSON_INTEGER, firstItem.getValue("createdAt").jsonPrimitive.long)
        assertEquals(MAX_EXACT_JSON_INTEGER, firstItem.getValue("updatedAt").jsonPrimitive.long)
        assertEquals(2, bounds.payload.getValue("items").jsonArray.size)

        mapOf(
            "rack-created-over" to "RACK.items[0].createdAt",
            "rack-updated-under" to "RACK.items[0].updatedAt",
        ).forEach { (profileId, field) ->
            assertTrue(snapshot.valid.none {
                it.key.localProfileId == profileId &&
                    it.key.section == ProfilePreferenceSectionName.RACK
            })
            val issue = snapshot.unsyncable.single {
                it.key.localProfileId == profileId &&
                    it.key.section == ProfilePreferenceSectionName.RACK
            }
            assertEquals(
                ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER.name,
                issue.reason,
                field,
            )
            assertTrue(foundationRepository.get(profileId).rack.metadata.dirty)
        }

        val oldGeneration = snapshot.unsyncable.single {
            it.key.localProfileId == "rack-created-over" &&
                it.key.section == ProfilePreferenceSectionName.RACK
        }.localGeneration
        foundationRepository.updateRack(
            "rack-created-over",
            RackPreferences(
                items = listOf(
                    RackItem(
                        id = "rack-1",
                        name = "Same name",
                        weightKg = 20f,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                ),
            ),
            now = 40,
        )
        val revalidated = repository.snapshotDirtySections().valid.single {
            it.key.localProfileId == "rack-created-over" &&
                it.key.section == ProfilePreferenceSectionName.RACK
        }
        assertTrue(revalidated.localGeneration > oldGeneration)
    }

    private fun forceDirtyLedColorScheme(profileId: String, colorScheme: Long) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE UserProfilePreferences
                   SET led_color_scheme_id = ?, led_dirty = 1
                 WHERE profile_id = ?
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, colorScheme)
            bindString(1, profileId)
        }
    }

    @Test
    fun `LED color scheme validates Int32 before conversion and never wraps`() = runTest {
        mapOf(
            "led-max" to Int.MAX_VALUE.toLong(),
            "led-over" to Int.MAX_VALUE.toLong() + 1,
            "led-wraps-to-zero" to 4_294_967_296L,
        ).forEach { (profileId, value) ->
            createProfile(profileId)
            foundationRepository.insertDefaults(profileId)
            forceDirtyLedColorScheme(profileId, value)
        }

        val snapshot = repository.snapshotDirtySections()
        val max = snapshot.valid.single {
            it.key.localProfileId == "led-max" && it.key.section == ProfilePreferenceSectionName.LED
        }
        assertEquals(Int.MAX_VALUE, max.payload.getValue("ledColorSchemeId").jsonPrimitive.int)
        listOf("led-over", "led-wraps-to-zero").forEach { profileId ->
            assertTrue(snapshot.valid.none {
                it.key.localProfileId == profileId && it.key.section == ProfilePreferenceSectionName.LED
            })
            assertEquals(
                ProfilePreferenceSyncIssueReason.INVALID_INT32.name,
                snapshot.unsyncable.single {
                    it.key.localProfileId == profileId &&
                        it.key.section == ProfilePreferenceSectionName.LED
                }.reason,
            )
        }
    }

    private fun singleExerciseDefaults(exerciseId: String) = SingleExerciseDefaultsDocument(
        exerciseId = exerciseId,
        setReps = emptyList(),
        weightPerCableKg = 20f,
        setWeightsPerCableKg = emptyList(),
        progressionKg = 0f,
        setRestSeconds = emptyList(),
        workoutModeId = 0,
        eccentricLoadPercentage = 100,
        echoLevelValue = 1,
        duration = 0,
        isAMRAP = false,
        perSetRestTime = false,
    )

    @Test
    fun `Postgres incompatible text and normalized local only keys dead letter only their sections`() = runTest {
        createProfile("nul-profile")
        foundationRepository.insertDefaults("nul-profile")
        foundationRepository.updateRack(
            "nul-profile",
            RackPreferences(
                items = listOf(
                    RackItem(id = "rack-nul", name = "SECRET_SENTINEL\u0000", weightKg = 20f),
                ),
            ),
            now = 20,
        )

        createProfile("surrogate-profile")
        foundationRepository.insertDefaults("surrogate-profile")
        database.vitruvianDatabaseQueries.updateRackProfilePreferences(
            equipment_rack_json =
                """{"version":1,"items":[{"id":"rack-surrogate","name":"bad${'\\'}uD800","weightKg":20}]}""",
            rack_updated_at = 20,
            profile_id = "surrogate-profile",
        )

        val forbiddenKey = "safeWord\u0130"
        createProfile("local-key-profile")
        foundationRepository.insertDefaults("local-key-profile")
        foundationRepository.updateWorkout(
            "local-key-profile",
            WorkoutPreferences(
                singleExerciseDefaults = mapOf(
                    forbiddenKey to singleExerciseDefaults(forbiddenKey),
                ),
            ),
            now = 20,
        )

        val snapshot = repository.snapshotDirtySections()
        mapOf(
            ProfilePreferenceSectionKey("nul-profile", ProfilePreferenceSectionName.RACK) to
                ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE,
            ProfilePreferenceSectionKey("surrogate-profile", ProfilePreferenceSectionName.RACK) to
                ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE,
            ProfilePreferenceSectionKey("local-key-profile", ProfilePreferenceSectionName.WORKOUT) to
                ProfilePreferenceSyncIssueReason.LOCAL_ONLY_WIRE_KEY,
        ).forEach { (key, expectedReason) ->
            assertTrue(snapshot.valid.none { it.key == key })
            val issue = snapshot.unsyncable.single { it.key == key }
            assertEquals(expectedReason.name, issue.reason)
            assertFalse(issue.reason.contains("SECRET_SENTINEL"))
            assertFalse(issue.reason.contains(forbiddenKey))
        }
        assertTrue(snapshot.valid.any {
            it.key == ProfilePreferenceSectionKey("nul-profile", ProfilePreferenceSectionName.CORE)
        })
        assertTrue(snapshot.valid.any {
            it.key == ProfilePreferenceSectionKey("local-key-profile", ProfilePreferenceSectionName.RACK)
        })
    }

    private fun forceDirtySectionUpdatedAt(
        profileId: String,
        section: ProfilePreferenceSectionName,
        updatedAt: Long,
    ) {
        val updatedAtColumn = when (section) {
            ProfilePreferenceSectionName.CORE -> "core_updated_at"
            ProfilePreferenceSectionName.RACK -> "rack_updated_at"
            ProfilePreferenceSectionName.WORKOUT -> "workout_updated_at"
            ProfilePreferenceSectionName.LED -> "led_updated_at"
            ProfilePreferenceSectionName.VBT -> "vbt_updated_at"
        }
        val dirtyColumn = when (section) {
            ProfilePreferenceSectionName.CORE -> "core_dirty"
            ProfilePreferenceSectionName.RACK -> "rack_dirty"
            ProfilePreferenceSectionName.WORKOUT -> "workout_dirty"
            ProfilePreferenceSectionName.LED -> "led_dirty"
            ProfilePreferenceSectionName.VBT -> "vbt_dirty"
        }
        driver.execute(
            identifier = null,
            sql = """
                UPDATE UserProfilePreferences
                   SET $updatedAtColumn = ?, $dirtyColumn = 1
                 WHERE profile_id = ?
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, updatedAt)
            bindString(1, profileId)
        }
    }

    @Test
    fun `client modified timestamp bounds and adjacent dead letters cover every section`() = runTest {
        data class TimestampCase(val suffix: String, val value: Long, val valid: Boolean)

        val cases = listOf(
            TimestampCase("min", MIN_RFC3339_EPOCH_MILLIS, true),
            TimestampCase("max", MAX_RFC3339_EPOCH_MILLIS, true),
            TimestampCase("under", MIN_RFC3339_EPOCH_MILLIS - 1, false),
            TimestampCase("over", MAX_RFC3339_EPOCH_MILLIS + 1, false),
        )
        ProfilePreferenceSectionName.entries.forEach { section ->
            cases.forEach { case ->
                val profileId = "timestamp-${section.name.lowercase()}-${case.suffix}"
                createProfile(profileId)
                foundationRepository.insertDefaults(profileId)
                forceDirtySectionUpdatedAt(profileId, section, case.value)
            }
        }

        val snapshot = repository.snapshotDirtySections()
        ProfilePreferenceSectionName.entries.forEach { section ->
            cases.forEach { case ->
                val profileId = "timestamp-${section.name.lowercase()}-${case.suffix}"
                val key = ProfilePreferenceSectionKey(profileId, section)
                if (case.valid) {
                    assertEquals(
                        case.value,
                        snapshot.valid.single { it.key == key }.clientModifiedAtEpochMs,
                    )
                    assertTrue(snapshot.unsyncable.none { it.key == key })
                } else {
                    assertTrue(snapshot.valid.none { it.key == key })
                    assertEquals(
                        ProfilePreferenceSyncIssueReason.INVALID_CLIENT_MODIFIED_AT.name,
                        snapshot.unsyncable.single { it.key == key }.reason,
                    )
                    assertTrue(snapshot.valid.any {
                        it.key.localProfileId == profileId && it.key.section != section
                    })
                }
            }
        }
    }

    @Test
    fun `invalid profile IDs dead letter every dirty section with fixed category`() = runTest {
        val invalidProfileIds = listOf("", "SECRET_SENTINEL\u0000")
        invalidProfileIds.forEach { profileId ->
            createProfile(profileId)
            foundationRepository.insertDefaults(profileId)
        }

        val snapshot = repository.snapshotDirtySections()
        invalidProfileIds.forEach { profileId ->
            assertTrue(snapshot.valid.none { it.key.localProfileId == profileId })
            val issues = snapshot.unsyncable.filter { it.key.localProfileId == profileId }
            assertEquals(ProfilePreferenceSectionName.entries.toSet(), issues.map { it.key.section }.toSet())
            assertEquals(
                setOf(ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID.name),
                issues.map { it.reason }.toSet(),
            )
            assertTrue(issues.all { !it.reason.contains("SECRET_SENTINEL") })
        }

        createProfile("surrogate-template")
        foundationRepository.insertDefaults("surrogate-template")
        val surrogate = codec.encodeDirtyRow(
            database.vitruvianDatabaseQueries.selectProfilePreferences("surrogate-template")
                .executeAsOne()
                .copy(profile_id = "bad\uD800"),
        )
        assertTrue(surrogate.valid.isEmpty())
        assertEquals(
            ProfilePreferenceSectionName.entries.toSet(),
            surrogate.unsyncable.map { it.key.section }.toSet(),
        )
        assertEquals(
            setOf(ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID.name),
            surrogate.unsyncable.map { it.reason }.toSet(),
        )
    }

    private fun coreCanonical(
        revision: Long,
        bodyWeightKg: Double,
        updatedAt: Long = 1_783_771_200_000L,
        profileId: String = "profile-a",
    ) = CanonicalProfilePreferenceSection(
        key = ProfilePreferenceSectionKey(profileId, ProfilePreferenceSectionName.CORE),
        documentVersion = 1,
        serverRevision = revision,
        serverUpdatedAtEpochMs = updatedAt,
        payload = buildJsonObject {
            put("bodyWeightKg", bodyWeightKg)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )

    @Test
    fun `push canonical racing newer edit advances revision without clearing dirty`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.section == ProfilePreferenceSectionName.CORE
        }
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 90f), now = 30)

        repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    key = sent.key,
                    sentLocalGeneration = sent.localGeneration,
                    serverRevision = 4,
                    canonical = coreCanonical(revision = 4, bodyWeightKg = 80.0),
                    rejectionReason = null,
                ),
            ),
        )

        val current = foundationRepository.get("profile-a").core
        assertEquals(90f, current.value.bodyWeightKg)
        assertEquals(4, current.metadata.serverRevision)
        assertTrue(current.metadata.dirty)
        assertTrue(current.metadata.localGeneration > sent.localGeneration)
    }

    @Test
    fun `matching generation conflict canonical replaces snapshot and clears dirty`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.section == ProfilePreferenceSectionName.CORE
        }

        repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    key = sent.key,
                    sentLocalGeneration = sent.localGeneration,
                    serverRevision = 6,
                    canonical = coreCanonical(revision = 6, bodyWeightKg = 85.0),
                    rejectionReason = "REVISION_CONFLICT",
                ),
            ),
        )

        val current = foundationRepository.get("profile-a").core
        assertEquals(85f, current.value.bodyWeightKg)
        assertEquals(6, current.metadata.serverRevision)
        assertFalse(current.metadata.dirty)
    }

    @Test
    fun `pull updates only clean nonnewer rows and never creates unknown profile`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.section == ProfilePreferenceSectionName.CORE
        }
        repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    key = sent.key,
                    sentLocalGeneration = sent.localGeneration,
                    serverRevision = 2,
                    canonical = coreCanonical(revision = 2, bodyWeightKg = 80.0, updatedAt = 100),
                    rejectionReason = null,
                ),
            ),
        )

        val report = repository.applyPulledSections(
            listOf(
                coreCanonical(revision = 3, bodyWeightKg = 83.0),
                coreCanonical(revision = 9, bodyWeightKg = 99.0).copy(
                    key = ProfilePreferenceSectionKey("remote-only", ProfilePreferenceSectionName.CORE),
                ),
            ),
        )

        val current = foundationRepository.get("profile-a").core
        assertEquals(83f, current.value.bodyWeightKg)
        assertEquals(3, current.metadata.serverRevision)
        assertFalse(current.metadata.dirty)
        assertEquals(1, report.ignoredUnknownProfile)
        assertProfileDoesNotExist("remote-only")
    }

    @Test
    fun `invalid unknown pull IDs are rejected before lookup while valid sibling applies`() = runTest {
        val knownProfileId = "known-profile"
        createProfile(knownProfileId)
        foundationRepository.insertDefaults(knownProfileId)
        acknowledgeAllDirty(knownProfileId, revision = 2, variant = 1)

        val report = repository.applyPulledSections(
            listOf(
                coreCanonical(3, 91.0, profileId = ""),
                coreCanonical(3, 92.0, profileId = "SECRET_SENTINEL\u0000"),
                coreCanonical(3, 93.0, profileId = "bad\uD800"),
                coreCanonical(3, 83.0, profileId = knownProfileId),
            ),
        )

        assertEquals(3, report.invalid)
        assertEquals(0, report.ignoredUnknownProfile)
        assertEquals(1, report.applied)
        assertEquals(83f, foundationRepository.get(knownProfileId).core.value.bodyWeightKg)
    }

    @Test
    fun `dirty pull leaves payload revision timestamp generation and dirty flag unchanged`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
        val before = foundationRepository.get("profile-a").core

        repository.applyPulledSections(listOf(coreCanonical(revision = 3, bodyWeightKg = 83.0)))

        val after = foundationRepository.get("profile-a").core
        assertEquals(before.value, after.value)
        assertEquals(before.metadata.serverRevision, after.metadata.serverRevision)
        assertEquals(before.metadata.updatedAt, after.metadata.updatedAt)
        assertEquals(before.metadata.localGeneration, after.metadata.localGeneration)
        assertTrue(after.metadata.dirty)
    }

    @Test
    fun `equal revision different payload repairs clean row`() = runTest {
        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.section == ProfilePreferenceSectionName.CORE
        }
        repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    key = sent.key,
                    sentLocalGeneration = sent.localGeneration,
                    serverRevision = 2,
                    canonical = coreCanonical(revision = 2, bodyWeightKg = 80.0, updatedAt = 100),
                    rejectionReason = null,
                ),
            ),
        )

        repository.applyPulledSections(
            listOf(coreCanonical(revision = 2, bodyWeightKg = 83.0, updatedAt = 200)),
        )

        val repaired = foundationRepository.get("profile-a").core
        assertEquals(83f, repaired.value.bodyWeightKg)
        assertEquals(2, repaired.metadata.serverRevision)
        assertEquals(200, repaired.metadata.updatedAt)
        assertFalse(repaired.metadata.dirty)
    }

    private fun canonical(
        profileId: String,
        section: ProfilePreferenceSectionName,
        revision: Long,
        variant: Int,
        updatedAt: Long = 1_783_771_200_000L + variant,
    ) = CanonicalProfilePreferenceSection(
        key = ProfilePreferenceSectionKey(profileId, section),
        documentVersion = 1,
        serverRevision = revision,
        serverUpdatedAtEpochMs = updatedAt,
        payload = when (section) {
            ProfilePreferenceSectionName.CORE -> buildJsonObject {
                put("bodyWeightKg", 80 + variant)
                put("weightUnit", "KG")
                put("weightIncrement", 0.5)
            }
            ProfilePreferenceSectionName.RACK -> buildJsonObject {
                put("version", 1)
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("id", "rack-$variant")
                        put("name", "Rack $variant")
                        put("category", "OTHER")
                        put("weightKg", variant)
                        put("behavior", "ADDED_RESISTANCE")
                        put("enabled", true)
                        put("sortOrder", variant)
                        put("createdAt", variant.toLong())
                        put("updatedAt", variant.toLong())
                    })
                }
            }
            ProfilePreferenceSectionName.WORKOUT -> buildJsonObject {
                put("version", 1)
                put("stopAtTop", variant % 2 == 1)
            }
            ProfilePreferenceSectionName.LED -> buildJsonObject {
                put("ledColorSchemeId", variant)
                putJsonObject("preferences") {
                    put("version", 1)
                    put("discoModeUnlocked", variant % 2 == 1)
                }
            }
            ProfilePreferenceSectionName.VBT -> buildJsonObject {
                put("vbtEnabled", variant % 2 == 0)
                putJsonObject("preferences") {
                    put("version", 1)
                    put("velocityLossThresholdPercent", 20 + variant)
                }
            }
        },
    )

    private fun assertVariant(preferences: UserProfilePreferences, variant: Int) {
        assertEquals((80 + variant).toFloat(), preferences.core.value.bodyWeightKg)
        assertEquals(WeightUnit.KG, preferences.core.value.weightUnit)
        assertEquals(0.5f, preferences.core.value.weightIncrement)
        assertEquals("rack-$variant", preferences.rack.value.items.single().id)
        assertEquals(variant.toFloat(), preferences.rack.value.items.single().weightKg)
        assertEquals(variant % 2 == 1, preferences.workout.value.stopAtTop)
        assertEquals(variant, preferences.led.value.colorScheme)
        assertEquals(variant % 2 == 1, preferences.led.value.discoModeUnlocked)
        assertEquals(variant % 2 == 0, preferences.vbt.value.enabled)
        assertEquals(20 + variant, preferences.vbt.value.velocityLossThresholdPercent)
    }

    private fun allMetadata(preferences: UserProfilePreferences) = listOf(
        preferences.core.metadata,
        preferences.rack.metadata,
        preferences.workout.metadata,
        preferences.led.metadata,
        preferences.vbt.metadata,
    )

    private suspend fun acknowledgeAllDirty(profileId: String, revision: Long, variant: Int) {
        val sent = repository.snapshotDirtySections().valid
            .filter { it.key.localProfileId == profileId }
            .associateBy { it.key.section }
        repository.applyPushOutcomes(
            ProfilePreferenceSectionName.entries.map { section ->
                val snapshot = sent.getValue(section)
                ProfilePreferencePushOutcome(
                    key = snapshot.key,
                    sentLocalGeneration = snapshot.localGeneration,
                    serverRevision = revision,
                    canonical = canonical(profileId, section, revision, variant),
                    rejectionReason = null,
                )
            },
        )
    }

    @Test
    fun `matching generation push persists all five sections and row owned columns`() = runTest {
        createProfile("push-all")
        foundationRepository.insertDefaults("push-all")

        acknowledgeAllDirty("push-all", revision = 2, variant = 1)

        val preferences = foundationRepository.get("push-all")
        assertVariant(preferences, variant = 1)
        allMetadata(preferences).forEach { metadata ->
            assertEquals(2, metadata.serverRevision)
            assertFalse(metadata.dirty)
        }
        val row = database.vitruvianDatabaseQueries
            .selectProfilePreferenceSyncRow("push-all")
            .executeAsOne()
        assertEquals(1L, row.led_color_scheme_id)
        assertFalse(row.led_preferences_json.contains("colorScheme"))
        assertEquals(0L, row.vbt_enabled)
        assertFalse(row.vbt_preferences_json.contains("enabled"))
    }

    @Test
    fun `newer local generations preserve all five values while advancing revisions`() = runTest {
        createProfile("race-all")
        foundationRepository.insertDefaults("race-all")
        val sent = repository.snapshotDirtySections().valid
            .filter { it.key.localProfileId == "race-all" }
            .associateBy { it.key.section }
        foundationRepository.updateCore(
            "race-all",
            CoreProfilePreferences(95f, WeightUnit.KG, 1f),
            now = 30,
        )
        foundationRepository.updateRack(
            "race-all",
            RackPreferences(items = listOf(RackItem(id = "local", name = "Local", weightKg = 9f))),
            now = 30,
        )
        foundationRepository.updateWorkout(
            "race-all",
            WorkoutPreferences(stopAtTop = false, beepsEnabled = false),
            now = 30,
        )
        foundationRepository.updateLed(
            "race-all",
            LedPreferences(colorScheme = 9, discoModeUnlocked = false),
            now = 30,
        )
        foundationRepository.updateVbt(
            "race-all",
            VbtPreferences(enabled = true, velocityLossThresholdPercent = 45),
            now = 30,
        )

        val report = repository.applyPushOutcomes(
            ProfilePreferenceSectionName.entries.map { section ->
                val snapshot = sent.getValue(section)
                ProfilePreferencePushOutcome(
                    key = snapshot.key,
                    sentLocalGeneration = snapshot.localGeneration,
                    serverRevision = 4,
                    canonical = canonical("race-all", section, revision = 4, variant = 1),
                    rejectionReason = null,
                )
            },
        )

        val current = foundationRepository.get("race-all")
        assertEquals(95f, current.core.value.bodyWeightKg)
        assertEquals("local", current.rack.value.items.single().id)
        assertFalse(current.workout.value.beepsEnabled)
        assertEquals(9, current.led.value.colorScheme)
        assertEquals(45, current.vbt.value.velocityLossThresholdPercent)
        allMetadata(current).forEach { metadata ->
            assertEquals(4, metadata.serverRevision)
            assertTrue(metadata.dirty)
        }
        assertEquals(5, report.preservedNewerLocal)
    }

    @Test
    fun `clean pull applies all five sections and rejects every lower revision`() = runTest {
        createProfile("pull-all")
        foundationRepository.insertDefaults("pull-all")
        acknowledgeAllDirty("pull-all", revision = 2, variant = 1)

        val applied = repository.applyPulledSections(
            ProfilePreferenceSectionName.entries.map { section ->
                canonical("pull-all", section, revision = 3, variant = 2)
            },
        )
        val afterHigher = foundationRepository.get("pull-all")
        assertEquals(5, applied.applied)
        assertVariant(afterHigher, variant = 2)
        allMetadata(afterHigher).forEach { metadata ->
            assertEquals(3, metadata.serverRevision)
            assertFalse(metadata.dirty)
        }

        val lower = repository.applyPulledSections(
            ProfilePreferenceSectionName.entries.map { section ->
                canonical("pull-all", section, revision = 2, variant = 3)
            },
        )
        assertEquals(0, lower.applied)
        assertEquals(afterHigher, foundationRepository.get("pull-all"))
    }

    @Test
    fun `canonical null identity mismatch and revision mismatch remain dirty`() = runTest {
        createProfile("invalid-outcome")
        foundationRepository.insertDefaults("invalid-outcome")
        foundationRepository.updateCore(
            "invalid-outcome",
            CoreProfilePreferences(bodyWeightKg = 80f),
            now = 20,
        )
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.localProfileId == "invalid-outcome" &&
                it.key.section == ProfilePreferenceSectionName.CORE
        }

        val noCanonical = repository.applyPushOutcomes(
            listOf(ProfilePreferencePushOutcome(sent.key, sent.localGeneration, 2, null, null)),
        )
        assertEquals(0, noCanonical.applied)
        assertTrue(foundationRepository.get("invalid-outcome").core.metadata.dirty)

        val wrongKey = repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    sent.key,
                    sent.localGeneration,
                    2,
                    canonical("other-profile", ProfilePreferenceSectionName.CORE, 2, 1),
                    null,
                ),
            ),
        )
        val wrongRevision = repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    sent.key,
                    sent.localGeneration,
                    3,
                    canonical("invalid-outcome", ProfilePreferenceSectionName.CORE, 2, 1),
                    null,
                ),
            ),
        )
        assertEquals(1, wrongKey.invalid)
        assertEquals(1, wrongRevision.invalid)
        assertTrue(foundationRepository.get("invalid-outcome").core.metadata.dirty)
    }

    @Test
    fun `canonical revision bounds and malformed payload fail closed with category only reasons`() {
        val max = coreCanonical(MAX_EXACT_JSON_INTEGER, 80.0)
        assertIs<ProfilePreferenceCanonicalColumnsResult.Valid>(codec.decodeCanonical(max))
        listOf(-1L, MAX_EXACT_JSON_INTEGER + 1).forEach { revision ->
            val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
                codec.decodeCanonical(coreCanonical(revision, 80.0)),
            )
            assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION, invalid.reason)
        }
        val sentinel = "SECRET_SENTINEL"
        val malformed = canonical("profile-a", ProfilePreferenceSectionName.WORKOUT, 3, 1)
            .copy(payload = buildJsonObject {
                put("version", 1)
                put("repCountTiming", sentinel)
            })
        val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
            codec.decodeCanonical(malformed),
        )
        assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD, invalid.reason)
        assertFalse(invalid.reason.name.contains(sentinel))
    }

    @Test
    fun `canonical profile ID and timestamp boundaries fail closed before persistence`() = runTest {
        listOf("", "SECRET_SENTINEL\u0000", "bad\uD800").forEach { profileId ->
            val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
                codec.decodeCanonical(coreCanonical(2, 80.0, profileId = profileId)),
            )
            assertEquals(ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID, invalid.reason)
            assertFalse(invalid.reason.name.contains("SECRET_SENTINEL"))
        }
        listOf(MIN_RFC3339_EPOCH_MILLIS, MAX_RFC3339_EPOCH_MILLIS).forEach { updatedAt ->
            assertIs<ProfilePreferenceCanonicalColumnsResult.Valid>(
                codec.decodeCanonical(coreCanonical(2, 80.0, updatedAt = updatedAt)),
            )
        }
        listOf(
            MIN_RFC3339_EPOCH_MILLIS - 1,
            MAX_RFC3339_EPOCH_MILLIS + 1,
        ).forEach { updatedAt ->
            val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
                codec.decodeCanonical(coreCanonical(2, 80.0, updatedAt = updatedAt)),
            )
            assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP, invalid.reason)
        }

        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        acknowledgeAllDirty("profile-a", revision = 2, variant = 1)
        val before = foundationRepository.get("profile-a")
        val report = repository.applyPulledSections(
            listOf(
                coreCanonical(
                    revision = 3,
                    bodyWeightKg = 90.0,
                    updatedAt = MAX_RFC3339_EPOCH_MILLIS + 1,
                ),
            ),
        )
        assertEquals(1, report.invalid)
        assertEquals(before, foundationRepository.get("profile-a"))
    }

    private fun rackCanonicalWithTimestamps(
        createdAt: Long,
        updatedAt: Long,
        revision: Long = 3,
        profileId: String = "profile-a",
    ) = CanonicalProfilePreferenceSection(
        key = ProfilePreferenceSectionKey(profileId, ProfilePreferenceSectionName.RACK),
        documentVersion = 1,
        serverRevision = revision,
        serverUpdatedAtEpochMs = 1_783_771_200_000L,
        payload = buildJsonObject {
            put("version", 1)
            putJsonArray("items") {
                add(buildJsonObject {
                    put("id", "rack-boundary")
                    put("name", "Rack")
                    put("weightKg", 20)
                    put("createdAt", createdAt)
                    put("updatedAt", updatedAt)
                })
            }
        },
    )

    @Test
    fun `rack canonical timestamp exact integer bounds pass and adjacent values do not mutate`() = runTest {
        assertIs<ProfilePreferenceCanonicalColumnsResult.Valid>(
            codec.decodeCanonical(
                rackCanonicalWithTimestamps(MIN_EXACT_JSON_INTEGER, MAX_EXACT_JSON_INTEGER),
            ),
        )
        listOf(
            rackCanonicalWithTimestamps(MAX_EXACT_JSON_INTEGER + 1, 0),
            rackCanonicalWithTimestamps(0, MIN_EXACT_JSON_INTEGER - 1),
        ).forEach { canonical ->
            val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
                codec.decodeCanonical(canonical),
            )
            assertEquals(ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER, invalid.reason)
        }

        createProfile("profile-a")
        foundationRepository.insertDefaults("profile-a")
        acknowledgeAllDirty("profile-a", revision = 2, variant = 1)
        val before = foundationRepository.get("profile-a").rack
        val report = repository.applyPulledSections(
            listOf(rackCanonicalWithTimestamps(MAX_EXACT_JSON_INTEGER + 1, 0)),
        )
        assertEquals(1, report.invalid)
        assertEquals(before, foundationRepository.get("profile-a").rack)
    }

    @Test
    fun `canonical divergence event never exposes user controlled profile ID`() = runTest {
        val profileId = "SECRET_SENTINEL-profile"
        createProfile(profileId)
        foundationRepository.insertDefaults(profileId)
        acknowledgeAllDirty(profileId, revision = 2, variant = 1)
        val observedSections = mutableListOf<ProfilePreferenceSectionName>()
        val loggingRepository = SqlDelightProfilePreferenceSyncRepository(
            database,
            codec,
        ) { section -> observedSections += section }

        loggingRepository.applyPulledSections(
            listOf(coreCanonical(2, 99.0, profileId = profileId)),
        )

        assertEquals(listOf(ProfilePreferenceSectionName.CORE), observedSections)
        assertFalse(observedSections.joinToString().contains("SECRET_SENTINEL"))
    }

    @Test
    fun `malformed pulled canonical is isolated while valid sibling applies`() = runTest {
        createProfile("malformed-pull")
        foundationRepository.insertDefaults("malformed-pull")
        acknowledgeAllDirty("malformed-pull", revision = 2, variant = 1)
        val malformed = canonical(
            "malformed-pull",
            ProfilePreferenceSectionName.WORKOUT,
            revision = 3,
            variant = 2,
        ).copy(payload = buildJsonObject {
            put("version", 1)
            put("repCountTiming", "SECRET_SENTINEL")
        })

        val report = repository.applyPulledSections(
            listOf(
                malformed,
                canonical("malformed-pull", ProfilePreferenceSectionName.CORE, 3, 2),
            ),
        )

        val current = foundationRepository.get("malformed-pull")
        assertEquals(1, report.invalid)
        assertEquals(1, report.applied)
        assertTrue(current.workout.value.stopAtTop)
        assertEquals(82f, current.core.value.bodyWeightKg)
    }

    @Test
    fun `matching generation requires dirty state and nonregressing revision`() = runTest {
        createProfile("stale-push")
        foundationRepository.insertDefaults("stale-push")
        val sent = repository.snapshotDirtySections().valid.single {
            it.key.localProfileId == "stale-push" &&
                it.key.section == ProfilePreferenceSectionName.CORE
        }
        repository.applyPushOutcomes(
            listOf(
                ProfilePreferencePushOutcome(
                    sent.key,
                    sent.localGeneration,
                    6,
                    canonical("stale-push", ProfilePreferenceSectionName.CORE, 6, 6),
                    null,
                ),
            ),
        )
        val stale = ProfilePreferencePushOutcome(
            sent.key,
            sent.localGeneration,
            5,
            canonical("stale-push", ProfilePreferenceSectionName.CORE, 5, 5),
            null,
        )
        assertEquals(0, repository.applyPushOutcomes(listOf(stale)).applied)

        driver.execute(
            null,
            "UPDATE UserProfilePreferences SET core_dirty = 1 WHERE profile_id = ?",
            1,
        ) { bindString(0, "stale-push") }
        assertEquals(0, repository.applyPushOutcomes(listOf(stale)).applied)
        val current = foundationRepository.get("stale-push").core
        assertEquals(86f, current.value.bodyWeightKg)
        assertEquals(6, current.metadata.serverRevision)
        assertTrue(current.metadata.dirty)
    }

    @Test
    fun `lost acknowledgement then later edit retains approved matching generation server wins policy`() =
        runTest {
            createProfile("lost-ack-edit")
            foundationRepository.insertDefaults("lost-ack-edit")
            foundationRepository.updateCore(
                "lost-ack-edit",
                CoreProfilePreferences(bodyWeightKg = 80f),
                now = 20,
            )
            val committedButUnacknowledged = repository.snapshotDirtySections().valid.single {
                it.key.localProfileId == "lost-ack-edit" &&
                    it.key.section == ProfilePreferenceSectionName.CORE
            }

            // Simulate the server committing generation 1 at revision 1 and the HTTP response
            // being lost: do not apply an outcome locally before the user edits again.
            foundationRepository.updateCore(
                "lost-ack-edit",
                CoreProfilePreferences(bodyWeightKg = 90f),
                now = 30,
            )
            val retry = repository.snapshotDirtySections().valid.single {
                it.key == committedButUnacknowledged.key
            }
            assertTrue(retry.localGeneration > committedButUnacknowledged.localGeneration)
            assertEquals(0L, retry.baseRevision)

            repository.applyPushOutcomes(
                listOf(
                    ProfilePreferencePushOutcome(
                        key = retry.key,
                        sentLocalGeneration = retry.localGeneration,
                        serverRevision = 1,
                        canonical = coreCanonical(
                            revision = 1,
                            bodyWeightKg = 80.0,
                            profileId = "lost-ack-edit",
                        ),
                        rejectionReason = "REVISION_CONFLICT",
                    ),
                ),
            )

            val current = foundationRepository.get("lost-ack-edit").core
            assertEquals(80f, current.value.bodyWeightKg)
            assertEquals(1L, current.metadata.serverRevision)
            assertFalse(current.metadata.dirty)
        }

    @Test
    fun `semantic numeric equality does not become equal revision divergence`() = runTest {
        createProfile("numeric-equality")
        foundationRepository.insertDefaults("numeric-equality")
        val integerToken = coreCanonical(0, 80.0, profileId = "numeric-equality").copy(
            payload = PortalWireJson.parseToJsonElement(
                """{"bodyWeightKg":80,"weightUnit":"KG","weightIncrement":0.5}""",
            ).jsonObject,
        )
        driver.execute(
            null,
            """
                UPDATE UserProfilePreferences
                   SET body_weight_kg = 80, weight_unit = 'KG', weight_increment = 0.5,
                       core_dirty = 0
                 WHERE profile_id = ?
            """.trimIndent(),
            1,
        ) { bindString(0, "numeric-equality") }
        val normalizedRow = database.vitruvianDatabaseQueries
            .selectProfilePreferenceSyncRow("numeric-equality")
            .executeAsOne()

        assertFalse(codec.hasCanonicalDivergence(normalizedRow, integerToken))
        assertTrue(
            codec.hasCanonicalDivergence(
                normalizedRow,
                coreCanonical(0, 83.0, profileId = "numeric-equality"),
            ),
        )
    }

    private companion object {
        const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
        const val MIN_EXACT_JSON_INTEGER = -9_007_199_254_740_991L
        const val MIN_RFC3339_EPOCH_MILLIS = -62_135_596_800_000L
        const val MAX_RFC3339_EPOCH_MILLIS = 253_402_300_799_999L
    }
}
