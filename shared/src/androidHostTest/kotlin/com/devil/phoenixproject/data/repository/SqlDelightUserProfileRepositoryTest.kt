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
import com.devil.phoenixproject.domain.model.UserProfilePreferences
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightUserProfileRepositoryTest {
    private lateinit var sqlDriver: SqlDriver
    private lateinit var database: VitruvianDatabase
    private lateinit var preferenceStore: FaultingProfilePreferencesRepository
    private lateinit var settings: MapSettings
    private lateinit var safetyStore: FaultingProfileLocalSafetyStore
    private lateinit var repository: SqlDelightUserProfileRepository

    @Before
    fun setup() {
        sqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        database = createDatabase(sqlDriver)
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
    fun deletionPolicyCoversEveryDirectProfileOwnedSqliteTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createDatabase(driver)
        val directProfileTables = mutableSetOf<String>()
        val tableNames = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                while (cursor.next().value) cursor.getString(0)?.let(tableNames::add)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        tableNames.forEach { table ->
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA table_info(\"$table\")",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        if (cursor.getString(1) in setOf("profile_id", "profileId")) {
                            directProfileTables += table
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
        }

        assertEquals(
            directProfileTables,
            ProfileDeletionMergePolicy.directProfileOwnedTables,
            "Every table with an exact profile_id/profileId column needs an explicit deletion policy",
        )
        assertTrue("ExerciseMvt" in directProfileTables)
        assertTrue("RoutineGroup" in directProfileTables)
        assertTrue("VelocityOneRepMaxEstimate" in directProfileTables)
        assertTrue("PendingProfileLocalCleanup" in directProfileTables)
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
    fun failedCreateIdentityDeleteRollsBackEarlierCompensationAndRetries() = runTest {
        val fixture = createFaultingFixture()
        fixture.preferenceStore.seedMissingProfiles()
        fixture.repository.reconcileActiveProfileContext()
        val profileIdsBefore = fixture.repository.allProfiles.value.map { it.id }.toSet()
        val activeIdBefore = fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id
        fixture.preferenceStore.failNextGet = true
        fixture.transitionFaults.failNextProfileDelete = true

        assertFailsWith<ProfileContextRecoveryException> {
            fixture.repository.createAndActivateProfile("Failed create", 2)
        }
        val pending = fixture.database.vitruvianDatabaseQueries
            .selectPendingProfileContextRecovery()
            .executeAsOne()
        val failedId = assertNotNull(pending.created_profile_id)
        assertEquals(
            failedId,
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
        assertNotNull(
            fixture.database.vitruvianDatabaseQueries.getProfileById(failedId)
                .executeAsOneOrNull(),
        )
        assertNotNull(
            fixture.database.vitruvianDatabaseQueries.selectProfilePreferences(failedId)
                .executeAsOneOrNull(),
        )

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
        assertEquals(
            activeIdBefore,
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
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
    fun failedJournalClearRestoresSwitchingUntilRetryPublishesReadyAndDrainsJournal() = runTest {
        val fixture = createFaultingFixture()
        fixture.preferenceStore.seedMissingProfiles()
        fixture.repository.reconcileActiveProfileContext()
        val target = fixture.repository.createProfile("Target", 2)
        fixture.database.transaction {
            fixture.database.vitruvianDatabaseQueries.enqueueProfileContextRecovery(
                "default",
                null,
                100,
            )
            fixture.database.vitruvianDatabaseQueries.setActiveProfile(target.id)
        }
        fixture.transitionFaults.failNextJournalClear = true

        assertFailsWith<ProfileContextRecoveryException> {
            fixture.repository.reconcileActiveProfileContext()
        }

        assertEquals(
            "default",
            fixture.database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
        )
        assertNotNull(
            fixture.database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )
        val switching = assertIs<ActiveProfileContext.Switching>(
            fixture.repository.activeProfileContext.value,
        )
        assertEquals("default", switching.targetProfileId)

        fixture.repository.reconcileActiveProfileContext()

        assertNull(
            fixture.database.vitruvianDatabaseQueries.selectPendingProfileContextRecovery()
                .executeAsOneOrNull(),
        )
        val ready = assertIs<ActiveProfileContext.Ready>(
            fixture.repository.activeProfileContext.value,
        )
        assertEquals("default", ready.profile.id)
        assertEquals("default", ready.preferences.profileId)
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
    fun deleteActiveProfileRejectsAStaleExpectedIdWithoutMutation() = runTest {
        ready()
        val stale = repository.createAndActivateProfile("Stale", 1)
        val current = repository.createAndActivateProfile("Current", 2)

        assertFailsWith<StaleProfileContextException> {
            repository.deleteActiveProfile(stale.id)
        }

        assertNotNull(database.vitruvianDatabaseQueries.getProfileById(stale.id).executeAsOneOrNull())
        assertNotNull(database.vitruvianDatabaseQueries.getProfileById(current.id).executeAsOneOrNull())
        assertEquals(
            current.id,
            assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun deleteActiveProfileRacingAQueuedSwitchReassignsOnlyToDefault() = runTest {
        val deletionEntered = CountDownLatch(1)
        val releaseDeletion = CountDownLatch(1)
        repository = createRepository(
            database,
            preferenceStore,
            safetyStore,
            beforeProfileDeletionCommit = {
                deletionEntered.countDown()
                check(releaseDeletion.await(5, TimeUnit.SECONDS))
            },
        )
        ready()
        val source = repository.createAndActivateProfile("Source", 1)
        val next = repository.createProfile("Next", 2)
        insertWorkoutSession("atomic-delete-session", 5, 20.0, source.id)

        val deletion = async(Dispatchers.Default) {
            repository.deleteActiveProfile(source.id)
        }
        assertTrue(deletionEntered.await(5, TimeUnit.SECONDS))
        val switch = async(Dispatchers.Default) {
            repository.setActiveProfile(next.id)
        }
        releaseDeletion.countDown()

        assertTrue(deletion.await())
        switch.await()

        assertNull(database.vitruvianDatabaseQueries.getProfileById(source.id).executeAsOneOrNull())
        assertEquals(
            "default",
            database.vitruvianDatabaseQueries.selectSessionById("atomic-delete-session")
                .executeAsOne().profile_id,
        )
        assertEquals(
            next.id,
            assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id,
        )
    }

    @Test
    fun activeDeletionPublishesExactTransitionThenCleansOnlySourceLocalSafety() = runTest {
        ready()
        val targetSafety = ProfileLocalSafetyPreferences("target-secret", false, true, false)
        repository.updateLocalSafety("default", targetSafety)
        val source = repository.createAndActivateProfile("Source", 1)
        val sourceSafety = ProfileLocalSafetyPreferences("source-secret", true, true, true)
        repository.updateLocalSafety(source.id, sourceSafety)
        val observed = mutableListOf<ActiveProfileContext>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeProfileContext.drop(1).take(2).toList(observed)
        }

        assertTrue(repository.deleteProfile(source.id))

        assertEquals(
            listOf(
                ActiveProfileContext.Switching("default"),
                repository.activeProfileContext.value,
            ),
            observed,
        )
        assertEquals("default", assertIs<ActiveProfileContext.Ready>(observed[1]).profile.id)
        assertEquals(ProfileLocalSafetyPreferences(), safetyStore.read(source.id))
        assertEquals(targetSafety, safetyStore.read("default"))
        assertTrue(database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup().executeAsList().isEmpty())
        job.cancel()
    }

    @Test
    fun activeDeletionCancellationDuringPostCommitPublicationRecoversReadyAndPropagatesOriginal() =
        runTest {
            ready()
            val source = repository.createAndActivateProfile("Source", 1)
            val cancellation = CancellationException("cancel post-commit publication")
            preferenceStore.cancelNextGetWith = cancellation
            var propagatedByRepository: CancellationException? = null

            val deletion = async {
                try {
                    repository.deleteActiveProfile(source.id)
                } catch (error: CancellationException) {
                    propagatedByRepository = error
                    throw error
                }
            }
            val thrown = assertFailsWith<CancellationException> { deletion.await() }

            assertEquals(cancellation.message, thrown.message)
            assertSame(cancellation, propagatedByRepository)
            assertNull(database.vitruvianDatabaseQueries.getProfileById(source.id).executeAsOneOrNull())
            assertEquals(
                "default",
                database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
            )
            assertEquals(
                "default",
                assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id,
            )
        }

    @Test
    fun activeDeletionCancellationPreservesRecoveryExceptionWhenReconciliationTrulyFails() =
        runTest {
            ready()
            val source = repository.createAndActivateProfile("Source", 1)
            val cancellation = CancellationException("cancel post-commit publication")
            preferenceStore.cancelNextGetWith = cancellation
            preferenceStore.failNextGet = true
            var propagatedByRepository: Throwable? = null

            val thrown = supervisorScope {
                val deletion = async {
                    try {
                        repository.deleteActiveProfile(source.id)
                    } catch (error: Throwable) {
                        propagatedByRepository = error
                        throw error
                    }
                }
                assertFailsWith<ProfileContextRecoveryException> { deletion.await() }
            }
            val recovery = assertIs<ProfileContextRecoveryException>(propagatedByRepository)
            assertEquals(recovery.message, thrown.message)
            assertSame(cancellation, recovery.cause)
            assertEquals(1, cancellation.suppressed.size)
            assertIs<InjectedTransitionFailure>(cancellation.suppressed.single())
            assertNull(database.vitruvianDatabaseQueries.getProfileById(source.id).executeAsOneOrNull())
            assertEquals(
                "default",
                database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id,
            )
            assertEquals(
                "default",
                assertIs<ActiveProfileContext.Switching>(
                    repository.activeProfileContext.value,
                ).targetProfileId,
            )
        }

    @Test
    fun inactiveDeletionTargetsCurrentReadyProfileWithoutSwitching() = runTest {
        ready()
        val target = repository.createAndActivateProfile("Target", 1)
        val source = repository.createProfile("Source", 2)
        safetyStore.write(source.id, ProfileLocalSafetyPreferences("source-secret", true, true, true))
        val observed = mutableListOf<ActiveProfileContext>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeProfileContext.drop(1).toList(observed)
        }

        assertTrue(repository.deleteProfile(source.id))
        job.cancel()

        assertTrue(observed.none { it is ActiveProfileContext.Switching })
        assertEquals(target.id, assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id)
        assertEquals(ProfileLocalSafetyPreferences(), safetyStore.read(source.id))
    }

    @Test
    fun beforeCommitFailureSeesJournalAndRollsBackSqlAndExactContext() = runTest {
        var sawJournalInsideTransaction = false
        repository = createRepository(
            database,
            preferenceStore,
            safetyStore,
            beforeProfileDeletionCommit = {
                sawJournalInsideTransaction = database.vitruvianDatabaseQueries
                    .selectPendingProfileLocalCleanup()
                    .executeAsOneOrNull() != null
                throw InjectedTransitionFailure()
            },
        )
        ready()
        val source = repository.createAndActivateProfile("Source", 1)
        repository.updateCore(source.id, CoreProfilePreferences(bodyWeightKg = 83f))
        val sourceSafety = ProfileLocalSafetyPreferences("source-secret", true, true, true)
        repository.updateLocalSafety(source.id, sourceSafety)
        insertWorkoutSession("source-session", 5, 20.0, source.id)
        val observed = mutableListOf<ActiveProfileContext>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeProfileContext.drop(1).take(2).toList(observed)
        }

        assertFailsWith<InjectedTransitionFailure> { repository.deleteProfile(source.id) }

        assertTrue(sawJournalInsideTransaction)
        assertEquals(
            listOf(
                ActiveProfileContext.Switching("default"),
                observed.firstOrNull { it is ActiveProfileContext.Ready },
            ),
            observed,
        )
        assertEquals(source.id, assertIs<ActiveProfileContext.Ready>(observed[1]).profile.id)
        assertNotNull(database.vitruvianDatabaseQueries.getProfileById(source.id).executeAsOneOrNull())
        assertNotNull(database.vitruvianDatabaseQueries.selectProfilePreferences(source.id).executeAsOneOrNull())
        assertEquals(83f, preferenceStore.get(source.id).core.value.bodyWeightKg)
        assertEquals(sourceSafety, safetyStore.read(source.id))
        assertEquals(source.id, database.vitruvianDatabaseQueries.getActiveProfile().executeAsOne().id)
        assertEquals(1, countRows("WorkoutSession", "profile_id", source.id))
        assertTrue(database.vitruvianDatabaseQueries.selectPendingProfileLocalCleanup().executeAsList().isEmpty())
        job.cancel()
    }

    @Test
    fun externalConflictsKeepTargetParentsAndDeleteSourceChildrenWhileSourceOnlyGraphsMove() = runTest {
        ready()
        val source = repository.createProfile("Source", 1)
        executeSql(
            "INSERT INTO ExternalRoutine(id, externalId, provider, title, syncedAt, rawData, profileId) VALUES (?, ?, 'hevy', ?, 1, ?, ?)",
            "target-routine", "shared", "Target", "target-bytes", "default",
        )
        executeSql(
            "INSERT INTO ExternalRoutine(id, externalId, provider, title, syncedAt, rawData, profileId) VALUES (?, ?, 'hevy', ?, 1, ?, ?)",
            "source-conflict", "shared", "Source", "source-bytes", source.id,
        )
        executeSql(
            "INSERT INTO ExternalRoutineExercise(id, externalRoutineId, title) VALUES (?, ?, ?)",
            "source-conflict-exercise", "source-conflict", "Delete me",
        )
        executeSql(
            "INSERT INTO ExternalRoutineSet(id, externalRoutineExerciseId, setIndex) VALUES (?, ?, 0)",
            "source-conflict-set", "source-conflict-exercise",
        )
        executeSql(
            "INSERT INTO ExternalRoutine(id, externalId, provider, title, syncedAt, rawData, profileId) VALUES (?, ?, 'hevy', ?, 1, ?, ?)",
            "source-only", "source-only", "Move", "source-only-bytes", source.id,
        )
        executeSql(
            "INSERT INTO ExternalRoutineExercise(id, externalRoutineId, title) VALUES (?, ?, ?)",
            "source-only-exercise", "source-only", "Retain me",
        )

        assertTrue(repository.deleteProfile(source.id))

        assertEquals("target-bytes", textValue("SELECT rawData FROM ExternalRoutine WHERE id = 'target-routine'"))
        assertEquals(0, countRowsWhere("ExternalRoutine", "id = 'source-conflict'"))
        assertEquals(0, countRowsWhere("ExternalRoutineExercise", "id = 'source-conflict-exercise'"))
        assertEquals(0, countRowsWhere("ExternalRoutineSet", "id = 'source-conflict-set'"))
        assertEquals("default", textValue("SELECT profileId FROM ExternalRoutine WHERE id = 'source-only'"))
        assertEquals(1, countRowsWhere("ExternalRoutineExercise", "id = 'source-only-exercise'"))
    }

    @Test
    fun deletionExecutesEveryDirectProfileTablePolicy() = runTest {
        ready()
        val source = repository.createProfile("Source", 1)
        val sourceId = source.id
        insertWorkoutSession("owned-session", 5, 20.0, sourceId)
        executeSql("INSERT INTO RoutineGroup(id, name, createdAt, profile_id) VALUES ('owned-group', 'G', 1, ?)", sourceId)
        executeSql("INSERT INTO Routine(id, name, createdAt, profile_id, groupId) VALUES ('owned-routine', 'R', 1, ?, 'owned-group')", sourceId)
        executeSql("INSERT INTO TrainingCycle(id, name, created_at, profile_id) VALUES ('owned-cycle', 'C', 1, ?)", sourceId)
        executeSql("INSERT INTO AssessmentResult(exerciseId, estimatedOneRepMaxKg, loadVelocityData, createdAt, profile_id) VALUES ('bench', 100, '{}', 1, ?)", sourceId)
        executeSql("INSERT INTO VelocityOneRepMaxEstimate(exerciseId, estimatedPerCableKg, mvtUsedMs, r2, distinctLoads, computedAt, profile_id) VALUES ('bench', 50, 200, .9, 3, 1, ?)", sourceId)
        executeSql("INSERT INTO PersonalRecord(id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume, phase, profile_id, uuid) VALUES (700, 'bench', 'Bench', 50, 5, 60, 1, 'OldSchool', 'MAX_WEIGHT', 250, 'COMBINED', ?, 'owned-pr')", sourceId)
        executeSql("INSERT INTO EarnedBadge(id, badgeId, earnedAt, profile_id) VALUES (701, 'owned-badge', 1, ?)", sourceId)
        executeSql("INSERT INTO StreakHistory(id, startDate, endDate, length, profile_id) VALUES (702, 1, 2, 2, ?)", sourceId)
        executeSql("INSERT INTO ExerciseMvt(exerciseId, profile_id, personalMvtMs, sampleCount, updatedAt) VALUES ('bench', ?, 250, 2, 1)", sourceId)
        executeSql("INSERT INTO ProgressionEvent(id, exercise_id, suggested_weight_kg, previous_weight_kg, reason, timestamp, profile_id) VALUES ('owned-progression', 'bench', 55, 50, 'test', 1, ?)", sourceId)
        executeSql("INSERT INTO IntegrationStatus(provider, status, errorMessage, profileId) VALUES ('hevy', 'connected', 'source-state', ?)", sourceId)
        executeSql("INSERT INTO IntegrationSyncCursor(provider, profileId, cursorType, cursorValue, updatedAt) VALUES ('hevy', ?, 'activities', 'source-cursor', 1)", sourceId)
        executeSql("INSERT INTO IntegrationStatus(provider, status, errorMessage, profileId) VALUES ('hevy', 'connected', 'target-state', 'default')")
        executeSql("INSERT INTO IntegrationSyncCursor(provider, profileId, cursorType, cursorValue, updatedAt) VALUES ('hevy', 'default', 'activities', 'target-cursor', 1)")

        assertTrue(repository.deleteProfile(sourceId))

        listOf(
            "WorkoutSession",
            "RoutineGroup",
            "Routine",
            "TrainingCycle",
            "AssessmentResult",
            "VelocityOneRepMaxEstimate",
            "PersonalRecord",
            "EarnedBadge",
            "StreakHistory",
            "ExerciseMvt",
            "ProgressionEvent",
        ).forEach { table ->
            assertEquals(0, countRows(table, "profile_id", sourceId), "$table retained source ownership")
            assertTrue(countRows(table, "profile_id", "default") >= 1, "$table did not move to target")
        }
        assertEquals(0, countRows("IntegrationStatus", "profileId", sourceId))
        assertEquals(0, countRows("IntegrationSyncCursor", "profileId", sourceId))
        assertEquals("target-state", textValue("SELECT errorMessage FROM IntegrationStatus WHERE profileId = 'default'"))
        assertEquals("target-cursor", textValue("SELECT cursorValue FROM IntegrationSyncCursor WHERE profileId = 'default'"))
        assertNull(database.vitruvianDatabaseQueries.selectProfilePreferences(sourceId).executeAsOneOrNull())
    }

    @Test
    fun personalRecordBadgeAndMvtCollisionsRetainTargetIdentityAndMergeMetadata() = runTest {
        ready()
        val source = repository.createProfile("Source", 1)
        executeSql("INSERT INTO PersonalRecord(id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume, phase, updatedAt, serverId, profile_id, uuid) VALUES (800, 'bench', 'Target Bench', 50, 5, 60, 10, 'Old School', 'MAX_WEIGHT', 250, 'COMBINED', 10, 'target-server', 'default', 'target-uuid')")
        executeSql("INSERT INTO PersonalRecord(id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume, phase, updatedAt, serverId, profile_id, uuid) VALUES (801, 'bench', '', 70, 5, 80, 20, 'OldSchool', 'MAX_WEIGHT', 350, 'COMBINED', 20, 'source-server', ?, 'source-uuid')", source.id)
        executeSql("INSERT INTO EarnedBadge(id, badgeId, earnedAt, celebratedAt, updatedAt, serverId, profile_id) VALUES (810, 'shared', 200, 250, 20, 'target-badge', 'default')")
        executeSql("INSERT INTO EarnedBadge(id, badgeId, earnedAt, celebratedAt, updatedAt, serverId, deletedAt, profile_id) VALUES (811, 'shared', 100, 300, 10, 'source-badge', 400, ?)", source.id)
        executeSql("INSERT INTO ExerciseMvt(exerciseId, profile_id, personalMvtMs, sampleCount, updatedAt) VALUES ('bench', 'default', 200, 2, 20)")
        executeSql("INSERT INTO ExerciseMvt(exerciseId, profile_id, personalMvtMs, sampleCount, updatedAt) VALUES ('bench', ?, 400, 6, 10)", source.id)

        assertTrue(repository.deleteProfile(source.id))

        val pr = database.vitruvianDatabaseQueries.selectAllRecords("default").executeAsList().single { it.exerciseId == "bench" }
        assertEquals(800, pr.id)
        assertEquals(70.0, pr.weight)
        assertEquals("Target Bench", pr.exerciseName)
        assertEquals("Old School", pr.workoutMode)
        assertEquals("source-server", pr.serverId)
        assertEquals("source-uuid", pr.uuid)
        val badge = database.vitruvianDatabaseQueries.selectAllEarnedBadges("default").executeAsList().single { it.badgeId == "shared" }
        assertEquals(810, badge.id)
        assertEquals(100, badge.earnedAt)
        assertEquals(250, badge.celebratedAt)
        assertEquals("target-badge", badge.serverId)
        val mvt = database.vitruvianDatabaseQueries.selectExerciseMvt("bench", "default").executeAsOne()
        assertEquals(350.0, mvt.personalMvtMs)
        assertEquals(8, mvt.sampleCount)
    }

    @Test
    fun externalLeafAndProgramConflictsAreTargetWinsAndSourceOnlyRowsMove() = runTest {
        ready()
        val source = repository.createProfile("Source", 1)
        val sourceId = source.id
        seedExternalLeafPairs(sourceId)

        assertTrue(repository.deleteProfile(sourceId))

        listOf(
            "ExternalActivity" to "target-activity",
            "ExternalRoutineFolder" to "target-folder",
            "ExternalProgram" to "target-program",
            "ExternalExerciseTemplate" to "target-template",
            "ExternalExerciseTemplateMapping" to "target-mapping",
            "ExternalBodyMeasurement" to "target-body",
        ).forEach { (table, id) ->
            assertEquals("target-bytes", textValue("SELECT rawData FROM $table WHERE id = '$id'"), table)
            assertEquals(0, countRows(table, "profileId", sourceId), "$table retained source ownership")
        }
        assertEquals(0, countRowsWhere("ExternalProgramStats", "externalProgramId = 'source-program-conflict'"))
        assertEquals(1, countRowsWhere("ExternalProgramStats", "externalProgramId = 'target-program'"))
        listOf(
            "ExternalActivity" to "source-activity-only",
            "ExternalRoutineFolder" to "source-folder-only",
            "ExternalProgram" to "source-program-only",
            "ExternalExerciseTemplate" to "source-template-only",
            "ExternalExerciseTemplateMapping" to "source-mapping-only",
            "ExternalBodyMeasurement" to "source-body-only",
        ).forEach { (table, id) ->
            assertEquals("default", textValue("SELECT profileId FROM $table WHERE id = '$id'"), table)
        }
        assertEquals(1, countRowsWhere("ExternalProgramStats", "externalProgramId = 'source-program-only'"))
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
    fun fakeDeletionMatchesFailureRollbackAndRetryableCleanupSemantics() = runTest {
        val fake = FakeUserProfileRepository()
        fake.ensureDefaultProfile()
        fake.reconcileActiveProfileContext()
        val source = fake.createAndActivateProfile("Source", 1)
        val safety = ProfileLocalSafetyPreferences("source-secret", true, true, true)
        fake.updateCore(source.id, CoreProfilePreferences(bodyWeightKg = 83f))
        fake.updateLocalSafety(source.id, safety)
        fake.failBeforeProfileDeletionCommit = true
        val failureEvents = mutableListOf<ActiveProfileContext>()
        val failureJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.activeProfileContext.drop(1).take(2).toList(failureEvents)
        }

        assertFailsWith<IllegalStateException> { fake.deleteProfile(source.id) }

        assertEquals("default", assertIs<ActiveProfileContext.Switching>(failureEvents[0]).targetProfileId)
        assertEquals(source.id, assertIs<ActiveProfileContext.Ready>(failureEvents[1]).profile.id)
        assertEquals(83f, fake.observePreferences(source.id).first().core.value.bodyWeightKg)
        assertEquals(safety, assertIs<ActiveProfileContext.Ready>(fake.activeProfileContext.value).localSafety)
        assertTrue(fake.pendingLocalCleanupProfileIds.isEmpty())
        failureJob.cancel()

        fake.failLocalCleanupDeletes = true
        assertTrue(fake.deleteProfile(source.id))
        assertEquals(setOf(source.id), fake.pendingLocalCleanupProfileIds)
        fake.failLocalCleanupDeletes = false
        fake.retryPendingLocalCleanup()
        assertTrue(fake.pendingLocalCleanupProfileIds.isEmpty())
    }

    @Test
    fun fakeFailedDeletionPublishesNoIdentityChangesAndKeepsExistingPreferenceCollectorsLive() = runTest {
        val fake = FakeUserProfileRepository()
        fake.ensureDefaultProfile()
        fake.reconcileActiveProfileContext()
        val source = fake.createAndActivateProfile("Source", 1)
        fake.updateCore(source.id, CoreProfilePreferences(bodyWeightKg = 83f))
        val preferenceEvents = mutableListOf<Float>()
        val activeEvents = mutableListOf<UserProfile?>()
        val allProfileEvents = mutableListOf<List<UserProfile>>()
        val preferenceJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.observePreferences(source.id).take(2).collect {
                preferenceEvents += it.core.value.bodyWeightKg
            }
        }
        val activeJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.activeProfile.drop(1).toList(activeEvents)
        }
        val allProfilesJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.allProfiles.drop(1).toList(allProfileEvents)
        }
        fake.failBeforeProfileDeletionCommit = true

        assertFailsWith<IllegalStateException> { fake.deleteProfile(source.id) }
        fake.updateCore(source.id, CoreProfilePreferences(bodyWeightKg = 84f))
        preferenceJob.cancel()
        activeJob.cancel()
        allProfilesJob.cancel()

        assertEquals(listOf(83f, 84f), preferenceEvents)
        assertTrue(activeEvents.isEmpty(), "failed transaction leaked active-profile identity")
        assertTrue(allProfileEvents.isEmpty(), "failed transaction leaked profile-list identity")
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
        beforeProfileDeletionCommit: () -> Unit = {},
    ) = SqlDelightUserProfileRepository(
        database = database,
        profilePreferencesRepository = preferences,
        profileLocalSafetyStore = safety,
        gamificationRepository = SqlDelightGamificationRepository(database),
        beforeProfileDeletionCommit = beforeProfileDeletionCommit,
    )

    private fun executeSql(sql: String, vararg values: Any?) {
        sqlDriver.execute(null, sql, values.size) {
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

    private fun countRows(table: String, column: String, value: String): Int =
        countRowsWhere(table, "$column = '$value'")

    private fun countRowsWhere(table: String, where: String): Int {
        var count = 0
        sqlDriver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table WHERE $where",
            { cursor ->
                if (cursor.next().value) count = cursor.getLong(0)?.toInt() ?: 0
                QueryResult.Value(Unit)
            },
            0,
        )
        return count
    }

    private fun textValue(sql: String): String? {
        var result: String? = null
        sqlDriver.executeQuery(
            null,
            sql,
            { cursor ->
                if (cursor.next().value) result = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            0,
        )
        return result
    }

    private fun seedExternalLeafPairs(sourceProfileId: String) {
        executeSql("INSERT INTO ExternalActivity(id, externalId, provider, name, startedAt, syncedAt, profileId, rawData) VALUES ('target-activity', 'shared-activity', 'hevy', 'Target', 1, 1, 'default', 'target-bytes')")
        executeSql("INSERT INTO ExternalActivity(id, externalId, provider, name, startedAt, syncedAt, profileId, rawData) VALUES ('source-activity', 'shared-activity', 'hevy', 'Source', 1, 1, ?, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalActivity(id, externalId, provider, name, startedAt, syncedAt, profileId, rawData) VALUES ('source-activity-only', 'source-activity-only', 'hevy', 'Only', 1, 1, ?, 'source-only-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalRoutineFolder(id, externalId, provider, title, profileId, rawData) VALUES ('target-folder', 'shared-folder', 'hevy', 'Target', 'default', 'target-bytes')")
        executeSql("INSERT INTO ExternalRoutineFolder(id, externalId, provider, title, profileId, rawData) VALUES ('source-folder', 'shared-folder', 'hevy', 'Source', ?, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalRoutineFolder(id, externalId, provider, title, profileId, rawData) VALUES ('source-folder-only', 'source-folder-only', 'hevy', 'Only', ?, 'source-only-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalProgram(id, externalId, provider, name, syncedAt, profileId, rawData) VALUES ('target-program', 'shared-program', 'hevy', 'Target', 1, 'default', 'target-bytes')")
        executeSql("INSERT INTO ExternalProgram(id, externalId, provider, name, syncedAt, profileId, rawData) VALUES ('source-program-conflict', 'shared-program', 'hevy', 'Source', 1, ?, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalProgramStats(id, externalProgramId, rawData) VALUES ('target-stats', 'target-program', 'target-stats-bytes')")
        executeSql("INSERT INTO ExternalProgramStats(id, externalProgramId, rawData) VALUES ('source-conflict-stats', 'source-program-conflict', 'source-stats-bytes')")
        executeSql("INSERT INTO ExternalProgram(id, externalId, provider, name, syncedAt, profileId, rawData) VALUES ('source-program-only', 'source-only-program', 'hevy', 'Only', 1, ?, 'source-only-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalProgramStats(id, externalProgramId, rawData) VALUES ('source-only-stats', 'source-program-only', 'source-only-stats-bytes')")
        executeSql("INSERT INTO ExternalExerciseTemplate(id, externalId, provider, title, profileId, rawData) VALUES ('target-template', 'shared-template', 'hevy', 'Target', 'default', 'target-bytes')")
        executeSql("INSERT INTO ExternalExerciseTemplate(id, externalId, provider, title, profileId, rawData) VALUES ('source-template', 'shared-template', 'hevy', 'Source', ?, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalExerciseTemplate(id, externalId, provider, title, profileId, rawData) VALUES ('source-template-only', 'source-template-only', 'hevy', 'Only', ?, 'source-only-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalExerciseTemplateMapping(id, provider, externalTemplateId, localExerciseId, profileId, createdAt, updatedAt, rawData) VALUES ('target-mapping', 'hevy', 'shared-template', 'bench', 'default', 1, 1, 'target-bytes')")
        executeSql("INSERT INTO ExternalExerciseTemplateMapping(id, provider, externalTemplateId, localExerciseId, profileId, createdAt, updatedAt, rawData) VALUES ('source-mapping', 'hevy', 'shared-template', 'squat', ?, 1, 1, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalExerciseTemplateMapping(id, provider, externalTemplateId, localExerciseId, profileId, createdAt, updatedAt, rawData) VALUES ('source-mapping-only', 'hevy', 'source-template-only', 'row', ?, 1, 1, 'source-only-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalBodyMeasurement(id, externalId, provider, measurementType, value, unit, measuredAt, syncedAt, profileId, rawData) VALUES ('target-body', 'shared-body', 'health', 'weight', 80, 'kg', 1, 1, 'default', 'target-bytes')")
        executeSql("INSERT INTO ExternalBodyMeasurement(id, externalId, provider, measurementType, value, unit, measuredAt, syncedAt, profileId, rawData) VALUES ('source-body', 'shared-body', 'health', 'weight', 90, 'kg', 1, 1, ?, 'source-bytes')", sourceProfileId)
        executeSql("INSERT INTO ExternalBodyMeasurement(id, externalId, provider, measurementType, value, unit, measuredAt, syncedAt, profileId, rawData) VALUES ('source-body-only', 'source-body-only', 'health', 'weight', 90, 'kg', 1, 1, ?, 'source-only-bytes')", sourceProfileId)
    }

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
        var cancelNextGetWith: CancellationException? = null
        private var requireActiveGetContext = false

        override suspend fun get(profileId: String): UserProfilePreferences {
            cancelNextGetWith?.let { cancellation ->
                cancelNextGetWith = null
                requireActiveGetContext = true
                currentCoroutineContext().cancel(cancellation)
                throw cancellation
            }
            if (requireActiveGetContext) currentCoroutineContext().ensureActive()
            return when {
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
        var failNextProfileDelete: Boolean = false,
        var failNextJournalClear: Boolean = false,
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
            if (identifier == DELETE_PROFILE_IDENTIFIER && faults.failNextProfileDelete) {
                faults.failNextProfileDelete = false
                throw InjectedTransitionFailure()
            }
            if (identifier == CLEAR_RECOVERY_JOURNAL_IDENTIFIER && faults.failNextJournalClear) {
                faults.failNextJournalClear = false
                throw InjectedTransitionFailure()
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        private companion object {
            const val SET_ACTIVE_PROFILE_IDENTIFIER = 373_348_112
            const val DELETE_PROFILE_IDENTIFIER = 787_673_935
            const val CLEAR_RECOVERY_JOURNAL_IDENTIFIER = 1_230_173_044
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
