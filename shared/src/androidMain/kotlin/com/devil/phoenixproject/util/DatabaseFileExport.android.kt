package com.devil.phoenixproject.util

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

/**
 * Writes the support archive: a single verified non-sensitive `diagnostics.json` entry
 * ([DatabaseFileExport.diagnosticsJson]). The candidate files themselves are stat'ed only —
 * raw DB/WAL/SHM/journal/quarantine bytes must never enter an archive (signoff decision 5).
 */
internal fun writeDiagnosticsArchive(files: List<DatabaseDiagnosticFile>, archive: File) {
    archive.parentFile?.mkdirs()
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME))
        zip.write(DatabaseFileExport.diagnosticsJson(files).encodeToByteArray())
        zip.closeEntry()
    }
}

/** cache/exports/ is already exposed through the app's FileProvider (file_paths.xml). */
internal fun databaseExportArchive(cacheDir: File): File = File(cacheDir, "exports/${DatabaseFileExport.ARCHIVE_NAME}")

/**
 * Writes the diagnostics-only support archive for every candidate in [databasesDir] under
 * [cacheDir], replacing a previous one. Returns null (and leaves no archive) when there is
 * nothing to report. Never reads, opens, checkpoints, moves or deletes the candidates —
 * including unsafe-to-open dual databases and quarantined files, whose bytes are as
 * unsupportable to share as live database bytes (signoff decision 5).
 */
internal fun buildDatabaseArchive(databasesDir: File, cacheDir: File): File? {
    val archive = databaseExportArchive(cacheDir)
    archive.delete()
    val quarantine = File(databasesDir, DATABASE_QUARANTINE_DIRECTORY)
    val quarantined = quarantine.walkTopDown().filter(File::isFile)
        .map { it.relativeTo(quarantine).invariantSeparatorsPath }
        .toList()
    val files = DatabaseFileExport.diagnosticFiles("databases", databasesDir.path, { File(it).isFile }, { File(it).length() }) +
        DatabaseFileExport.quarantineDiagnosticFiles(databasesDir.path, quarantined) { File(it).length() }
    if (files.isEmpty()) return null
    return archive.also { writeDiagnosticsArchive(files, it) }
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
            context.launchFileProviderShareChooser(
                uri = uri,
                mimeType = "application/zip",
                chooserTitle = "Export database files",
                subject = "Project Phoenix database files",
                newTaskOnSendIntent = false,
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
        // The pre-rename archive held raw DB/WAL bytes (signoff decision 5): a leftover
        // copy stays shareable through the exports FileProvider path, so it goes too.
        File(context.cacheDir, "exports/${DatabaseFileExport.LEGACY_RAW_ARCHIVE_NAME}").delete()
    } catch (_: Exception) {
        // A leftover archive stays in app-private cache; never fail startup for it.
    }
}
