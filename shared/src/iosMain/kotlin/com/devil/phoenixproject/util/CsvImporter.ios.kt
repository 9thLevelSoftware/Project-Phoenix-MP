@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.integration.importWorkoutHistoryCsv
import com.devil.phoenixproject.data.repository.WorkoutRepository
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS implementation of [CsvImporter].
 *
 * Reads CSV files via Foundation's NSString.stringWithContentsOfFile.
 * The [uri] parameter is expected to be a file system path.
 *
 * Duplicate detection: for each parsed session we check if any existing session
 * has the same timestamp and exercise name. If so, the row is skipped.
 */
internal class IosCsvImporter(private val workoutRepository: WorkoutRepository) : CsvImporter {

    override suspend fun importFromCsv(uri: String, profileId: String): CsvImportResult =
        importWorkoutHistoryCsv(uri, profileId, workoutRepository) { path ->
            NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
        }
}
