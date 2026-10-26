package com.devil.phoenixproject.util

/** Custom file destinations are intentionally unavailable on tvOS. */
internal class TvosBackupDestinationResolver : BackupDestinationResolver {
    override suspend fun isAccessible(destination: BackupDestination.Custom): Boolean = false

    override suspend fun writeFile(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String> = Result.failure(UnsupportedOperationException("Custom backups are not available on tvOS."))
}
