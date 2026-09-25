@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

private val log = Logger.withTag("UriContentReader")

/**
 * iOS implementation: reads content from a file path (temp file returned by UIDocumentPickerViewController).
 */
actual suspend fun readUriContent(uriOrPath: String): String? = withContext(Dispatchers.Default) {
    try {
        @Suppress("UNCHECKED_CAST")
        NSString.stringWithContentsOfFile(uriOrPath, NSUTF8StringEncoding, null) as? String
    } catch (e: Exception) {
        log.e(e) { "Failed to read file content: $uriOrPath" }
        null
    }
}

/** The picker hands over a local copy, so its size is known before reading it. */
actual suspend fun readUriContentUpTo(uriOrPath: String, maxBytes: Int): BoundedUriContent = withContext(Dispatchers.Default) {
    try {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(uriOrPath, error = null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longValue
        when {
            size == null -> BoundedUriContent.Unreadable
            size > maxBytes -> BoundedUriContent.TooLarge
            else -> {
                @Suppress("UNCHECKED_CAST")
                val content = NSString.stringWithContentsOfFile(uriOrPath, NSUTF8StringEncoding, null) as? String
                content?.let { BoundedUriContent.Read(it) } ?: BoundedUriContent.Unreadable
            }
        }
    } catch (e: Exception) {
        log.e(e) { "Failed to read file content: $uriOrPath" }
        BoundedUriContent.Unreadable
    }
}
