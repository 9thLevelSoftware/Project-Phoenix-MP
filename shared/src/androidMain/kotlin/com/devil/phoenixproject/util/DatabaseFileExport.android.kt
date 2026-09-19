package com.devil.phoenixproject.util

import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.DatabaseFileNames
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

internal actual suspend fun shareDatabaseFiles(): Boolean {
    val context = AndroidCrashLog.context() ?: return false
    return try {
        val archive = withContext(Dispatchers.IO) {
            // All candidates live in the app's databases directory (see AndroidDatabaseFileOperations).
            val directory = context.getDatabasePath(DatabaseFileNames.TARGET).parentFile ?: return@withContext null
            val entries = DatabaseFileExport.entries("databases", directory.path) { File(it).isFile }
            if (entries.isEmpty()) return@withContext null
            // cache/exports/ is already exposed through the app's FileProvider (file_paths.xml).
            File(context.cacheDir, "exports/${DatabaseFileExport.ARCHIVE_NAME}").also {
                writeDatabaseArchive(entries, it)
            }
        } ?: return false
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(e) { "Database file export failed" }
        false
    }
}
