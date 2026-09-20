package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.local.DatabaseFileNames
import kotlinx.coroutines.sync.Mutex

/** One file to copy into the support archive: [entryName] inside the zip, [path] on disk. */
internal data class DatabaseExportEntry(val entryName: String, val path: String)

/**
 * Support export for startup failures that block on persisted database files (#764), where
 * Phoenix refuses to pick between database copies itself. Every candidate that exists (main files
 * plus their -wal/-shm/-journal sidecars) is copied byte-for-byte into one zip. The originals are
 * only read: never opened by SQLite, checkpointed, moved or deleted.
 */
internal object DatabaseFileExport {
    const val ARCHIVE_NAME = "phoenix-database-files.zip"

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
     * Existing candidate files in [directory], named `folder/file` in the archive. Separate folders
     * keep same-named files from different locations (e.g. two `vitruvian.db`) apart.
     */
    fun entries(folder: String, directory: String, exists: (String) -> Boolean): List<DatabaseExportEntry> =
        DATABASE_NAMES.flatMap { name -> SIDECAR_SUFFIXES.map { suffix -> "$name$suffix" } }
            .map { fileName -> DatabaseExportEntry("$folder/$fileName", "$directory/$fileName") }
            .filter { exists(it.path) }
}

/**
 * Zips the database candidate files and opens the platform share sheet. Returns false when there
 * was nothing to export or the archive/share failed; never throws and never modifies the originals.
 */
internal expect suspend fun shareDatabaseFiles(): Boolean

/**
 * Removes a previously exported archive. It is kept after sharing because the receiving app reads
 * it asynchronously, so it is removed once startup succeeds instead. Never throws.
 */
internal expect fun deleteDatabaseExportArchive()
