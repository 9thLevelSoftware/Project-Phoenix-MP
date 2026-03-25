package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [CsvImporter].
 *
 * Reads CSV files via [android.content.ContentResolver] so that content:// URIs
 * returned by the system file picker work correctly.
 *
 * Duplicate detection: for each parsed session we check if any existing session
 * has the same timestamp and exercise name. If so, the row is skipped.
 */
class AndroidCsvImporter(
    private val context: Context,
    private val workoutRepository: WorkoutRepository
) : CsvImporter {

    override suspend fun importFromCsv(uri: String, profileId: String): CsvImportResult {
        return withContext(Dispatchers.IO) {
            try {
                val contentUri = Uri.parse(uri)
                val csvContent = context.contentResolver.openInputStream(contentUri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: return@withContext CsvImportResult(
                    imported = 0, skipped = 0, failed = 0,
                    errors = listOf("Could not open file: $uri")
                )

                val (sessions, parseErrors) = CsvParser.parseWorkoutHistory(csvContent)

                if (sessions.isEmpty() && parseErrors.isNotEmpty()) {
                    return@withContext CsvImportResult(
                        imported = 0, skipped = 0, failed = parseErrors.size,
                        errors = parseErrors
                    )
                }

                // Pre-load existing sessions for duplicate detection (one DB round-trip).
                // MutableSet so intra-file duplicates are also caught as they are imported.
                val existingSessions = workoutRepository.getRecentSessionsSync(profileId = profileId, limit = Int.MAX_VALUE)
                val existingKeys = existingSessions.map { s ->
                    DuplicateKey(s.timestamp, s.exerciseName ?: s.exerciseId ?: "")
                }.toMutableSet()

                var imported = 0
                var skipped = 0
                val importErrors = parseErrors.toMutableList()

                for (parsedSession in sessions) {
                    // Stamp each imported session with the active profile's ID
                    // so it appears in the correct user's history.
                    val session = parsedSession.copy(profileId = profileId)
                    val key = DuplicateKey(session.timestamp, session.exerciseName ?: "")
                    if (key in existingKeys) {
                        skipped++
                        continue
                    }
                    try {
                        workoutRepository.saveSession(session)
                        existingKeys.add(key) // Track intra-file duplicates
                        imported++
                    } catch (e: Exception) {
                        Logger.w("CsvImporter") { "Failed to save row: ${e.message}" }
                        if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                            skipped++
                        } else {
                            importErrors.add(
                                "Failed to save session (${session.exerciseName}): ${e.message}"
                            )
                        }
                    }
                }

                Logger.i("CsvImporter") { "Import complete: $imported imported, $skipped skipped, ${parseErrors.size} parse errors" }

                CsvImportResult(
                    imported = imported,
                    skipped = skipped,
                    failed = parseErrors.size,
                    errors = importErrors
                )
            } catch (e: Exception) {
                Logger.e("CsvImporter", e) { "Import failed" }
                CsvImportResult(
                    imported = 0, skipped = 0, failed = 0,
                    errors = listOf("Import failed: ${e.message}")
                )
            }
        }
    }

    private data class DuplicateKey(val timestamp: Long, val exerciseName: String)
}
