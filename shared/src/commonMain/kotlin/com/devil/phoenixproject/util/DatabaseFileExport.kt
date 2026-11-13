package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY
import com.devil.phoenixproject.data.local.DatabaseFileNames
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One database candidate artifact, named for diagnostics only. Bytes are never copied. */
internal data class DatabaseDiagnosticFile(val name: String, val sizeBytes: Long)

/** One verified non-sensitive diagnostics line: artifact name + byte size, nothing else. */
@Serializable
internal data class DatabaseDiagnosticsLine(val name: String, val sizeBytes: Long)

@Serializable
internal data class DatabaseDiagnosticsReport(
    val purpose: String,
    /** Explicit on the wire: the archive verifiably contains no raw database bytes. */
    val rawDatabaseBytesIncluded: Boolean,
    val candidates: List<DatabaseDiagnosticsLine>,
)

/**
 * Support export for startup failures that block on persisted database files (#764), where
 * Phoenix refuses to pick between database copies itself.
 *
 * SIGNOFF DECISION 5 (binding, integration-model §7, issue #1384 P3): raw database bytes —
 * main files, `-wal`, `-shm`, `-journal` sidecars and quarantined candidates — are NEVER
 * shared, build-wide, in integration-capable builds (all current builds), including while
 * the Intervals.icu integration is disabled, from the startup-failure screen and for
 * unsafe-to-open dual databases. DELETE cannot scrub SQLite pages/WAL frames, so a raw
 * archive would leak Intervals connection metadata, secret key references and completion
 * rows into support channels. The support export carries only verified non-sensitive
 * diagnostics: candidate artifact names and byte sizes. Files are stat'ed, never read and
 * never opened by SQLite. Explicit JSON training backups remain available. Re-enabling raw
 * archives requires separately verified logical rebuild/redaction — there is deliberately
 * no toggle: the raw-zip suppression is enforced only by this construction and the
 * archive-content tests that pin it.
 */
internal object DatabaseFileExport {
    const val ARCHIVE_NAME = "phoenix-support-diagnostics.zip"
    const val DIAGNOSTICS_ENTRY_NAME = "diagnostics.json"

    /**
     * The pre-rename raw-database archive name. Older builds wrote raw DB/WAL bytes under
     * this name into the same shareable cache/exports location; deletion must remove it
     * too or the suppressed raw bytes stay on disk and shareable (signoff decision 5).
     */
    const val LEGACY_RAW_ARCHIVE_NAME = "phoenix-database-files.zip"

    /**
     * Serializes exports process-wide. The screen's own "exporting" flag is lost when an Android
     * activity is recreated, while a blocking zip keeps running; two runs must never write the
     * same archive or staging folder at once.
     */
    val mutex = Mutex()

    private val DATABASE_NAMES = listOf(
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.TARGET,
        DatabaseFileNames.RECOVERY,
        DatabaseFileNames.STAGING,
    )
    private val SIDECAR_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    /**
     * Existing candidate files in [directory], named `folder/file` in the report — main files
     * plus their -wal/-shm/-journal sidecars. Metadata only: each candidate is stat'ed via
     * [sizeOf] and never read. Separate folders keep same-named files from different locations
     * (e.g. two `vitruvian.db`) apart.
     */
    fun diagnosticFiles(
        folder: String,
        directory: String,
        isFile: (String) -> Boolean,
        sizeOf: (String) -> Long,
    ): List<DatabaseDiagnosticFile> =
        DATABASE_NAMES.flatMap { name -> SIDECAR_SUFFIXES.map { suffix -> "$name$suffix" } }
            .map { fileName -> "$folder/$fileName" to "$directory/$fileName" }
            .filter { (_, path) -> isFile(path) }
            .map { (name, path) -> DatabaseDiagnosticFile(name, sizeOf(path)) }

    /**
     * Candidates a DB_DUAL_DATABASES recovery moved aside (#764) live in [DATABASE_QUARANTINE_DIRECTORY]
     * beside the databases. [relativeFiles] are the files under it, relative to it; each is
     * reported as `quarantine/<relative path>` with its byte size — quarantined bytes are
     * exactly as unsafe to share as live database bytes and are never copied either.
     */
    fun quarantineDiagnosticFiles(
        databaseDirectory: String,
        relativeFiles: List<String>,
        sizeOf: (String) -> Long,
    ): List<DatabaseDiagnosticFile> =
        relativeFiles.sorted().map { relative ->
            DatabaseDiagnosticFile(
                name = "quarantine/$relative",
                sizeBytes = sizeOf("$databaseDirectory/$DATABASE_QUARANTINE_DIRECTORY/$relative"),
            )
        }

    /**
     * The entire support-export payload: a verified non-sensitive diagnostics report. This is
     * the ONLY content [shareDatabaseFiles] may ever place in an archive. Names are
     * app-generated constants or timestamped quarantine names; sizes are file metadata.
     */
    fun diagnosticsJson(files: List<DatabaseDiagnosticFile>): String = Json.encodeToString(
        DatabaseDiagnosticsReport(
            purpose = "Project Phoenix startup support diagnostics. Database contents are never included.",
            rawDatabaseBytesIncluded = false,
            candidates = files.map { DatabaseDiagnosticsLine(it.name, it.sizeBytes) },
        ),
    )
}

/**
 * Builds the support archive and opens the platform share sheet. Returns false when there was
 * nothing to report or the archive/share failed; never throws and never modifies or reads the
 * candidate files.
 *
 * Suppressed surface (signoff decision 5): this must never place raw DB/WAL/SHM/journal/
 * quarantine bytes in the archive — only [DatabaseFileExport.diagnosticsJson].
 */
internal expect suspend fun shareDatabaseFiles(): Boolean

/**
 * Removes a previously exported archive. It is kept after sharing because the receiving app reads
 * it asynchronously, so it is removed once startup succeeds instead. Never throws.
 */
internal expect fun deleteDatabaseExportArchive()
