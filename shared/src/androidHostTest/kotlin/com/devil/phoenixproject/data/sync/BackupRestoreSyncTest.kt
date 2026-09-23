package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalServer
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.PortalServerApiClient
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.util.BackupData
import com.devil.phoenixproject.util.BackupJsonWriter
import com.devil.phoenixproject.util.BaseDataBackupManager
import com.devil.phoenixproject.util.ImportResult
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PR 22 / G-1: restoring a backup onto a new phone must not destroy the portal's rep data.
 *
 * A backup carries sessions and their completed sets but not RepMetric / RepBiomechanics.
 * The portal's `replace_session_children` deletes a session's children (rep summaries
 * included) and re-inserts the pushed payload, so re-pushing a restored session the portal
 * already holds would wipe its rep data. This fake portal models that replacement; each
 * seeded session carries a `rep-data` marker exercise that survives only if the session is
 * never re-pushed. FK-on databases throughout.
 */
class BackupRestoreSyncTest {

    private val profileId = "active-profile"
    private val stamp = currentTimeMillis() - 86_400_000L

    @Test
    fun `a restored backup never re-pushes what the portal holds and uploads unsynced work once`() = runTest {
        // Old phone: two synced workouts (a standalone one and a two-set routine group,
        // stamped by an earlier push) and one workout that never reached the portal.
        val oldPhone = createTestDatabase()
        oldPhone.seedProfile()
        oldPhone.insertSession("solo", groupId = null, timestamp = stamp, stampedAt = stamp + 1_000)
        oldPhone.insertSession("g-1", groupId = GROUP, timestamp = stamp, stampedAt = stamp + 1_000)
        oldPhone.insertSession("g-2", groupId = GROUP, timestamp = stamp + 60_000, stampedAt = stamp + 61_000)
        oldPhone.insertSession("fresh", groupId = null, timestamp = stamp + 120_000, stampedAt = null)
        val backup = backupManager(oldPhone, tokenStorage = null).exportToJson()

        // The portal holds the synced workouts with rep data the backup does not carry.
        val server = FakePortalServer(serverNow = { currentTimeMillis() })
        listOf("solo", GROUP).forEach { id ->
            server.seedSession(
                id = id,
                exercises = listOf(repData()),
                updatedAt = stamp + 2_000,
                routineSessionId = if (id == GROUP) GROUP else null,
            )
        }

        // New phone, signed into the same account: restore, then sync twice.
        val newPhone = createTestDatabase()
        val tokenStorage = signedInTokenStorage()
        val result = backupManager(newPhone, tokenStorage).importFromJson(backup).getOrThrow()
        assertEquals(0, result.entitiesWithErrors)
        assertEquals(4, result.sessionsImported)

        // The new phone's own history: a routine workout the portal holds truncated to its
        // first set (the old per-set push bug). The repair must still rebuild it.
        newPhone.insertSession("own-1", groupId = OWN_GROUP, timestamp = stamp - 600_000, stampedAt = stamp - 599_000)
        newPhone.insertSession("own-2", groupId = OWN_GROUP, timestamp = stamp - 540_000, stampedAt = stamp - 539_000)
        server.seedSession(
            id = OWN_GROUP,
            exercises = listOf(FakePortalServer.StoredExercise("own-1", "Bench Press", emptyList())),
            updatedAt = stamp - 599_000,
            routineSessionId = OWN_GROUP,
        )

        val apiClient = PortalServerApiClient(server)
        val manager = syncManager(newPhone, apiClient, tokenStorage)
        manager.sync()
        manager.sync()

        val pushedIds = apiClient.pushPayloads.flatMap { payload -> payload.sessions.map { it.id } }
        assertTrue("solo" !in pushedIds && GROUP !in pushedIds, "restored synced workouts must not be re-pushed: $pushedIds")
        listOf("solo", GROUP).forEach { id ->
            assertEquals(listOf("rep-data"), server.exerciseIds(id), "the portal's rep data for $id must survive")
        }
        assertEquals(1, pushedIds.count { it == "fresh" }, "a never-uploaded workout uploads exactly once")
        assertEquals(
            listOf("own-1", "own-2"),
            server.exerciseIds(OWN_GROUP).sorted(),
            "a restore must not stop the repair of this device's own truncated workouts",
        )
        assertTrue(server.session("fresh") != null)
    }

    // ---- fixtures ----

    private fun repData() = FakePortalServer.StoredExercise(
        id = "rep-data",
        name = "Bench Press",
        sets = listOf(FakePortalServer.StoredSet(id = "rep-summary", weightKg = 40f, actualReps = 8)),
    )

    private fun signedInTokenStorage() = PortalTokenStorage(MapSettings()).apply {
        saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "a@b.c"),
            ),
        )
    }

    private fun syncManager(
        database: PhoenixDatabase,
        apiClient: PortalServerApiClient,
        tokenStorage: PortalTokenStorage,
    ): SyncManager {
        val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest(id = profileId) }
        return SyncManager(
            apiClient = apiClient,
            tokenStorage = tokenStorage,
            syncRepository = SqlDelightSyncRepository(database, profiles),
            gamificationRepository = FakeGamificationRepository(),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = profiles,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
        )
    }

    private fun PhoenixDatabase.seedProfile() {
        phoenixDatabaseQueries.insertUserProfileIgnore("default", "Default", 0L, 1L, 0L)
        phoenixDatabaseQueries.insertUserProfileIgnore(profileId, "Me", 1L, 2L, 1L)
        phoenixDatabaseQueries.insertDefaultProfilePreferences("default", 1L)
        phoenixDatabaseQueries.insertDefaultProfilePreferences(profileId, 1L)
    }

    /** One set with a CompletedSet child; [stampedAt] models a row an earlier push stamped. */
    private fun PhoenixDatabase.insertSession(id: String, groupId: String?, timestamp: Long, stampedAt: Long?) {
        val q = phoenixDatabaseQueries
        q.insertSession(
            id = id, timestamp = timestamp, mode = "OldSchool", targetReps = 8L, weightPerCableKg = 40.0,
            progressionKg = 0.0, duration = 45_000L, totalReps = 8L, warmupReps = 0L, workingReps = 8L,
            isJustLift = 0L, stopAtTop = 0L, eccentricLoad = 100L, echoLevel = 0L, exerciseId = null,
            exerciseName = "Bench Press", routineSessionId = groupId, routineName = groupId?.let { "Push Day" },
            routineId = null, safetyFlags = 0L, deloadWarningCount = 0L, romViolationCount = 0L,
            spotterActivations = 0L, peakForceConcentricA = null, peakForceConcentricB = null,
            peakForceEccentricA = null, peakForceEccentricB = null, avgForceConcentricA = null,
            avgForceConcentricB = null, avgForceEccentricA = null, avgForceEccentricB = null,
            heaviestLiftKg = null, totalVolumeKg = null, cableCount = 2L, estimatedCalories = null,
            warmupAvgWeightKg = null, workingAvgWeightKg = null, burnoutAvgWeightKg = null, peakWeightKg = null,
            rpe = null, avgMcvMmS = null, avgAsymmetryPercent = null, totalVelocityLossPercent = null,
            dominantSide = null, strengthProfile = null, formScore = null, profile_id = profileId,
            display_multiplier = 2L, externalAddedLoadKg = 0.0, counterweightKg = 0.0, rackItemsJson = "[]",
        )
        q.insertCompletedSet(
            id = "cs-$id", session_id = id, planned_set_id = null, routine_exercise_id = null, set_number = 1L,
            set_type = "STANDARD", attempt_number = 1L, actual_reps = 8L, actual_weight_kg = 40.0, logged_rpe = null,
            is_pr = 0L, completed_at = timestamp, set_end_reason = "UNKNOWN",
        )
        stampedAt?.let {
            q.updateSessionTimestamp(it, id)
            q.markSessionSynced(id)
        }
    }

    private fun backupManager(database: PhoenixDatabase, tokenStorage: PortalTokenStorage?): BaseDataBackupManager {
        val manager = object : BaseDataBackupManager(
            database,
            SqlDelightProfilePreferencesRepository(database),
            SqlDelightUserProfileRepository(
                database = database,
                profilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
                profileLocalSafetyStore = SettingsProfileLocalSafetyStore(MapSettings()),
                gamificationRepository = SqlDelightGamificationRepository(database),
            ),
            tokenStorage,
        ) {
            private val dir = kotlin.io.path.createTempDirectory("pr22-sync").toFile()
            override fun createBackupWriter(): BackupJsonWriter =
                BackupJsonWriter(File.createTempFile("pr22-", ".json", dir).absolutePath)
            override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)
            override suspend fun saveToFile(backup: BackupData): Result<String> = error("unused")
            override suspend fun importFromFile(filePath: String): Result<ImportResult> = error("unused")
            override suspend fun shareBackup() = Unit
            override fun getSessionBackupDirectory(): String = dir.absolutePath
            override fun listBackupFileSizes(): List<Long> = emptyList()
            override fun openBackupFolder() = Unit
            override fun pruneOldBackups(keepCount: Int) = Unit
        }
        // As the other backup fixtures do: every profile the repository created has preferences.
        database.phoenixDatabaseQueries.seedMissingProfilePreferences()
        return manager
    }

    private companion object {
        const val GROUP = "routine-session-restored"
        const val OWN_GROUP = "routine-session-own"
    }
}
