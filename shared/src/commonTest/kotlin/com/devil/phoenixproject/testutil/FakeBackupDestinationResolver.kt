package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupDestinationResolver

/**
 * Fake implementation of [BackupDestinationResolver] for unit tests.
 *
 * Captures all write calls for assertion and allows configuring
 * accessibility and write results per-test.
 */
class FakeBackupDestinationResolver : BackupDestinationResolver {

    /** Controls what [isAccessible] returns. */
    var isAccessibleResult = true

    /** Controls what [writeFile] returns. */
    var writeFileResult: Result<String> = Result.success("fake://written")

    /** Captures all [writeFile] invocations as (destination, fileName, tempFilePath). */
    val writtenFiles = mutableListOf<Triple<BackupDestination.Custom, String, String>>()

    /** Controls what [listFiles] returns. */
    var listFilesResult: List<String> = emptyList()

    override suspend fun isAccessible(destination: BackupDestination.Custom): Boolean =
        isAccessibleResult

    override suspend fun writeFile(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String> {
        writtenFiles.add(Triple(destination, fileName, tempFilePath))
        return writeFileResult
    }

    override suspend fun listFiles(destination: BackupDestination.Custom): List<String> =
        listFilesResult
}
