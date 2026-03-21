package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
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
class IosCsvImporter(
    private val workoutRepository: WorkoutRepository
) : CsvImporter {

    override suspend fun importFromCsv(uri: String): CsvImportResult {
        return withContext(Dispatchers.IO) {
            try {
                val csvContent = NSString.stringWithContentsOfFile(
                    uri,
                    encoding = NSUTF8StringEncoding,
                    error = null
                ) ?: return@withContext CsvImportResult(
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
                val existingSessions = workoutRepository.getRecentSessionsSync(limit = Int.MAX_VALUE)
                val existingKeys = existingSessions.map { s ->
                    DuplicateKey(s.timestamp, s.exerciseName ?: s.exerciseId ?: "")
                }.toMutableSet()

                var imported = 0
                var skipped = 0
                val importErrors = parseErrors.toMutableList()

                for (session in sessions) {
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
