package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.util.CsvImportResult
import com.devil.phoenixproject.util.PhoenixCsvCodec
import com.devil.phoenixproject.util.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

private data class CsvDuplicateKey(val timestamp: Long, val exerciseName: String)

/**
 * Shared workout-history CSV import pipeline used by Android and iOS [com.devil.phoenixproject.util.CsvImporter]
 * implementations.
 *
 * - Reads raw CSV text via [readCsvContent] on [Dispatchers.IO].
 * - Stamps every parsed session with the active [profileId] before duplicate-key lookup and persistence.
 * - Rethrows [kotlinx.coroutines.CancellationException] from both file-read and per-row save catch blocks.
 */
internal suspend fun importWorkoutHistoryCsv(
    uri: String,
    profileId: String,
    workoutRepository: WorkoutRepository,
    readCsvContent: suspend (String) -> String?,
): CsvImportResult = withContext(Dispatchers.IO) {
    try {
        val csvContent = readCsvContent(uri) ?: return@withContext CsvImportResult(
            imported = 0,
            skipped = 0,
            failed = 0,
            errors = listOf("Could not open file: $uri"),
        )

        val (sessions, parseErrors) = PhoenixCsvCodec.parseWorkoutHistory(csvContent)

        if (sessions.isEmpty() && parseErrors.isNotEmpty()) {
            return@withContext CsvImportResult(
                imported = 0,
                skipped = 0,
                failed = parseErrors.size,
                errors = parseErrors,
            )
        }

        // Pre-load existing sessions for duplicate detection (one DB round-trip).
        // MutableSet so intra-file duplicates are also caught as they are imported.
        val existingSessions = workoutRepository.getRecentSessionsSync(profileId = profileId, limit = Int.MAX_VALUE)
        val existingKeys = existingSessions.map { s ->
            CsvDuplicateKey(s.timestamp, s.exerciseName ?: s.exerciseId ?: "")
        }.toMutableSet()

        var imported = 0
        var skipped = 0
        var saveFailures = 0
        val importErrors = parseErrors.toMutableList()

        for (parsedSession in sessions) {
            // Stamp each imported session with the active profile's ID
            // so it appears in the correct user's history.
            val session = parsedSession.copy(profileId = profileId)
            val key = CsvDuplicateKey(session.timestamp, session.exerciseName ?: session.exerciseId ?: "")
            if (key in existingKeys) {
                skipped++
                continue
            }
            try {
                workoutRepository.saveSession(session)
                existingKeys.add(key) // Track intra-file duplicates
                imported++
            } catch (e: Exception) {
                // CancellationException extends Exception. Rethrow it so a cancelled
                // import stops instead of recording a failed row and writing the rest.
                e.rethrowIfCancellation()
                Logger.w(tag = "CsvImporter") { "Failed to save row: ${e.message}" }
                if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                    skipped++
                } else {
                    saveFailures++
                    importErrors.add(
                        "Failed to save session (${session.exerciseName}): ${e.message}",
                    )
                }
            }
        }

        Logger.i(tag = "CsvImporter") {
            "Import complete: $imported imported, $skipped skipped, ${parseErrors.size} parse errors, $saveFailures save failures"
        }

        CsvImportResult(
            imported = imported,
            skipped = skipped,
            failed = parseErrors.size + saveFailures,
            errors = importErrors,
        )
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        Logger.e(throwable = e, tag = "CsvImporter") { "Import failed" }
        CsvImportResult(
            imported = 0,
            skipped = 0,
            failed = 0,
            errors = listOf("Import failed: ${e.message}"),
        )
    }
}
