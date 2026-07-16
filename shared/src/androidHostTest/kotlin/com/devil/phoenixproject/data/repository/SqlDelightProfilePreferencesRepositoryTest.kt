package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightProfilePreferencesRepositoryTest {
    private lateinit var database: VitruvianDatabase
    private lateinit var repository: ProfilePreferencesRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightProfilePreferencesRepository(database)
    }

    @Test
    fun editingCoreOnlyAdvancesCoreMetadata() = runTest {
        insertProfile("a")
        repository.insertDefaults("a")
        val before = repository.get("a")

        repository.updateCore(
            profileId = "a",
            value = before.core.value.copy(bodyWeightKg = 80f),
            now = 200,
        )
        val after = repository.get("a")

        assertEquals(before.core.metadata.localGeneration + 1, after.core.metadata.localGeneration)
        assertEquals(200, after.core.metadata.updatedAt)
        assertTrue(after.core.metadata.dirty)
        assertEquals(before.core.metadata.serverRevision, after.core.metadata.serverRevision)
        assertEquals(before.rack, after.rack)
        assertEquals(before.workout, after.workout)
        assertEquals(before.led, after.led)
        assertEquals(before.vbt, after.vbt)
    }

    @Test
    fun eachDocumentEditOnlyAdvancesItsOwnMetadata() = runTest {
        insertProfile("a")
        repository.insertDefaults("a")

        assertOnlySectionChanged(ProfilePreferenceSectionName.RACK, now = 201) {
            repository.updateRack(
                "a",
                RackPreferences(items = listOf(RackItem(id = "plate", name = "Plate", weightKg = 10f))),
                201,
            )
        }
        assertOnlySectionChanged(ProfilePreferenceSectionName.WORKOUT, now = 202) {
            repository.updateWorkout("a", WorkoutPreferences(stopAtTop = true), 202)
        }
        assertOnlySectionChanged(ProfilePreferenceSectionName.LED, now = 203) {
            repository.updateLed("a", LedPreferences(colorScheme = 4, discoModeUnlocked = true), 203)
        }
        assertOnlySectionChanged(ProfilePreferenceSectionName.VBT, now = 204) {
            repository.updateVbt(
                "a",
                VbtPreferences(enabled = false, velocityLossThresholdPercent = 30),
                204,
            )
        }
    }

    @Test
    fun malformedWorkoutRawSurvivesRackEditAndOnlyValidWorkoutWritesReplaceIt() = runTest {
        insertProfile("a")
        repository.insertDefaults("a")
        val malformed = " { definitely-not-json ] \n"
        database.vitruvianDatabaseQueries.updateWorkoutProfilePreferences(malformed, 10, "a")

        val invalid = repository.get("a")
        assertEquals(WorkoutPreferences(), invalid.workout.value)
        assertEquals(malformed, invalid.workout.raw)
        assertIs<ProfilePreferenceValidity.Invalid>(invalid.workout.validity)

        repository.updateRack(
            "a",
            RackPreferences(items = listOf(RackItem(id = "bench", name = "Bench", weightKg = 12f))),
            20,
        )

        assertEquals(
            malformed,
            database.vitruvianDatabaseQueries.selectProfilePreferences("a")
                .executeAsOne()
                .workout_preferences_json,
        )
        assertEquals(malformed, repository.get("a").workout.raw)

        repository.resetInvalidSection("a", ProfilePreferenceSectionName.WORKOUT, 30)
        val reset = repository.get("a")
        assertIs<ProfilePreferenceValidity.Valid>(reset.workout.validity)
        assertEquals(WorkoutPreferences(), reset.workout.value)
        assertNotEquals(malformed, reset.workout.raw)
        assertEquals(
            invalid.workout.metadata.localGeneration + 1,
            reset.workout.metadata.localGeneration,
        )
        assertEquals(invalid.workout.metadata.serverRevision, reset.workout.metadata.serverRevision)

        database.vitruvianDatabaseQueries.updateWorkoutProfilePreferences(malformed, 40, "a")
        val edited = WorkoutPreferences(stopAtTop = true)
        repository.updateWorkout("a", edited, 50)
        assertEquals(edited, repository.get("a").workout.value)
        assertEquals(
            ProfilePreferencesCodec.encodeWorkout(edited),
            database.vitruvianDatabaseQueries.selectProfilePreferences("a")
                .executeAsOne()
                .workout_preferences_json,
        )
    }

    @Test
    fun invalidTypedUpdatesAreRejectedWithoutChangingStoredValues() = runTest {
        insertProfile("a")
        repository.insertDefaults("a")
        val before = repository.get("a")

        assertFailsWith<IllegalArgumentException> {
            repository.updateCore(
                "a",
                CoreProfilePreferences(
                    bodyWeightKg = 500f,
                    weightUnit = WeightUnit.KG,
                    weightIncrement = 2.5f,
                ),
                200,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repository.updateWorkout(
                "a",
                WorkoutPreferences(autoStartCountdownSeconds = 99),
                200,
            )
        }

        assertEquals(before, repository.get("a"))
    }

    @Test
    fun observeMapsSubsequentDatabaseUpdates() = runTest {
        insertProfile("a")
        repository.insertDefaults("a")
        val initialGeneration = repository.get("a").core.metadata.localGeneration

        repository.updateCore("a", CoreProfilePreferences(bodyWeightKg = 80f), 222)

        val observed = repository.observe("a")
            .dropWhile { it.core.metadata.localGeneration == initialGeneration }
            .first()
        assertEquals(80f, observed.core.value.bodyWeightKg)
        assertEquals(222, observed.core.metadata.updatedAt)
    }

    @Test
    fun localSafetyKeysAreProfilePrefixedAndLegacyCopyUsesImmutableSnapshot() = runTest {
        val settings = MapSettings()
        val store = SettingsProfileLocalSafetyStore(settings)
        val a = ProfileLocalSafetyPreferences("red", true, true, false)
        val b = ProfileLocalSafetyPreferences("blue", false, false, true)

        store.write("a", a)
        store.write("b", b)

        assertEquals(a, store.read("a"))
        assertEquals(b, store.read("b"))
        assertEquals("red", settings.getStringOrNull("profile_a_safe_word"))
        assertEquals("blue", settings.getStringOrNull("profile_b_safe_word"))
        assertNull(settings.getStringOrNull("safe_word"))

        val legacySnapshot = ProfileLocalSafetyPreferences("copied", true, false, true)
        store.copyLegacyToProfiles(listOf("a", "b"), legacySnapshot)
        assertEquals(legacySnapshot, store.read("a"))
        assertEquals(legacySnapshot, store.read("b"))
    }

    @Test
    fun partialLocalSafetyWriteRollsBackEveryProfileKey() {
        val settings = OneShotFailingSettings()
        val store = SettingsProfileLocalSafetyStore(settings)
        val previous = ProfileLocalSafetyPreferences("original", true, true, false)
        store.write("a", previous)
        settings.failNextBooleanWrite = true

        assertFailsWith<InjectedSettingsFailure> {
            store.write("a", ProfileLocalSafetyPreferences("replacement", false, false, true))
        }

        assertEquals(previous, store.read("a"))
    }

    @Test
    fun deleteRemovesOnlyTheSelectedProfilesSafetyKeys() {
        val settings = MapSettings()
        val store = SettingsProfileLocalSafetyStore(settings)
        val a = ProfileLocalSafetyPreferences("red", true, true, true)
        val b = ProfileLocalSafetyPreferences("blue", true, true, true)
        store.write("a", a)
        store.write("b", b)

        store.delete("a")

        assertEquals(ProfileLocalSafetyPreferences(), store.read("a"))
        assertEquals(b, store.read("b"))
    }

    private fun insertProfile(id: String) {
        database.vitruvianDatabaseQueries.insertProfile(id, id, 0, 1, 0)
    }

    private suspend fun assertOnlySectionChanged(
        section: ProfilePreferenceSectionName,
        now: Long,
        update: suspend () -> Unit,
    ) {
        val before = repository.get("a")
        update()
        val after = repository.get("a")

        fun assertMetadataChange(
            expectedSection: ProfilePreferenceSectionName,
            beforeGeneration: Long,
            beforeRevision: Long,
            afterGeneration: Long,
            afterRevision: Long,
            afterUpdatedAt: Long,
            afterDirty: Boolean,
        ) {
            if (section == expectedSection) {
                assertEquals(beforeGeneration + 1, afterGeneration)
                assertEquals(now, afterUpdatedAt)
                assertTrue(afterDirty)
            } else {
                assertEquals(beforeGeneration, afterGeneration)
            }
            assertEquals(beforeRevision, afterRevision)
        }

        assertMetadataChange(
            ProfilePreferenceSectionName.CORE,
            before.core.metadata.localGeneration,
            before.core.metadata.serverRevision,
            after.core.metadata.localGeneration,
            after.core.metadata.serverRevision,
            after.core.metadata.updatedAt,
            after.core.metadata.dirty,
        )
        assertMetadataChange(
            ProfilePreferenceSectionName.RACK,
            before.rack.metadata.localGeneration,
            before.rack.metadata.serverRevision,
            after.rack.metadata.localGeneration,
            after.rack.metadata.serverRevision,
            after.rack.metadata.updatedAt,
            after.rack.metadata.dirty,
        )
        assertMetadataChange(
            ProfilePreferenceSectionName.WORKOUT,
            before.workout.metadata.localGeneration,
            before.workout.metadata.serverRevision,
            after.workout.metadata.localGeneration,
            after.workout.metadata.serverRevision,
            after.workout.metadata.updatedAt,
            after.workout.metadata.dirty,
        )
        assertMetadataChange(
            ProfilePreferenceSectionName.LED,
            before.led.metadata.localGeneration,
            before.led.metadata.serverRevision,
            after.led.metadata.localGeneration,
            after.led.metadata.serverRevision,
            after.led.metadata.updatedAt,
            after.led.metadata.dirty,
        )
        assertMetadataChange(
            ProfilePreferenceSectionName.VBT,
            before.vbt.metadata.localGeneration,
            before.vbt.metadata.serverRevision,
            after.vbt.metadata.localGeneration,
            after.vbt.metadata.serverRevision,
            after.vbt.metadata.updatedAt,
            after.vbt.metadata.dirty,
        )

        if (section != ProfilePreferenceSectionName.CORE) assertEquals(before.core, after.core)
        if (section != ProfilePreferenceSectionName.RACK) assertEquals(before.rack, after.rack)
        if (section != ProfilePreferenceSectionName.WORKOUT) assertEquals(before.workout, after.workout)
        if (section != ProfilePreferenceSectionName.LED) assertEquals(before.led, after.led)
        if (section != ProfilePreferenceSectionName.VBT) assertEquals(before.vbt, after.vbt)
    }

    private class InjectedSettingsFailure : IllegalStateException("injected Settings failure")

    private class OneShotFailingSettings(
        private val delegate: Settings = MapSettings(),
    ) : Settings by delegate {
        var failNextBooleanWrite = false

        override fun putBoolean(key: String, value: Boolean) {
            if (failNextBooleanWrite) {
                failNextBooleanWrite = false
                throw InjectedSettingsFailure()
            }
            delegate.putBoolean(key, value)
        }
    }
}
