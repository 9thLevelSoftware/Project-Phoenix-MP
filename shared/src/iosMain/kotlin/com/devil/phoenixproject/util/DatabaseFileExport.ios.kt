@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY
import com.devil.phoenixproject.data.local.DatabaseFileNames
import com.devil.phoenixproject.data.local.legacyLibraryRootPath
import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

/** Bounds the wait for UIKit's presentation completion so the screen can never stay "exporting". */
private const val PRESENTATION_TIMEOUT_MS = 10_000L

internal actual suspend fun shareDatabaseFiles(): Boolean = try {
    DatabaseFileExport.mutex.withLock {
        val archive = withContext(Dispatchers.Default) { buildDatabaseArchive() }
        if (archive == null) {
            false
        } else {
            withTimeoutOrNull(PRESENTATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    presentShareSheet(
                        items = listOf(NSURL.fileURLWithPath(archive)),
                        onShown = { if (continuation.isActive) continuation.resume(true) },
                        onNotShown = { if (continuation.isActive) continuation.resume(false) },
                    )
                }
            } ?: false
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.e(e) { "Database file export failed" }
    false
}

internal actual fun deleteDatabaseExportArchive() {
    try {
        NSFileManager.defaultManager.removeItemAtPath(archivePath(), error = null)
    } catch (_: Exception) {
        // A leftover archive stays in the app's tmp folder; never fail startup for it.
    }
}

private fun archivePath(): String = "${NSTemporaryDirectory().trimEnd('/')}/${DatabaseFileExport.ARCHIVE_NAME}"

/**
 * Stages the candidates in a scratch folder under tmp, then lets NSFileCoordinator zip that folder
 * (the documented ForUploading behaviour for directories). Staging uses hard links where possible
 * so large databases are not duplicated; removing a link never affects the original file.
 */
private fun buildDatabaseArchive(): String? {
    val fileManager = NSFileManager.defaultManager
    val archive = archivePath()
    fileManager.removeItemAtPath(archive, error = null)
    val exists = { path: String -> fileManager.fileExistsAtPath(path) }
    // Guard A involves the SQLiter directory; guards B/C also the pre-SQLiter Library-root vitruvian.db.
    val sqliterDirectory = DatabaseFileContext.databasePath(DatabaseFileNames.TARGET, null).substringBeforeLast('/')
    val libraryDirectory = legacyLibraryRootPath().substringBeforeLast('/')
    val entries = DatabaseFileExport.entries("sqliter", sqliterDirectory, exists) +
        DatabaseFileExport.entries("library", libraryDirectory, exists) +
        DatabaseFileExport.quarantineEntries(sqliterDirectory, quarantinedFiles(sqliterDirectory))
    if (entries.isEmpty()) return null

    val staging = "${NSTemporaryDirectory().trimEnd('/')}/phoenix-database-files"
    fileManager.removeItemAtPath(staging, error = null)
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
            val staged = fileManager.linkItemAtPath(entry.path, toPath = destination, error = null) ||
                fileManager.copyItemAtPath(entry.path, toPath = destination, error = null)
            check(staged) { "Could not stage ${entry.entryName} for export" }
        }
        return if (zipDirectory(staging, archive)) archive else null
    } finally {
        fileManager.removeItemAtPath(staging, error = null)
    }
}

private fun zipDirectory(directory: String, archive: String): Boolean = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var moved = false
    NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
        NSURL.fileURLWithPath(directory, isDirectory = true),
        options = NSFileCoordinatorReadingForUploading,
        error = error.ptr,
        byAccessor = { zipped ->
            // The generated zip is a temporary file owned by this block and deleted after it returns,
            // so move it out here.
            val zippedPath = zipped?.path
            if (zippedPath != null) {
                moved = NSFileManager.defaultManager.moveItemAtPath(zippedPath, toPath = archive, error = null)
            }
        },
    )
    error.value?.let { Logger.w { "Database export zip failed: ${it.localizedDescription}" } }
    moved
}

/** Regular files under the quarantine folder (#764), relative to it. */
private fun quarantinedFiles(sqliterDirectory: String): List<String> {
    val fileManager = NSFileManager.defaultManager
    val root = "$sqliterDirectory/$DATABASE_QUARANTINE_DIRECTORY"
    val subpaths = fileManager.subpathsOfDirectoryAtPath(root, error = null).orEmpty()
    return subpaths.mapNotNull { it as? String }.filter { relative ->
        fileManager.attributesOfItemAtPath("$root/$relative", error = null)?.get(NSFileType) == NSFileTypeRegular
    }
}
