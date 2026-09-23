package com.devil.phoenixproject.util

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.GoTrueUser
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestDriver
import com.devil.phoenixproject.testutil.createTestSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.testutil.seedExercise
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * PR 22: backups are complete for user-authored data, fail loudly instead of exporting
 * empty sections, keep raw telemetry opt-in, and restore without leaving the sync engine
 * a wrong picture of what the portal already has. FK-on in-memory databases throughout.
 */
class DataBackupCompletenessTest {

    private val testJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ---- fresh-device round trip ----

    @Test
    fun `a routine on a custom exercise restores onto a fresh device with no exercise rows`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.database.seedExercise("custom-cable-fly", "Cable Fly", isCustom = true)
        source.saveRoutineOn("custom-cable-fly", progressionKg = 1f)

        val target = Fixture()
        assertEquals(null, target.queries.selectExerciseById("custom-cable-fly").executeAsOneOrNull())

        val result = target.manager.importFromJson(source.manager.exportToJson()).getOrThrow()

        assertEquals(0, result.entitiesWithErrors)
        assertEquals(1L, target.queries.selectExerciseById("custom-cable-fly").executeAsOne().isCustom)
        val restored = target.queries.selectRoutineExerciseById("routine-1-re").executeAsOne()
        assertEquals("custom-cable-fly", restored.exerciseId, "routine exercise must keep its custom exercise")
    }

    // ---- per-profile gamification stats ----

    @Test
    fun `two profiles export and restore their own gamification stats`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.insertStats(profileId = "default", totalWorkouts = 7)
        source.insertStats(profileId = PROFILE_B, totalWorkouts = 42)

        val exported = testJson.decodeFromString<BackupData>(source.manager.exportToJson())
        assertEquals(
            mapOf("default" to 7, PROFILE_B to 42),
            exported.data.gamificationStatsByProfile.associate { it.profileId to it.totalWorkouts },
        )
        val streamed = testJson.decodeFromString<BackupData>(File(source.manager.exportToCachePublic()).readText())
        assertEquals(2, streamed.data.gamificationStatsByProfile.size, "streaming export must carry both profiles")

        val target = Fixture()
        target.manager.importFromJson(source.manager.exportToJson()).getOrThrow()
        assertEquals(7L, target.queries.selectGamificationStats("default").executeAsOne().totalWorkouts)
        assertEquals(42L, target.queries.selectGamificationStats(PROFILE_B).executeAsOne().totalWorkouts)
    }

    // ---- a table read failure fails the export ----

    @Test
    fun `a failed table read fails both exports instead of writing an empty section`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.driver.execute(null, "DROP TABLE StreakHistory", 0)

        assertFailsWith<Exception> { source.manager.exportToJson() }
        assertTrue(source.manager.exportToFile().isFailure, "streaming export must report failure")
    }

    // ---- progressionKg clamp ----

    @Test
    fun `an imported progression of 50 kg per rep is stored as 3`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.database.seedExercise("custom-row", "Row", isCustom = true)
        source.saveRoutineOn("custom-row", progressionKg = 1f)
        source.saveSession("session-1")

        val backup = testJson.decodeFromString<BackupData>(source.manager.exportToJson())
        val tampered = backup.copy(
            data = backup.data.copy(
                workoutSessions = backup.data.workoutSessions.map { it.copy(progressionKg = 50f) },
                routineExercises = backup.data.routineExercises.map { it.copy(progressionKg = 50f) },
            ),
        )

        val target = Fixture()
        target.manager.importFromJson(testJson.encodeToString(tampered)).getOrThrow()

        assertEquals(3.0, target.queries.selectSessionById("session-1").executeAsOne().progressionKg)
        assertEquals(3.0, target.queries.selectRoutineExerciseById("routine-1-re").executeAsOne().progressionKg)
    }

    // ---- raw telemetry is opt-in ----

    @Test
    fun `raw telemetry is left out of every export by default`() = runTest {
        val source = Fixture(includeRawTelemetry = false)
        source.seedProfiles()
        source.saveSession("session-1")
        source.insertMetric("session-1")

        val full = testJson.decodeFromString<BackupData>(source.manager.exportToJson())
        assertTrue(full.data.metricSamples.isEmpty())
        assertFalse(full.privacy.containsRawTelemetry)
        assertTrue("Does not include raw per-sample telemetry" in full.privacy.userFacingSummary)
        val streamed = testJson.decodeFromString<BackupData>(File(source.manager.exportToCachePublic()).readText())
        assertTrue(streamed.data.metricSamples.isEmpty())
        val auto = testJson.decodeFromString<BackupData>(File(source.manager.exportSession("session-1").getOrThrow()).readText())
        assertTrue(auto.data.metricSamples.isEmpty(), "auto backups follow the same default")
        assertEquals(1L, source.queries.countBackupMetricSamples().executeAsOne(), "samples stay on the phone")
    }

    @Test
    fun `opted-in raw telemetry is exported and round-trips`() = runTest {
        val source = Fixture(includeRawTelemetry = true)
        source.seedProfiles()
        source.saveSession("session-1")
        source.insertMetric("session-1")

        val exported = source.manager.exportToJson()
        val backup = testJson.decodeFromString<BackupData>(exported)
        assertEquals(1, backup.data.metricSamples.size)
        assertTrue(backup.privacy.containsRawTelemetry)

        val target = Fixture(includeRawTelemetry = false)
        val result = target.manager.importFromJson(exported).getOrThrow()
        assertEquals(1, result.metricsImported, "restore imports samples whenever the file has them")
    }

    // ---- session sync markers ----

    @Test
    fun `restored sessions keep their origin and stamp and only never-uploaded ones push`() = runTest {
        val source = Fixture(includeRawTelemetry = true)
        source.seedProfiles()
        source.saveSession("pulled-1")
        source.saveSession("synced-1")
        source.saveSession("unsynced-1")
        source.saveSession("edited-1")
        // Child restores re-dirty their session, so every marked row gets a child.
        listOf("pulled-1", "synced-1", "unsynced-1", "edited-1").forEach(source::insertMetric)
        source.queries.restoreSessionSyncMarkers(portalOrigin = 1L, updatedAt = 1_700_000_500_000L, acknowledged = 0L, id = "pulled-1")
        // Pushed and acknowledged, as the old phone's sync leaves it.
        source.queries.updateSessionTimestamp(1_700_000_600_000L, "synced-1")
        source.queries.markSessionSynced("synced-1")
        // Pushed and acknowledged, then re-tagged: the stamp stays but the edit is pending.
        source.queries.updateSessionTimestamp(1_700_000_600_000L, "edited-1")
        source.queries.markSessionSynced("edited-1")
        source.queries.updateSessionExerciseTag(null, "Front Squat", 1_700_000_700_000L, "edited-1")

        val target = Fixture()
        target.manager.importFromJson(source.manager.exportToJson()).getOrThrow()

        val pulled = target.queries.selectSessionById("pulled-1").executeAsOne()
        assertEquals(1L, pulled.portalOrigin)
        assertEquals(1_700_000_500_000L, pulled.updatedAt)
        assertFalse(pulled.local_sync_generation > pulled.synced_sync_generation, "a pulled row must never be re-pushed")

        // Stamped by the old device's push: the portal holds it with rep data the backup lacks.
        val synced = target.queries.selectSessionById("synced-1").executeAsOne()
        assertEquals(0L, synced.portalOrigin)
        assertEquals(1_700_000_600_000L, synced.updatedAt)
        assertFalse(synced.local_sync_generation > synced.synced_sync_generation, "a synced row must not be re-pushed")
        assertTrue(synced.synced_sync_generation > 0L, "and it counts as synced, not never-synced")

        val unsynced = target.queries.selectSessionById("unsynced-1").executeAsOne()
        assertEquals(null, unsynced.updatedAt)
        assertTrue(unsynced.local_sync_generation > unsynced.synced_sync_generation, "a never-uploaded row still pushes")

        // A stamp is not proof: an edit still pending at backup time must still upload.
        val edited = target.queries.selectSessionById("edited-1").executeAsOne()
        assertEquals(1_700_000_700_000L, edited.updatedAt)
        assertEquals("Front Squat", edited.exerciseName)
        assertTrue(edited.local_sync_generation > edited.synced_sync_generation, "a pending edit must not be marked synced")
    }

    // ---- stock exercise user fields ----

    @Test
    fun `favourites and MVT overrides on stock exercises survive a restore without overwriting local choices`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.database.seedExercise("stock-squat", "Squat")
        source.database.seedExercise("stock-press", "Press")
        source.queries.updateFavorite(1L, "stock-squat")
        source.driver.execute(null, "UPDATE Exercise SET mvtOverrideMs = 180.0 WHERE id = 'stock-press'", 0)

        val target = Fixture()
        target.database.seedExercise("stock-squat", "Squat")
        target.database.seedExercise("stock-press", "Press")
        target.driver.execute(null, "UPDATE Exercise SET mvtOverrideMs = 150.0 WHERE id = 'stock-press'", 0)

        val result = target.manager.importFromJson(source.manager.exportToJson()).getOrThrow()

        assertEquals(0, result.entitiesWithErrors)
        assertEquals(1L, target.queries.selectExerciseById("stock-squat").executeAsOne().isFavorite)
        assertEquals(150.0, target.queries.selectExerciseById("stock-press").executeAsOne().mvtOverrideMs, "local override wins")
    }

    // ---- restore resets settings-held sync state ----

    @Test
    fun `a restore resets the restored profiles' sync cursors and one-shot work markers`() = runTest {
        val storage = PortalTokenStorage(MapSettings())
        storage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "user-1@example.com"),
            ),
        )
        storage.setPullCursor("user-1", PROFILE_B, 5_000L)
        storage.setRoutineGroupRepairCursor(PROFILE_B, 0L)

        val source = Fixture()
        source.seedProfiles()
        source.saveSession("session-b", profileId = PROFILE_B)

        val preferences = FakePreferencesManager()
        preferences.setVelocityOneRepMaxBackfillDone(true)

        val target = Fixture(portalTokenStorage = storage, preferencesManager = preferences)
        target.manager.importFromJson(source.manager.exportToJson()).getOrThrow()

        assertEquals(0L, storage.getPullCursor("user-1", PROFILE_B), "next pull must be a full pull")
        assertEquals(0L, storage.getRoutineGroupRepairCursor(PROFILE_B), "no repair re-push of rep-less restored rows")
        assertEquals(1, preferences.oneShotResetCount)
        assertFalse(preferences.preferencesFlow.value.velocityOneRepMaxBackfillDone, "backfill re-runs over restored sessions")
    }

    // ---- which row failures a restore may skip ----

    @Test
    fun `only malformed rows and constraint violations are skipped and everything else aborts the restore`() {
        assertTrue(isSkippableRestoreRowFailure(SerializationException("bad row")))
        assertTrue(isSkippableRestoreRowFailure(RuntimeException("wrapped", SerializationException("bad row"))))
        assertTrue(isSkippableRestoreRowFailure(IllegalStateException("[SQLITE_CONSTRAINT_FOREIGNKEY] FOREIGN KEY constraint failed")))
        // Android framework wording
        assertTrue(isSkippableRestoreRowFailure(IllegalStateException("UNIQUE constraint failed: Exercise.id (code 2067 SQLITE_CONSTRAINT_UNIQUE)")))
        assertFalse(isSkippableRestoreRowFailure(IllegalStateException("[SQLITE_FULL] database or disk is full")))
        assertFalse(isSkippableRestoreRowFailure(RuntimeException("wrapped", java.io.IOException("disk I/O error"))))
        assertFalse(isSkippableRestoreRowFailure(IllegalStateException("database disk image is malformed")))
        assertFalse(isSkippableRestoreRowFailure(NullPointerException()), "an unknown failure aborts, never silently skips")
        assertFalse(isSkippableRestoreRowFailure(IllegalStateException("unexpected driver state")))
        assertFalse(isSkippableRestoreRowFailure(kotlinx.coroutines.CancellationException("cancelled")))
    }

    @Test
    fun `a storage failure mid-restore aborts the restore while a constraint failure only skips the row`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.saveSession("session-1")
        val exported = source.manager.exportToJson()

        val diskFull = Fixture(failSessionInsertsWith = "[SQLITE_FULL] database or disk is full")
        assertTrue(diskFull.manager.importFromJson(exported).isFailure, "disk full must abort, not skip")

        val constraint = Fixture(failSessionInsertsWith = "[SQLITE_CONSTRAINT_CHECK] CHECK constraint failed")
        val skipped = constraint.manager.importFromJson(exported).getOrThrow()
        assertEquals(1, skipped.entitiesWithErrors, "a constraint failure skips only that row")

        val unexpected = Fixture(failSessionInsertsWith = "unexpected driver state")
        assertTrue(unexpected.manager.importFromJson(exported).isFailure, "an unknown failure aborts, never silently skips")
    }

    @Test
    fun `a restore that aborts after sessions committed leaves pulled rows pulled and clean and still resets sync state`() = runTest {
        val source = Fixture(includeRawTelemetry = true)
        source.seedProfiles()
        source.saveSession("pulled-1")
        source.insertMetric("pulled-1") // restoring this re-dirties the pulled row
        source.queries.insertStreakHistory(1_700_000_000_000L, 1_700_000_100_000L, 2L, profileId = "default")
        source.queries.restoreSessionSyncMarkers(portalOrigin = 1L, updatedAt = 1_700_000_500_000L, acknowledged = 0L, id = "pulled-1")
        val storage = PortalTokenStorage(MapSettings())
        storage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access", tokenType = "bearer", expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600, refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "user-1@example.com"),
            ),
        )
        storage.setPullCursor("user-1", "default", 5_000L)

        // The disk fills in a late section, after sessions and their samples committed.
        val target = Fixture(
            portalTokenStorage = storage,
            failSessionInsertsWith = "database or disk is full (code 13 SQLITE_FULL)",
            failInsertsInto = "StreakHistory",
        )
        assertTrue(target.manager.importFromJson(source.manager.exportToJson()).isFailure)

        val pulled = target.queries.selectSessionById("pulled-1").executeAsOne()
        assertEquals(1L, pulled.portalOrigin, "an aborted restore must not leave a pulled row looking local")
        assertFalse(pulled.local_sync_generation > pulled.synced_sync_generation, "nor dirty")
        assertEquals(0L, storage.getPullCursor("user-1", "default"), "committed rows still reset the pull cursor")
    }

    // ---- v1-v6 single-object gamification stats (the shipping app's format) ----

    @Test
    fun `a v6 backup restores its single gamification stats row`() = runTest {
        val target = Fixture()
        val result = target.manager.importFromJson(v6Backup(statsProfileId = "default", totalWorkouts = 9)).getOrThrow()

        assertTrue(result.gamificationStatsImported)
        val restored = target.queries.selectGamificationStats("default").executeAsOne()
        assertEquals(9L, restored.totalWorkouts)
        assertEquals(1_700_000_000_000L, restored.lastUpdated)
    }

    @Test
    fun `a v6 backup whose stats name a profile it does not carry is rejected and counted`() = runTest {
        val target = Fixture()
        val result = target.manager.importFromJson(v6Backup(statsProfileId = "ghost", totalWorkouts = 9)).getOrThrow()

        assertFalse(result.gamificationStatsImported)
        assertEquals(1, result.entitiesWithErrors)
        assertEquals(null, target.queries.selectGamificationStats("ghost").executeAsOneOrNull())
    }

    /**
     * A v6 file as the shipping app writes it: a single `gamificationStats` object, and none
     * of the v7 sections. Built from a real export, then reshaped to the older wire format.
     */
    private suspend fun v6Backup(statsProfileId: String, totalWorkouts: Int): String {
        val source = Fixture()
        source.seedProfiles()
        val v7 = testJson.decodeFromString<BackupData>(source.manager.exportToJson())
        val v6 = v7.copy(
            version = 6,
            data = v7.data.copy(
                gamificationStats = GamificationStatsBackup(
                    totalWorkouts = totalWorkouts,
                    lastUpdated = 1_700_000_000_000L,
                    profileId = statsProfileId,
                ),
            ),
        )
        val root = testJson.parseToJsonElement(testJson.encodeToString(v6)).jsonObject
        val data = root.getValue("data").jsonObject
        val v6Data = JsonObject(data - "gamificationStatsByProfile" - "stockExerciseUserFields")
        return JsonObject(root + ("data" to v6Data)).toString()
    }

    // ---- fixtures ----

    /**
     * Fails every insert into [table] with [message], mirroring how a platform SQLite driver
     * reports it (Android: "database or disk is full (code 13 SQLITE_FULL)").
     */
    private class FailingSessionInsertDriver(
        private val delegate: SqlDriver,
        private val message: String?,
        private val table: String = "WorkoutSession",
    ) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (message != null && sql.contains("INTO $table")) throw IllegalStateException(message)
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private class Fixture(
        includeRawTelemetry: Boolean = false,
        portalTokenStorage: PortalTokenStorage? = null,
        preferencesManager: FakePreferencesManager? = null,
        failSessionInsertsWith: String? = null,
        failInsertsInto: String = "WorkoutSession",
    ) {
        val driver: SqlDriver = if (failSessionInsertsWith == null) {
            createTestDriver()
        } else {
            FailingSessionInsertDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), failSessionInsertsWith, failInsertsInto)
                .also(::createTestSchema)
        }
        val database = PhoenixDatabase(driver)
        val queries = database.phoenixDatabaseQueries
        private val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val manager = TestManager(database, includeRawTelemetry, portalTokenStorage, preferencesManager)

        fun seedProfiles() {
            queries.insertUserProfileIgnore("default", "Default", 0L, 1L, 1L)
            queries.insertUserProfileIgnore(PROFILE_B, "B", 1L, 2L, 0L)
            queries.insertDefaultProfilePreferences("default", 1L)
            queries.insertDefaultProfilePreferences(PROFILE_B, 1L)
        }

        suspend fun saveRoutineOn(exerciseId: String, progressionKg: Float) {
            workoutRepository.saveRoutine(
                Routine(
                    id = "routine-1",
                    name = "Day",
                    exercises = listOf(
                        RoutineExercise(
                            id = "routine-1-re",
                            exercise = Exercise(id = exerciseId, name = exerciseId, muscleGroup = "Chest"),
                            orderIndex = 0,
                            weightPerCableKg = 20f,
                            progressionKg = progressionKg,
                        ),
                    ),
                ),
            )
        }

        suspend fun saveSession(id: String, profileId: String = "default") {
            workoutRepository.saveSession(
                WorkoutSession(
                    id = id,
                    timestamp = 1_700_000_000_000L,
                    mode = "OLD_SCHOOL",
                    reps = 8,
                    weightPerCableKg = 30f,
                    duration = 60_000L,
                    totalReps = 8,
                    workingReps = 8,
                    profileId = profileId,
                ),
            )
        }

        fun insertMetric(sessionId: String) {
            queries.insertMetric(sessionId, 1_700_000_000_100L, 0.5, null, 0.4, null, 30.0, null, 12.0, 0L)
        }

        fun insertStats(profileId: String, totalWorkouts: Long) {
            queries.upsertGamificationStats(
                profileId.hashCode().toLong(), totalWorkouts, 10L, 100L, 1L, 1L, 1L, 0L, null, null, 1_700_000_000_000L,
                profileId = profileId,
            )
        }
    }

    private class TestManager(
        database: PhoenixDatabase,
        override val includeRawTelemetryInBackups: Boolean,
        portalTokenStorage: PortalTokenStorage?,
        preferencesManager: FakePreferencesManager?,
    ) : BaseDataBackupManager(
        database,
        SqlDelightProfilePreferencesRepository(database),
        SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
            profileLocalSafetyStore = SettingsProfileLocalSafetyStore(MapSettings()),
            gamificationRepository = SqlDelightGamificationRepository(database),
        ),
        portalTokenStorage,
        preferencesManager,
    ) {
        private val dir = kotlin.io.path.createTempDirectory("pr22-backups").toFile()

        suspend fun exportToCachePublic(): String = exportToCache()

        override fun createBackupWriter(): BackupJsonWriter =
            BackupJsonWriter(File.createTempFile("pr22-export-", ".json", dir).absolutePath)

        override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)
        override suspend fun saveToFile(backup: BackupData): Result<String> = error("unused")
        override suspend fun importFromFile(filePath: String): Result<ImportResult> = error("unused")
        override suspend fun shareBackup() = Unit
        override fun getSessionBackupDirectory(): String = dir.absolutePath
        override fun listBackupFileSizes(): List<Long> = emptyList()
        override fun openBackupFolder() = Unit
        override fun pruneOldBackups(keepCount: Int) = Unit
    }

    private companion object {
        const val PROFILE_B = "profile-b"
    }
}
