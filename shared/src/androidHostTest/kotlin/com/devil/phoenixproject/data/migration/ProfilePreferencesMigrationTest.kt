package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.data.preferences.SettingsLegacyProfilePreferencesReader
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProfilePreferencesMigrationTest {
    @Test
    fun legacySnapshotCopiesToEveryExistingProfileWithoutChangingActiveProfile() = runTest {
        val fixture = fixture { settings ->
            settings.putString("weight_unit", "KG")
            settings.putFloat("body_weight_kg", 82f)
            settings.putFloat("weight_increment", 2.5f)
            settings.putInt("color_scheme", 3)
            settings.putBoolean("safe_word_calibrated", true)
            settings.putString("safe_word", "phoenix")
            settings.putString(
                "equipment_rack_items_v1",
                """[{"id":"plate","name":"Plate","weightKg":10.0}]""",
            )
        }
        fixture.createProfiles("a", "b")
        fixture.queries.setActiveProfile("b")
        fixture.profiles.refreshProfiles()
        val activeBefore = fixture.profiles.activeProfile.value?.id

        fixture.migration.runRequiredMigrations()

        assertEquals(RequiredMigrationState.Ready, fixture.migration.requiredMigrationState.value)
        assertEquals(activeBefore, fixture.profiles.activeProfile.value?.id)
        assertEquals("b", assertIs<ActiveProfileContext.Ready>(fixture.profiles.activeProfileContext.value).profile.id)
        listOf("a", "b").forEach { profileId ->
            val preferences = fixture.preferenceRepository.get(profileId)
            assertEquals(1, preferences.legacyMigrationVersion)
            assertEquals(82f, preferences.core.value.bodyWeightKg)
            assertEquals(WeightUnit.KG, preferences.core.value.weightUnit)
            assertEquals(2.5f, preferences.core.value.weightIncrement)
            assertEquals(listOf("plate"), preferences.rack.value.items.map { it.id })
            assertEquals(3, preferences.led.value.colorScheme)
            assertTrue(preferences.vbt.value.enabled)
            assertEquals(
                ProfileLocalSafetyPreferences("phoenix", true, false, false),
                fixture.safetyStore.read(profileId),
            )
        }
        assertTrue(fixture.settings.getBoolean("profile_preferences_legacy_migration_complete_v1", false))
    }

    @Test
    fun partialFailureRetriesWithoutOverwritingCompletedRows() = runTest {
        val fixture = fixture()
        fixture.createProfiles("a", "b")
        fixture.safetyStore.failProfileId = "b"

        fixture.migration.runRequiredMigrations()
        assertIs<RequiredMigrationState.Failed>(fixture.migration.requiredMigrationState.value)
        fixture.preferenceRepository.updateCore(
            "a",
            CoreProfilePreferences(bodyWeightKg = 90f),
            300,
        )

        fixture.safetyStore.failProfileId = null
        fixture.migration.retryRequiredMigrations()

        assertEquals(90f, fixture.preferenceRepository.get("a").core.value.bodyWeightKg)
        assertEquals(1, fixture.preferenceRepository.get("a").legacyMigrationVersion)
        assertEquals(1, fixture.preferenceRepository.get("b").legacyMigrationVersion)
        assertEquals(RequiredMigrationState.Ready, fixture.migration.requiredMigrationState.value)
        assertEquals(1, fixture.settings.getInt("migration_repair_version", 0))
    }

    @Test
    fun startupReconciliationDrainsInterruptedCreateBeforeLegacyCopyAndReady() = runTest {
        val fixture = fixture()
        fixture.createProfiles("failed-create")
        fixture.preferenceRepository.insertDefaults("failed-create")
        fixture.queries.enqueueProfileContextRecovery("default", "failed-create", 100)
        fixture.queries.setActiveProfile("failed-create")

        fixture.migration.runRequiredMigrations()

        assertNull(fixture.queries.selectPendingProfileContextRecovery().executeAsOneOrNull())
        assertNull(fixture.queries.getProfileById("failed-create").executeAsOneOrNull())
        assertNull(fixture.queries.selectProfilePreferences("failed-create").executeAsOneOrNull())
        assertEquals("default", fixture.queries.getActiveProfile().executeAsOne().id)
        assertEquals(
            "default",
            assertIs<ActiveProfileContext.Ready>(fixture.profiles.activeProfileContext.value).profile.id,
        )
        assertEquals(RequiredMigrationState.Ready, fixture.migration.requiredMigrationState.value)
    }

    @Test
    fun activeContextStaysSwitchingUntilPreferenceAndLocalSafetyCopyFinish() = runTest {
        val fixture = fixture()
        val copyStarted = CompletableDeferred<Unit>()
        val allowCopyToFinish = CompletableDeferred<Unit>()
        fixture.safetyStore.beforeFirstCopy = {
            copyStarted.complete(Unit)
            allowCopyToFinish.await()
        }

        val migrationJob = launch { fixture.migration.runRequiredMigrations() }
        val readyAwaited = CompletableDeferred<Unit>()
        val awaitJob = launch {
            fixture.migration.awaitRequiredMigrations()
            readyAwaited.complete(Unit)
        }
        copyStarted.await()

        assertIs<ActiveProfileContext.Switching>(fixture.profiles.activeProfileContext.value)
        assertEquals(RequiredMigrationState.Applying, fixture.migration.requiredMigrationState.value)
        assertFalse(readyAwaited.isCompleted)

        allowCopyToFinish.complete(Unit)
        migrationJob.join()
        awaitJob.join()
        assertIs<ActiveProfileContext.Ready>(fixture.profiles.activeProfileContext.value)
        assertTrue(readyAwaited.isCompleted)
    }

    @Test
    fun profilesCreatedAfterMigrationStartWithDefaultsAndDoNotReceiveLegacyValues() = runTest {
        val fixture = fixture { settings -> settings.putFloat("body_weight_kg", 90f) }
        fixture.migration.runRequiredMigrations()

        val created = fixture.profiles.createProfile("New", 1)
        val preferences = fixture.preferenceRepository.get(created.id)

        assertEquals(1, preferences.legacyMigrationVersion)
        assertEquals(CoreProfilePreferences(), preferences.core.value)
    }

    private fun fixture(configure: (MapSettings) -> Unit = {}): Fixture {
        val database = createTestDatabase()
        val settings = MapSettings().also(configure)
        val preferences = SqlDelightProfilePreferencesRepository(database)
        val safetyStore = FaultingProfileLocalSafetyStore(SettingsProfileLocalSafetyStore(settings))
        val gamification = SqlDelightGamificationRepository(database)
        val profiles = SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = preferences,
            profileLocalSafetyStore = safetyStore,
            gamificationRepository = gamification,
        )
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )
        val migration = MigrationManager(
            database = database,
            userProfileRepository = profiles,
            gamificationRepository = gamification,
            settings = settings,
            profilePreferencesRepository = preferences,
            profileLocalSafetyStore = safetyStore,
            legacyProfilePreferencesReader = reader,
        )
        return Fixture(database, settings, preferences, safetyStore, profiles, migration)
    }

    private data class Fixture(
        val database: VitruvianDatabase,
        val settings: MapSettings,
        val preferenceRepository: ProfilePreferencesRepository,
        val safetyStore: FaultingProfileLocalSafetyStore,
        val profiles: UserProfileRepository,
        val migration: MigrationManager,
    ) {
        val queries = database.vitruvianDatabaseQueries

        suspend fun createProfiles(vararg ids: String) {
            ids.forEachIndexed { index, id ->
                queries.insertProfile(id, id, index.toLong(), 100L + index, 0)
            }
            profiles.refreshProfiles()
        }
    }

    private class InjectedSafetyCopyFailure(profileId: String) :
        IllegalStateException("injected safety copy failure for $profileId")

    private class FaultingProfileLocalSafetyStore(
        private val delegate: ProfileLocalSafetyStore,
    ) : ProfileLocalSafetyStore by delegate {
        var failProfileId: String? = null
        var beforeFirstCopy: (suspend () -> Unit)? = null

        override suspend fun copyLegacyToProfiles(
            profileIds: List<String>,
            value: ProfileLocalSafetyPreferences,
        ) {
            beforeFirstCopy?.let { hook ->
                beforeFirstCopy = null
                hook()
            }
            profileIds.forEach { profileId ->
                if (profileId == failProfileId) throw InjectedSafetyCopyFailure(profileId)
                delegate.write(profileId, value)
            }
        }
    }
}
