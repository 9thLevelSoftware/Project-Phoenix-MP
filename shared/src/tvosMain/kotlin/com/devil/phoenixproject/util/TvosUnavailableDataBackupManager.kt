package com.devil.phoenixproject.util

/** tvOS deliberately exposes no backup/import UI; never pretend a file export succeeded. */
internal class TvosUnavailableDataBackupManager : DataBackupManager {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Phoenix backup files are unavailable on tvOS.")

    override suspend fun exportAllData(): BackupData = unsupported()

    override suspend fun exportToJson(): String = unsupported()

    override suspend fun importFromJson(jsonString: String): Result<ImportResult> =
        Result.failure(UnsupportedOperationException("Phoenix backup files are unavailable on tvOS."))

    override suspend fun exportToFile(onProgress: (BackupProgress) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Phoenix backup files are unavailable on tvOS."))

    override suspend fun importFromFile(filePath: String): Result<ImportResult> =
        Result.failure(UnsupportedOperationException("Phoenix backup files are unavailable on tvOS."))

    override suspend fun shareBackup() = Unit

    override suspend fun exportSession(sessionId: String): Result<String> =
        Result.failure(UnsupportedOperationException("Phoenix backup files are unavailable on tvOS."))

    override suspend fun exportRoutine(routineSessionId: String): Result<String> =
        Result.failure(UnsupportedOperationException("Phoenix backup files are unavailable on tvOS."))

    override suspend fun getBackupStats(): BackupStats = BackupStats(fileCount = 0, totalBytes = 0)

    override fun openBackupFolder() = Unit
}
