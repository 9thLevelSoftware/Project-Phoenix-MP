@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.data.local.DatabaseFileNames
import com.devil.phoenixproject.data.local.legacyLibraryRootPath
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

internal actual suspend fun shareDatabaseFiles(): Boolean = try {
    val archive = withContext(Dispatchers.Default) { buildDatabaseArchive() }
    if (archive != null) {
        presentShareSheet(listOf(NSURL.fileURLWithPath(archive))) {}
        true
    } else {
        false
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.e(e) { "Database file export failed" }
    false
}

/**
 * Copies the candidates into a scratch folder under tmp, then lets NSFileCoordinator zip that
 * folder (the documented ForUploading behaviour for directories). Only the copies are touched.
 */
private fun buildDatabaseArchive(): String? {
    val fileManager = NSFileManager.defaultManager
    val exists = { path: String -> fileManager.fileExistsAtPath(path) }
    // Guard A involves the SQLiter directory; guards B/C also the pre-SQLiter Library-root vitruvian.db.
    val sqliterDirectory = DatabaseFileContext.databasePath(DatabaseFileNames.TARGET, null).substringBeforeLast('/')
    val libraryDirectory = legacyLibraryRootPath().substringBeforeLast('/')
    val entries = DatabaseFileExport.entries("sqliter", sqliterDirectory, exists) +
        DatabaseFileExport.entries("library", libraryDirectory, exists)
    if (entries.isEmpty()) return null

    val tmp = NSTemporaryDirectory().trimEnd('/')
    val staging = "$tmp/phoenix-database-files"
    val archive = "$tmp/${DatabaseFileExport.ARCHIVE_NAME}"
    fileManager.removeItemAtPath(staging, error = null)
    fileManager.removeItemAtPath(archive, error = null)
    try {
        for (entry in entries) {
            val destination = "$staging/${entry.entryName}"
            check(
                fileManager.createDirectoryAtPath(
                    destination.substringBeforeLast('/'),
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                ),
            ) { "Could not create the export folder" }
            check(fileManager.copyItemAtPath(entry.path, toPath = destination, error = null)) {
                "Could not copy ${entry.entryName} for export"
            }
        }
        return if (zipDirectory(staging, archive)) archive else null
    } finally {
        fileManager.removeItemAtPath(staging, error = null)
    }
}

private fun zipDirectory(directory: String, archive: String): Boolean = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    var copied = false
    NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
        NSURL.fileURLWithPath(directory, isDirectory = true),
        options = NSFileCoordinatorReadingForUploading,
        error = error.ptr,
        byAccessor = { zipped: NSURL? ->
            // The generated zip only lives for the duration of this block.
            val zippedPath = zipped?.path
            if (zippedPath != null) {
                copied = NSFileManager.defaultManager.copyItemAtPath(zippedPath, toPath = archive, error = null)
            }
        },
    )
    error.value?.let { Logger.w { "Database export zip failed: ${it.localizedDescription}" } }
    copied
}
