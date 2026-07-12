package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightUserProfileRepositoryTest {
    private lateinit var database: VitruvianDatabase
    private lateinit var preferenceStore: FaultingProfilePreferencesRepository
    private lateinit var settings: MapSettings
    private lateinit var safetyStore: FaultingProfileLocalSafetyStore
    private lateinit var repository: SqlDelightUserProfileRepository

    @Before
    fun setup() {
        database = createDatabase()
        preferenceStore = FaultingProfilePreferencesRepository(
            SqlDelightProfilePreferencesRepository(database),
        )
        settings = MapSettings()
        safetyStore = FaultingProfileLocalSafetyStore(
            SettingsProfileLocalSafetyStore(settings),
        )
        repository = createRepository(database, preferenceStore, safetyStore)
    }

    @Test
    fun constructorKeepsContextSwitchingUntilExplicitReconciliation() = runTest {
        assertEquals("default", repository.activeProfile.value?.id)
        assertNull(
            database.vitruvianDatabaseQueries.selectProfilePreferences("default")
                .executeAsOneOrNull(),
        )
        val switching = assertIs<ActiveProfileContext.Switching>(repository.activeProfileContext.value)
        assertEquals("default", switching.targetProfileId)

        preferenceStore.seedMissingProfiles()
        repository.reconcileActiveProfileContext()

        val ready = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        assertEquals("default", ready.profile.id)
        assertEquals("default", ready.preferences.profileId)
        assertEquals(ProfileLocalSafetyPreferences(), ready.localSafety)
    }

    @Test
    fun createProfileAtomicallyAddsIdentityAndDefaultsWithoutActivatingIt() = runTest {
        ready()

        val created = repository.createProfile("Alex", 2)

        assertFalse(created.isActive)
        assertEquals("default", repository.activeProfile.value?.id)
        assertNotNull(database.vitruvianDatabaseQueries.getProfileById(created.id).executeAsOneOrNull())
        assertNotNull(
            database.vitruvianDatabaseQueries.selectProfilePreferences(created.id)
                .executeAsOneOrNull(),
        )
        assertEquals(
            1L,
            database.vitruvianDatabaseQueries.selectProfilePreferences(created.id)
                .executeAsOne()
                .legacy_migration_version,
        )
        assertEquals("default", assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id)
    }

    @Test
    fun seededExistingProfilesRemainEligibleForLegacyMigration() = runTest {
        assertNull(
            database.vitruvianDatabaseQueries.selectProfilePreferences("default")
                .executeAsOneOrNull(),
        )

        preferenceStore.seedMissingProfiles()

        assertEquals(
            0L,
            database.vitruvianDatabaseQueries.selectProfilePreferences("default")
                .executeAsOne()
                .legacy_migration_version,
        )
    }

    @Test
    fun createAndActivateProfilePublishesMatchingCompleteContext() = runTest {
        ready()

        val created = repository.createAndActivateProfile("  Alex  ", 2)

        assertEquals("Alex", created.name)
        assertTrue(created.isActive)
        assertEquals(created.id, database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        val context = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        assertEquals(created.id, context.profile.id)
        assertEquals(created.id, context.preferences.profileId)
        assertEquals(safetyStore.read(created.id), context.localSafety)
        assertNull(database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    }

    @Test
    fun switchingNeverEmitsMixedProfileContext() = runTest {
        ready()
        val profileA = repository.createAndActivateProfile("A", 1)
        val profileB = repository.createAndActivateProfile("B", 2)
        val observed = mutableListOf<ActiveProfileContext>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeProfileContext.drop(1).take(2).toList(observed)
        }

        repository.setActiveProfile(profileA.id)

        val switching = assertIs<ActiveProfileContext.Switching>(observed[0])
        assertEquals(profileA.id, switching.targetProfileId)
        val ready = assertIs<ActiveProfileContext.Ready>(observed[1])
        assertEquals(profileA.id, ready.profile.id)
        assertEquals(profileA.id, ready.preferences.profileId)
        assertNotEquals(profileB.id, ready.profile.id)
        job.cancel()
    }

    @Test
    fun profilesKeepIndependentValuesAcrossEverySectionAndLocalSafety() = runTest {
        ready()
        val profileA = repository.createAndActivateProfile("A", 1)
        val rackA = RackPreferences(items = listOf(RackItem(id = "a", name = "A", weightKg = 5f)))
        val workoutA = WorkoutPreferences(stopAtTop = true)
        val ledA = LedPreferences(colorScheme = 2, discoModeUnlocked = true)
        val vbtA = VbtPreferences(enabled = false, velocityLossThresholdPercent = 30)
        val safetyA = ProfileLocalSafetyPreferences("red", true, true, false)
        repository.updateCore(profileA.id, CoreProfilePreferences(bodyWeightKg = 80f))
        repository.updateRack(profileA.id, rackA)
        repository.updateWorkout(profileA.id, workoutA)
        repository.updateLed(profileA.id, ledA)
        repository.updateVbt(profileA.id, vbtA)
        repository.updateLocalSafety(profileA.id, safetyA)

        val profileB = repository.createAndActivateProfile("B", 2)
        val rackB = RackPreferences(items = listOf(RackItem(id = "b", name = "B", weightKg = 10f)))
        val workoutB = WorkoutPreferences(beepsEnabled = false)
        val ledB = LedPreferences(colorScheme = 7)
        val vbtB = VbtPreferences(enabled = true, velocityLossThresholdPercent = 40)
        val safetyB = ProfileLocalSafetyPreferences("blue", false, false, true)
        repository.updateCore(profileB.id, CoreProfilePreferences(bodyWeightKg = 100f))
        repository.updateRack(profileB.id, rackB)
        repository.updateWorkout(profileB.id, workoutB)
        repository.updateLed(profileB.id, ledB)
        repository.updateVbt(profileB.id, vbtB)
        repository.updateLocalSafety(profileB.id, safetyB)

        repository.setActiveProfile(profileA.id)
        val readyA = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        assertEquals(80f, readyA.preferences.core.value.bodyWeightKg)
        assertEquals(rackA, readyA.preferences.rack.value)
        assertEquals(workoutA, readyA.preferences.workout.value)
        assertEquals(ledA, readyA.preferences.led.value)
        assertEquals(vbtA, readyA.preferences.vbt.value)
        assertEquals(safetyA, readyA.localSafety)

        repository.setActiveProfile(profileB.id)
        val readyB = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        assertEquals(100f, readyB.preferences.core.value.bodyWeightKg)
        assertEquals(rackB, readyB.preferences.rack.value)
        assertEquals(workoutB, readyB.preferences.workout.value)
        assertEquals(ledB, readyB.preferences.led.value)
        assertEquals(vbtB, readyB.preferences.vbt.value)
        assertEquals(safetyB, readyB.localSafety)
    }

    @Test
    fun staleAndSwitchingMutationsAreRejectedWithoutTouchingPreferences() = runTest {
        ready()
        val profileA = repository.createAndActivateProfile("A", 1)
        val profileB = repository.createAndActivateProfile("B", 2)
        val aBefore = preferenceStore.get(profileA.id)

        assertFailsWith<StaleProfileContextException> {
            repository.updateCore(profileA.id, CoreProfilePreferences(bodyWeightKg = 80f))
        }
        assertEquals(aBefore, preferenceStore.get(profileA.id))

        val switchingRepository = createRepository(
            database,
            preferenceStore,
            safetyStore,
        )
        assertFailsWith<ProfileContextUnavailableException> {
            switchingRepository.updateCore(profileB.id, CoreProfilePreferences(bodyWeightKg = 90f))
        }
    }

    @Test
    fun failedTypedUpdateLeavesReadyContextOnTheSameProfile() = runTest {
        ready()
        val activeId = repository.activeProfile.value!!.id
        val before = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)

        assertFailsWith<IllegalArgumentException> {
            repository.updateWorkout(
                activeId,
                WorkoutPreferences(autoStartCountdownSeconds = 99),
            )
        }

        assertEquals(before, repository.activeProfileContext.value)
    }

    @Test
    fun failedCreateAfterActivationRestoresPreviousDatabaseAndReadyContext() = runTest {
        ready()
        val previous = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        val profileIdsBefore = repository.allProfiles.value.map { it.id }.toSet()
        val preferenceIdsBefore = preferenceIds()
        preferenceStore.failNextGet = true

        assertFails { repository.createAndActivateProfile("Cannot publish", 2) }

        assertEquals(previous.profile.id, database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertEquals(previous, repository.activeProfileContext.value)
        assertEquals(profileIdsBefore, repository.allProfiles.value.map { it.id }.toSet())
        assertEquals(preferenceIdsBefore, preferenceIds())
        assertNull(database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    }

    @Test
    fun failedSwitchAfterDatabaseActivationRestoresPreviousDatabaseAndReadyContext() = runTest {
        ready()
        val target = repository.createProfile("Target", 2)
        val previous = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
        preferenceStore.failNextGetFor = target.id

        assertFails { repository.setActiveProfile(target.id) }

        assertEquals(previous.profile.id, database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertEquals(previous, repository.activeProfileContext.value)
        assertNull(database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    }

    @Test
    fun rollbackFailureStaysJournaledUntilReconciliationRestoresDatabaseAndReady() = runTest {
        val fixture = createFaultingFixture()
        fixture.preferenceStore.seedMissingProfiles()
        fixture.repository.reconcileActiveProfileContext()
        val target = fixture.repository.createProfile("Target", 2)
        val previous = assertIs<ActiveProfileContext.Ready>(fixture.repository.activeProfileContext.value)
        fixture.preferenceStore.failNextGetFor = target.id
        fixture.transitionFaults.failSetActiveAfterMatches = 1

        assertFailsWith<ProfileContextRecoveryException> {
            fixture.repository.setActiveProfile(target.id)
        }
        assertNotNull(
            fixture.database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )

        fixture.repository.reconcileActiveProfileContext()

        assertNull(
            fixture.database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )
        assertEquals(
            previous.profile.id,
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
        assertEquals(
            previous.profile.id,
            assertIs<ActiveProfileContext.Ready>(fixture.repository.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun failedCreateCompensationRetriesFromJournalWithoutLeavingRows() = runTest {
        val fixture = createFaultingFixture()
        fixture.preferenceStore.seedMissingProfiles()
        fixture.repository.reconcileActiveProfileContext()
        val profileIdsBefore = fixture.repository.allProfiles.value.map { it.id }.toSet()
        fixture.preferenceStore.failNextGet = true
        fixture.transitionFaults.failNextPreferenceDelete = true

        assertFailsWith<ProfileContextRecoveryException> {
            fixture.repository.createAndActivateProfile("Failed create", 2)
        }
        val pending = fixture.database.vitruvianDatabaseQueries
            .selectPendingProfileContextRecovery()
            .executeAsOne()
        val failedId = assertNotNull(pending.created_profile_id)

        fixture.repository.reconcileActiveProfileContext()

        assertNull(
            fixture.database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )
        assertNull(fixture.database.vitruvianDatabaseQueries.getProfileById(failedId).executeAsOneOrNull())
        assertNull(
            fixture.database.vitruvianDatabaseQueries.selectProfilePreferences(failedId)
                .executeAsOneOrNull(),
        )
        assertEquals(profileIdsBefore, fixture.repository.allProfiles.value.map { it.id }.toSet())
    }

    @Test
    fun failedReadyPublicationKeepsRecoveryJournalUntilACompleteRetry() = runTest {
        ready()
        val target = repository.createProfile("Target", 2)
        database.transaction {
            database.vitruvianDatabaseQueries.enqueueProfileContextRecovery("default", null, 100)
            database.vitruvianDatabaseQueries.setActiveProfile(target.id)
        }
        preferenceStore.failNextGetFor = "default"

        assertFailsWith<ProfileContextRecoveryException> {
            repository.reconcileActiveProfileContext()
        }

        assertEquals("default", database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertNotNull(
            database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )
        assertIs<ActiveProfileContext.Switching>(repository.activeProfileContext.value)

        repository.reconcileActiveProfileContext()

        assertNull(database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery().executeAsOneOrNull())
        assertEquals(
            "default",
            assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun startupRecoveryDrainsJournalButRetainsSwitchingUntilOrdinaryReconciliation() = runTest {
        ready()
        val target = repository.createProfile("Target", 2)
        database.transaction {
            database.vitruvianDatabaseQueries.enqueueProfileContextRecovery("default", null, 100)
            database.vitruvianDatabaseQueries.setActiveProfile(target.id)
        }

        repository.recoverPendingProfileTransitionForStartup()

        assertNull(database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery().executeAsOneOrNull())
        assertEquals("default", database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        val switching = assertIs<ActiveProfileContext.Switching>(repository.activeProfileContext.value)
        assertEquals("default", switching.targetProfileId)

        repository.reconcileActiveProfileContext()
        assertEquals("default", assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id)
    }

    @Test
    fun pendingLocalCleanupDequeuesOnlyAfterEverySafetyKeyIsRemoved() = runTest {
        val value = ProfileLocalSafetyPreferences("do-not-log", true, true, true)
        safetyStore.write("source", value)
        database.vitruvianDatabaseQueries.enqueueProfileLocalCleanup("source", 100)
        safetyStore.failDeletes = true

        repository.retryPendingLocalCleanup()
        assertEquals(
            listOf("source"),
            database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup()
                .executeAsList()
                .map { it.profile_id },
        )
        assertEquals("do-not-log", settings.getStringOrNull("profile_source_safe_word"))

        safetyStore.failDeletes = false
        repository.retryPendingLocalCleanup()
        assertTrue(
            database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup()
                .executeAsList()
                .isEmpty(),
        )
        assertNull(settings.getStringOrNull("profile_source_safe_word"))
    }

    @Test
    fun partialSettingsDeletionRemainsQueuedUntilRetryRemovesAllKeys() = runTest {
        val partialSettings = OneShotRemoveFailingSettings(failAtRemove = 2)
        val partialSafetyStore = SettingsProfileLocalSafetyStore(partialSettings)
        val partialRepository = createRepository(database, preferenceStore, partialSafetyStore)
        val value = ProfileLocalSafetyPreferences("partial", true, true, true)
        partialSafetyStore.write("source", value)
        database.vitruvianDatabaseQueries.enqueueProfileLocalCleanup("source", 100)

        partialRepository.retryPendingLocalCleanup()

        assertNotNull(
            database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup()
                .executeAsOneOrNull(),
        )

        partialRepository.retryPendingLocalCleanup()

        assertNull(
            database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup()
                .executeAsOneOrNull(),
        )
        assertEquals(ProfileLocalSafetyPreferences(), partialSafetyStore.read("source"))
    }

    @Test
    fun deleteProfileRemovesPreferencesAndPublishesMatchingDefaultContext() = runTest {
        ready()
        val created = repository.createAndActivateProfile("Jordan", 1)

        val deleteDefault = repository.deleteProfile("default")
        val deleteCreated = repository.deleteProfile(created.id)

        assertFalse(deleteDefault)
        assertTrue(deleteCreated)
        assertEquals("default", database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertEquals("default", assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id)
        assertNull(
            database.vitruvianDatabaseQueries.selectProfilePreferences(created.id)
                .executeAsOneOrNull(),
        )
    }

    @Test
    fun deleteRecoversACompleteReadyContextAfterPostCommitPublicationFailure() = runTest {
        ready()
        val created = repository.createAndActivateProfile("Jordan", 1)
        preferenceStore.failNextGetFor = "default"

        assertTrue(repository.deleteProfile(created.id))

        assertNull(database.vitruvianDatabaseQueries.getProfileById(created.id).executeAsOneOrNull())
        assertEquals("default", database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertEquals(
            "default",
            assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun fakeRepositoryMatchesSectionIsolationSwitchingAndMigrationSemantics() = runTest {
        val fake = FakeUserProfileRepository()
        fake.ensureDefaultProfile()
        assertEquals(0, fake.observePreferences("default").first().legacyMigrationVersion)
        fake.reconcileActiveProfileContext()
        val profileA = fake.createAndActivateProfile("A", 1)
        val rackA = RackPreferences(items = listOf(RackItem(id = "a", name = "A", weightKg = 5f)))
        val workoutA = WorkoutPreferences(stopAtTop = true)
        val ledA = LedPreferences(colorScheme = 3, discoModeUnlocked = true)
        val vbtA = VbtPreferences(enabled = false, velocityLossThresholdPercent = 30)
        val safetyA = ProfileLocalSafetyPreferences("red", true, true, false)
        fake.updateCore(profileA.id, CoreProfilePreferences(bodyWeightKg = 80f))
        fake.updateRack(profileA.id, rackA)
        fake.updateWorkout(profileA.id, workoutA)
        fake.updateLed(profileA.id, ledA)
        fake.updateVbt(profileA.id, vbtA)
        fake.updateLocalSafety(profileA.id, safetyA)

        val observed = mutableListOf<ActiveProfileContext>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.activeProfileContext.drop(1).take(2).toList(observed)
        }
        val profileB = fake.createAndActivateProfile("B", 2)
        job.cancel()
        assertEquals(profileB.id, assertIs<ActiveProfileContext.Switching>(observed[0]).targetProfileId)
        assertEquals(profileB.id, assertIs<ActiveProfileContext.Ready>(observed[1]).profile.id)
        assertEquals(1, fake.observePreferences(profileB.id).first().legacyMigrationVersion)

        fake.setActiveProfile(profileA.id)
        val readyA = assertIs<ActiveProfileContext.Ready>(fake.activeProfileContext.value)
        assertEquals(80f, readyA.preferences.core.value.bodyWeightKg)
        assertEquals(rackA, readyA.preferences.rack.value)
        assertEquals(workoutA, readyA.preferences.workout.value)
        assertEquals(ledA, readyA.preferences.led.value)
        assertEquals(vbtA, readyA.preferences.vbt.value)
        assertEquals(safetyA, readyA.localSafety)

        assertFailsWith<IllegalStateException> {
            fake.observePreferences("missing").first()
        }
    }

    @Test
    fun fakeRecoveryAndCleanupHooksRetainSwitchingUntilReconciliation() = runTest {
        val fake = FakeUserProfileRepository()
        fake.ensureDefaultProfile()
        fake.reconcileActiveProfileContext()
        fake.updateLocalSafety(
            "default",
            ProfileLocalSafetyPreferences("phrase", true, true, true),
        )
        fake.pendingLocalCleanupProfileIds += "default"

        fake.retryPendingLocalCleanup()
        assertTrue(fake.pendingLocalCleanupProfileIds.isEmpty())
        fake.setActiveProfile("default")
        assertEquals(
            ProfileLocalSafetyPreferences(),
            assertIs<ActiveProfileContext.Ready>(fake.activeProfileContext.value).localSafety,
        )

        fake.recoverPendingProfileTransitionForStartup()
        assertIs<ActiveProfileContext.Switching>(fake.activeProfileContext.value)
        fake.reconcileActiveProfileContext()
        assertIs<ActiveProfileContext.Ready>(fake.activeProfileContext.value)
    }

    @Test
    fun deleteProfileRecomputesMergedGamificationStatsWithoutDuplicateTargetRows() = runTest {
        ready()
        val created = repository.createProfile("Jordan", 1)
        val gamificationRepository = SqlDelightGamificationRepository(database)

        insertWorkoutSession("session-default", 10, 20.0, "default")
        insertWorkoutSession("session-created", 12, 25.0, created.id)
        gamificationRepository.updateStats("default")
        gamificationRepository.updateStats(created.id)
        assertEquals(2, database.vitruvianDatabaseQueries.selectGamificationStatsSync().executeAsList().size)

        assertTrue(repository.deleteProfile(created.id))

        val remainingRows = database.vitruvianDatabaseQueries
            .selectGamificationStatsSync()
            .executeAsList()
            .filter { it.profile_id == "default" }
        assertEquals(1, remainingRows.size)
        val merged = database.vitruvianDatabaseQueries
            .selectGamificationStats("default")
            .executeAsOneOrNull()
        assertNotNull(merged)
        assertEquals(2, merged.totalWorkouts.toInt())
        assertEquals(22, merged.totalReps.toInt())
    }

    private suspend fun ready() {
        preferenceStore.seedMissingProfiles()
        repository.reconcileActiveProfileContext()
    }

    private fun preferenceIds(): Set<String> = database.vitruvianDatabaseQueries
        .selectAllProfilePreferences()
        .executeAsList()
        .map { it.profile_id }
        .toSet()

    private fun createDatabase(driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)): VitruvianDatabase {
        VitruvianDatabase.Schema.create(driver)
        return VitruvianDatabase(driver)
    }

    private fun createRepository(
        database: VitruvianDatabase,
        preferences: ProfilePreferencesRepository,
        safety: ProfileLocalSafetyStore,
    ) = SqlDelightUserProfileRepository(
        database = database,
        profilePreferencesRepository = preferences,
        profileLocalSafetyStore = safety,
        gamificationRepository = SqlDelightGamificationRepository(database),
    )

    private fun createFaultingFixture(): FaultingFixture {
        val transitionFaults = TransitionFaults()
        val driver = FaultInjectingSqlDriver(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
            transitionFaults,
        )
        val fixtureDatabase = createDatabase(driver)
        val fixturePreferences = FaultingProfilePreferencesRepository(
            SqlDelightProfilePreferencesRepository(fixtureDatabase),
        )
        val fixtureSafety = FaultingProfileLocalSafetyStore(
            SettingsProfileLocalSafetyStore(MapSettings()),
        )
        return FaultingFixture(
            database = fixtureDatabase,
            preferenceStore = fixturePreferences,
            repository = createRepository(fixtureDatabase, fixturePreferences, fixtureSafety),
            transitionFaults = transitionFaults,
        )
    }

    private fun insertWorkoutSession(
        id: String,
        totalReps: Long,
        weightPerCableKg: Double,
        profileId: String,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 1_000_000L,
            mode = "OldSchool",
            targetReps = totalReps,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0L,
            workingReps = totalReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            routineSessionId = null,
            routineName = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
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
            routineId = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }

    private data class FaultingFixture(
        val database: VitruvianDatabase,
        val preferenceStore: FaultingProfilePreferencesRepository,
        val repository: SqlDelightUserProfileRepository,
        val transitionFaults: TransitionFaults,
    )

    private class FaultingProfilePreferencesRepository(
        private val delegate: ProfilePreferencesRepository,
    ) : ProfilePreferencesRepository by delegate {
        var failNextGet = false
        var failNextGetFor: String? = null

        override suspend fun get(profileId: String) = when {
            failNextGet -> {
                failNextGet = false
                throw InjectedTransitionFailure()
            }

            failNextGetFor == profileId -> {
                failNextGetFor = null
                throw InjectedTransitionFailure()
            }

            else -> delegate.get(profileId)
        }
    }

    private class FaultingProfileLocalSafetyStore(
        private val delegate: ProfileLocalSafetyStore,
    ) : ProfileLocalSafetyStore by delegate {
        var failDeletes = false

        override fun delete(profileId: String) {
            if (failDeletes) throw InjectedTransitionFailure()
            delegate.delete(profileId)
        }
    }

    private data class TransitionFaults(
        var failSetActiveAfterMatches: Int? = null,
        var failNextPreferenceDelete: Boolean = false,
    )

    private class FaultInjectingSqlDriver(
        private val delegate: SqlDriver,
        private val faults: TransitionFaults,
    ) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (identifier == SET_ACTIVE_PROFILE_IDENTIFIER) {
                faults.failSetActiveAfterMatches?.let { remaining ->
                    if (remaining == 0) {
                        faults.failSetActiveAfterMatches = null
                        throw InjectedTransitionFailure()
                    }
                    faults.failSetActiveAfterMatches = remaining - 1
                }
            }
            if (identifier == DELETE_PROFILE_PREFERENCES_IDENTIFIER && faults.failNextPreferenceDelete) {
                faults.failNextPreferenceDelete = false
                throw InjectedTransitionFailure()
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        private companion object {
            const val SET_ACTIVE_PROFILE_IDENTIFIER = 373_348_112
            const val DELETE_PROFILE_PREFERENCES_IDENTIFIER = 1_067_301_929
        }
    }

    private class InjectedTransitionFailure : IllegalStateException("injected transition failure")

    private class OneShotRemoveFailingSettings(
        private val failAtRemove: Int,
        private val delegate: Settings = MapSettings(),
    ) : Settings by delegate {
        private var removeCount = 0
        private var failed = false

        override fun remove(key: String) {
            removeCount += 1
            if (!failed && removeCount == failAtRemove) {
                failed = true
                throw InjectedTransitionFailure()
            }
            delegate.remove(key)
        }
    }
}
