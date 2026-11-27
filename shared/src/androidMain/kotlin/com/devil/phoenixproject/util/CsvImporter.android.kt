package com.devil.phoenixproject.util

import android.content.Context
import androidx.core.net.toUri
import com.devil.phoenixproject.data.integration.importWorkoutHistoryCsv
import com.devil.phoenixproject.data.repository.WorkoutRepository

/**
 * Android implementation of [CsvImporter].
 *
 * Reads CSV files via [android.content.ContentResolver] so that content:// URIs
 * returned by the system file picker work correctly.
 *
 * Duplicate detection: for each parsed session we check if any existing session
 * has the same timestamp and exercise name. If so, the row is skipped.
 */
class AndroidCsvImporter(private val context: Context, private val workoutRepository: WorkoutRepository) : CsvImporter {

    override suspend fun importFromCsv(uri: String, profileId: String): CsvImportResult =
        importWorkoutHistoryCsv(uri, profileId, workoutRepository) { path ->
            val contentUri = path.toUri()
            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        }
}
