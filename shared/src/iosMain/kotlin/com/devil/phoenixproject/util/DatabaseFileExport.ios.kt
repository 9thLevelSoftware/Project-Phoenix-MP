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
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

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
        // The pre-rename archive held raw DB/WAL bytes (signoff decision 5); a leftover
        // copy would keep the suppressed raw bytes on disk.
        NSFileManager.defaultManager.removeItemAtPath(
            legacyRawArchivePath(),
            error = null,
        )
        // Legacy builds also STAGED raw DB/WAL candidates in a directory next to the zip
        // and only removed it in the export's finally — a crash mid-export left the
        // suppressed raw bytes there. It goes with the archive.
        NSFileManager.defaultManager.removeItemAtPath(
            legacyRawStagingPath(),
            error = null,
        )
    } catch (_: Exception) {
        // A leftover archive stays in the app's tmp folder; never fail startup for it.
    }
}

private fun archivePath(): String = "${NSTemporaryDirectory().trimEnd('/')}/${DatabaseFileExport.ARCHIVE_NAME}"

private fun legacyRawArchivePath(): String =
    "${NSTemporaryDirectory().trimEnd('/')}/${DatabaseFileExport.LEGACY_RAW_ARCHIVE_NAME}"

private fun legacyRawStagingPath(): String =
    "${NSTemporaryDirectory().trimEnd('/')}/phoenix-database-files"

/**
 * Builds the support archive from verified non-sensitive diagnostics only.
 *
 * Suppressed surface (signoff decision 5, integration-model §7): raw DB/WAL/SHM/journal/
 * quarantine bytes — including the SQLiter and pre-SQLiter Library-root dual-database
 * candidates (#764) — are never read, staged or zipped. Only file names and byte sizes
 * (stat metadata) enter the archive, as `diagnostics.json`. Files are never opened by
 * SQLite; unsafe-to-open dual databases are handled safely by construction.
 */
private fun buildDatabaseArchive(): String? {
    val fileManager = NSFileManager.defaultManager
    val archive = archivePath()
    fileManager.removeItemAtPath(archive, error = null)
    val exists = { path: String -> fileManager.fileExistsAtPath(path) }
    val sizeOf = { path: String ->
        ((fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize)) as? NSNumber)?.longValue ?: 0L
    }
    // Guard A involves the SQLiter directory; guards B/C also the pre-SQLiter Library-root vitruvian.db.
    val sqliterDirectory = DatabaseFileContext.databasePath(DatabaseFileNames.TARGET, null).substringBeforeLast('/')
    val libraryDirectory = legacyLibraryRootPath().substringBeforeLast('/')
    val files = DatabaseFileExport.diagnosticFiles("sqliter", sqliterDirectory, exists, sizeOf) +
        DatabaseFileExport.diagnosticFiles("library", libraryDirectory, exists, sizeOf) +
        DatabaseFileExport.quarantineDiagnosticFiles(sqliterDirectory, quarantinedFiles(sqliterDirectory), sizeOf)
    if (files.isEmpty()) return null

    // Stage only the diagnostics report; the zip is produced from that single file.
    val staging = "${NSTemporaryDirectory().trimEnd('/')}/phoenix-support-diagnostics"
    fileManager.removeItemAtPath(staging, error = null)
    try {
        check(
            fileManager.createDirectoryAtPath(
                staging,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        ) { "Could not create the export folder" }
        val reportPath = "$staging/${DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME}"
        val report = NSString.create(string = DatabaseFileExport.diagnosticsJson(files))
        check(
            report.writeToFile(
                reportPath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            ),
        ) { "Could not stage the diagnostics report for export" }
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
