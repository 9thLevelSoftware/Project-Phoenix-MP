package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationWithSecurityScope
import platform.Foundation.NSURLBookmarkResolutionWithSecurityScope
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile

private val log = Logger.withTag("BackupDestinationResolver.iOS")

/**
 * iOS implementation of [BackupDestinationResolver].
 *
 * Uses security-scoped bookmarks (Base64-encoded) to regain access to
 * user-selected directories across app launches. Each operation resolves
 * the bookmark, starts security-scoped access, performs the I/O, then
 * stops access. A stale bookmark is recreated with
 * [NSURLBookmarkCreationWithSecurityScope] and stored back on
 * [BackupDestination.Custom].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBackupDestinationResolver(
    private val preferencesManager: PreferencesManager,
) : BackupDestinationResolver {

    /**
     * Decode a Base64 bookmark string back to an [NSURL].
     *
     * Returns null if the bookmark is missing, corrupt, or the referenced
     * directory no longer exists.
     */
    private suspend fun resolveBookmark(destination: BackupDestination.Custom): NSURL? {
        val base64 = destination.bookmarkData
        if (base64.isNullOrBlank()) {
            // No bookmark — try direct URL as fallback (e.g. app-sandbox paths)
            return destination.uri.takeIf { it.isNotBlank() }?.let { NSURL.fileURLWithPath(it) }
        }

        return try {
            val bookmarkData = NSData.create(
                base64EncodedString = base64,
                options = 0u,
            ) ?: run {
                log.w { "Failed to decode Base64 bookmark for ${destination.displayName}" }
                return null
            }

            val resolved = memScoped {
                val isStale = alloc<BooleanVar>()
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                @Suppress("UNCHECKED_CAST")
                val url = NSURL.URLByResolvingBookmarkData(
                    bookmarkData = bookmarkData,
                    options = NSURLBookmarkResolutionWithoutUI or NSURLBookmarkResolutionWithSecurityScope,
                    relativeToURL = null,
                    bookmarkDataIsStale = isStale.ptr,
                    error = errorPtr.ptr,
                )

                if (url == null) {
                    val resolveError = errorPtr.value
                    log.w {
                        "Bookmark resolution failed for ${destination.displayName}: " +
                            (resolveError?.localizedDescription ?: "unknown error")
                    }
                    null
                } else {
                    url to isStale.value
                }
            } ?: return null

            val (url, stale) = resolved
            if (stale) {
                refreshStaleBookmark(destination, url)
            }
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "resolveBookmark failed for ${destination.displayName}" }
            null
        }
    }

    /**
     * Replace a stale security-scoped bookmark with a fresh one.
     *
     * The resolved [url] is still valid for this call. Bookmark creation matches
     * [BackupLocationPicker]: [NSURLBookmarkCreationWithSecurityScope], while
     * security-scoped access is held. Failure leaves the previous bookmark in place.
     */
    private suspend fun refreshStaleBookmark(destination: BackupDestination.Custom, url: NSURL) {
        var accessing = false
        try {
            accessing = url.startAccessingSecurityScopedResource()
            val base64 = memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val bookmarkData = url.bookmarkDataWithOptions(
                    options = NSURLBookmarkCreationWithSecurityScope,
                    includingResourceValuesForKeys = null,
                    relativeToURL = null,
                    error = errorPtr.ptr,
                )
                if (bookmarkData == null) {
                    log.w {
                        "Stale bookmark for ${destination.displayName} could not be recreated: " +
                            (errorPtr.value?.localizedDescription ?: "unknown error")
                    }
                    null
                } else {
                    val encoded = bookmarkData.base64EncodedStringWithOptions(0u)
                    if (encoded.isNullOrEmpty()) null else encoded
                }
            }
            if (base64 == null) {
                log.w { "Bookmark is stale for ${destination.displayName}; keeping the previous bookmark" }
                return
            }
            if (base64 == destination.bookmarkData) {
                log.w { "Bookmark is stale for ${destination.displayName}, but recreation matched the stored bookmark" }
                return
            }

            val refreshedUri = url.absoluteString?.takeIf { it.isNotBlank() } ?: destination.uri
            preferencesManager.setBackupDestination(
                destination.copy(
                    uri = refreshedUri,
                    bookmarkData = base64,
                ),
            )
            log.i { "Refreshed stale security-scoped bookmark for ${destination.displayName}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Failed to persist refreshed bookmark for ${destination.displayName}" }
        } finally {
            if (accessing) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    override suspend fun isAccessible(destination: BackupDestination.Custom): Boolean = withContext(Dispatchers.IO) {
        val url = resolveBookmark(destination) ?: return@withContext false

        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val path = url.path ?: return@withContext false
            val fileManager = NSFileManager.defaultManager
            val exists = fileManager.fileExistsAtPath(path)
            val writable = fileManager.isWritableFileAtPath(path)
            if (!exists || !writable) {
                log.w { "Directory check failed: exists=$exists, writable=$writable for ${destination.displayName}" }
            }
            exists && writable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "isAccessible failed for ${destination.displayName}" }
            false
        } finally {
            if (accessing) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    override suspend fun writeFile(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = resolveBookmark(destination)
            ?: return@withContext Result.failure(Exception("Cannot resolve bookmark for ${destination.displayName}"))

        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val dirPath = url.path
                ?: return@withContext Result.failure(Exception("Resolved URL has no path"))

            val destPath = "$dirPath/$fileName"
            val fileManager = NSFileManager.defaultManager

            // Remove existing file if present
            if (fileManager.fileExistsAtPath(destPath)) {
                fileManager.removeItemAtPath(destPath, error = null)
            }

            // Read temp file content and write to destination
            val tempData = NSData.create(contentsOfFile = tempFilePath)
                ?: return@withContext Result.failure(Exception("Cannot read temp file: $tempFilePath"))

            val written = tempData.writeToFile(destPath, atomically = true)
            if (!written) {
                return@withContext Result.failure(Exception("NSData.writeToFile failed for $destPath"))
            }

            log.d { "Wrote $fileName to ${destination.displayName} ($destPath)" }
            Result.success(destPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "writeFile failed for $fileName to ${destination.displayName}" }
            Result.failure(e)
        } finally {
            if (accessing) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    override suspend fun listFiles(destination: BackupDestination.Custom): List<String> = withContext(Dispatchers.IO) {
        val url = resolveBookmark(destination) ?: return@withContext emptyList()

        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val dirPath = url.path ?: return@withContext emptyList()
            val fileManager = NSFileManager.defaultManager
            val contents = fileManager.contentsOfDirectoryAtPath(dirPath, error = null)
                ?: return@withContext emptyList()

            contents
                .mapNotNull { it as? String }
                .filter { it.endsWith(".json") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "listFiles failed for ${destination.displayName}" }
            emptyList()
        } finally {
            if (accessing) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }
}
