package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.util.BackupImportStagingArea
import com.devil.phoenixproject.util.BackupJsonWriter
import com.devil.phoenixproject.util.BackupStreamSource
import com.devil.phoenixproject.util.BaseDataBackupManager
import com.devil.phoenixproject.util.ImportResult
import com.russhwolf.settings.MapSettings
import java.io.File
import java.io.StringReader

/**
 * Host-test [BaseDataBackupManager].
 *
 * Profile repositories match the wiring the backup host tests used to paste:
 * one preferences repository, a SQLDelight user-profile repository, and a
 * seed of any profile that has no preferences row yet. Raw telemetry is on
 * unless a test opts out. Each instance writes into its own temp directory.
 */
internal class TestDataBackupManager(
    database: PhoenixDatabase,
    val profilePreferencesRepository: ProfilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
    val userProfileRepository: UserProfileRepository = createTestUserProfileRepository(
        database,
        profilePreferencesRepository,
    ),
    portalTokenStorage: PortalTokenStorage? = null,
    preferencesManager: PreferencesManager? = null,
    pendingProfileDeletionStore: PendingProfileDeletionStore? = null,
    private val stagingAreaFactory: (() -> BackupImportStagingArea)? = null,
    override val includeRawTelemetryInBackups: Boolean = true,
    private val sessionBackupDirectory: File = kotlin.io.path.createTempDirectory("phoenix-backup-test").toFile(),
) : BaseDataBackupManager(
    database,
    profilePreferencesRepository,
    userProfileRepository,
    portalTokenStorage,
    preferencesManager,
    pendingProfileDeletionStore,
) {
    var lastWriterPath: String? = null
        private set

    override fun createBackupWriter(): BackupJsonWriter {
        val tempFile = File.createTempFile("backup-test-", ".json", sessionBackupDirectory)
        lastWriterPath = tempFile.absolutePath
        return BackupJsonWriter(tempFile.absolutePath)
    }

    suspend fun exportToCachePublic(): String = exportToCache()

    suspend fun importFromStreamPublic(source: BackupStreamSource): Result<ImportResult> = importFromStream(source)

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

    override suspend fun importFromFile(filePath: String): Result<ImportResult> = error("Not needed for tests")

    override suspend fun shareBackup() = Unit

    override fun getSessionBackupDirectory(): String = sessionBackupDirectory.absolutePath

    override fun listBackupFileSizes(): List<Long> =
        sessionBackupDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.length() }
            ?: emptyList()

    override fun openBackupFolder() = Unit

    override fun pruneOldBackups(keepCount: Int) = Unit
}

internal fun createTestUserProfileRepository(
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

private class StringBackupStreamSource(private val json: String) : BackupStreamSource {
    private var reader: StringReader? = null

    override fun open() {
        reader = StringReader(json)
    }

    override fun close() {
        reader?.close()
        reader = null
    }

    override fun read(): Int = reader?.read() ?: -1

    override fun read(buffer: CharArray, offset: Int, length: Int): Int =
        reader?.read(buffer, offset, length) ?: -1
}
