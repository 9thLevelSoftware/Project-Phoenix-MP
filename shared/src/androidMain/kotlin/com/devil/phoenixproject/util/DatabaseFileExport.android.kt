package com.devil.phoenixproject.util

import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY
import com.devil.phoenixproject.data.local.DatabaseFileNames
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Copies each entry's bytes into a zip at [archive]; the source files are only read. */
internal fun writeDatabaseArchive(entries: List<DatabaseExportEntry>, archive: File) {
    archive.parentFile?.mkdirs()
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        for (entry in entries) {
            zip.putNextEntry(ZipEntry(entry.entryName))
            File(entry.path).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/** cache/exports/ is already exposed through the app's FileProvider (file_paths.xml). */
internal fun databaseExportArchive(cacheDir: File): File = File(cacheDir, "exports/${DatabaseFileExport.ARCHIVE_NAME}")

/**
 * Zips every candidate in [databasesDir] into the export archive under [cacheDir], replacing a
 * previous one. Returns null (and leaves no archive) when there is nothing to export.
 */
internal fun buildDatabaseArchive(databasesDir: File, cacheDir: File): File? {
    val archive = databaseExportArchive(cacheDir)
    archive.delete()
    val quarantine = File(databasesDir, DATABASE_QUARANTINE_DIRECTORY)
    val quarantined = quarantine.walkTopDown().filter(File::isFile)
        .map { it.relativeTo(quarantine).invariantSeparatorsPath }
        .toList()
    val entries = DatabaseFileExport.entries("databases", databasesDir.path) { File(it).isFile } +
        DatabaseFileExport.quarantineEntries(databasesDir.path, quarantined)
    if (entries.isEmpty()) return null
    return archive.also { writeDatabaseArchive(entries, it) }
}

// The Context comes from the crash-log install in PhoenixApp.onCreate (PR 25), which runs before any UI.
internal actual suspend fun shareDatabaseFiles(): Boolean {
    val context = AndroidCrashLog.context() ?: return false
    return try {
        DatabaseFileExport.mutex.withLock {
            val archive = withContext(Dispatchers.IO) {
                // All candidates live in the app's databases directory (see AndroidDatabaseFileOperations).
                val databasesDir = context.getDatabasePath(DatabaseFileNames.TARGET).parentFile
                    ?: return@withContext null
                buildDatabaseArchive(databasesDir, context.cacheDir)
            } ?: return@withLock false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix database files")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Export database files").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(e) { "Database file export failed" }
        false
    }
}

internal actual fun deleteDatabaseExportArchive() {
    val context = AndroidCrashLog.context() ?: return
    try {
        databaseExportArchive(context.cacheDir).delete()
    } catch (_: Exception) {
        // A leftover archive stays in app-private cache; never fail startup for it.
    }
}
