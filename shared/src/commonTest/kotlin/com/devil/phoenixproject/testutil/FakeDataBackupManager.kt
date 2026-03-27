package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.util.BackupContent
import com.devil.phoenixproject.util.BackupData
import com.devil.phoenixproject.util.BackupProgress
import com.devil.phoenixproject.util.BackupStats
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.ImportResult

/**
 * Fake DataBackupManager for testing.
 * All operations are no-ops that return success.
 */
class FakeDataBackupManager : DataBackupManager {

    var lastExportedSessionId: String? = null
        private set

    override suspend fun exportAllData(): BackupData = BackupData(
        version = 1,
        exportedAt = "2024-01-01T00:00:00Z",
        appVersion = "test",
        data = BackupContent(),
    )

    override suspend fun exportToJson(): String = "{}"

    override suspend fun importFromJson(jsonString: String): Result<ImportResult> = Result.success(
        ImportResult(
            sessionsImported = 0,
            sessionsSkipped = 0,
            metricsImported = 0,
            routinesImported = 0,
            routinesSkipped = 0,
            routineExercisesImported = 0,
            personalRecordsImported = 0,
            personalRecordsSkipped = 0,
        ),
    )

    override suspend fun saveToFile(backup: BackupData): Result<String> = Result.success("/fake/backup.json")

    override suspend fun exportToFile(onProgress: (BackupProgress) -> Unit): Result<String> = Result.success("/fake/backup.json")

    override suspend fun importFromFile(filePath: String): Result<ImportResult> = importFromJson("{}")

    override suspend fun getShareableContent(): String = "{}"

    override suspend fun shareBackup() = Unit

    override suspend fun exportSession(sessionId: String): Result<String> {
        lastExportedSessionId = sessionId
        return Result.success("/fake/phoenix-workout-2024-01-01-$sessionId.json")
    }

    override suspend fun getBackupStats(): BackupStats = BackupStats(fileCount = 0, totalBytes = 0)

    override fun openBackupFolder() = Unit
}
