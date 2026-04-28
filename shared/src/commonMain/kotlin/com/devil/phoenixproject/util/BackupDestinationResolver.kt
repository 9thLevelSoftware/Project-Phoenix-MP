package com.devil.phoenixproject.util

/**
 * Platform abstraction for writing files to custom backup destinations.
 *
 * Implementations handle platform-specific directory access:
 * - **Android**: [DocumentFile] + persisted URI permissions via [ContentResolver].
 * - **iOS**: Security-scoped bookmarks resolved from Base64 [NSData].
 *
 * Designed as an interface (not expect/actual) so tests can supply a
 * [FakeBackupDestinationResolver] without platform dependencies.
 */
interface BackupDestinationResolver {

    /**
     * Verify that the app still has read+write access to [destination].
     *
     * On Android this checks [ContentResolver.getPersistedUriPermissions] and
     * [DocumentFile.canWrite]. On iOS it resolves the bookmark and probes
     * [startAccessingSecurityScopedResource].
     *
     * @return true if the destination is reachable and writable.
     */
    suspend fun isAccessible(destination: BackupDestination.Custom): Boolean

    /**
     * Write [tempFilePath] to [destination] under [fileName].
     *
     * The caller is responsible for creating a valid temp file first.
     * On success the returned [String] is the platform URI/path of the written file.
     *
     * @return [Result.success] with the destination file path/URI, or
     *         [Result.failure] with the underlying exception.
     */
    suspend fun writeFile(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String>

    /**
     * List JSON backup file names present in [destination].
     *
     * Only `.json` files are returned; other file types are filtered out.
     */
    suspend fun listFiles(destination: BackupDestination.Custom): List<String>
}
