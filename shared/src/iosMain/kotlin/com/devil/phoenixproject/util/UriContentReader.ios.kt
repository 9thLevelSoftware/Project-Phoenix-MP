@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
