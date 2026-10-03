package com.devil.phoenixproject.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/**
 * Deletes an existing file at the temporary path, then writes [content] as UTF-8.
 *
 * Returns the path when the write succeeds, or null when it does not. Does not
 * catch exceptions; callers keep their own error style.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun writeUtf8TempFile(fileName: String, content: String): String? {
    val filePath = "${NSTemporaryDirectory()}$fileName"
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(filePath)) {
        fileManager.removeItemAtPath(filePath, null)
    }
    val success = NSString.create(string = content).writeToFile(
        filePath,
        atomically = true,
        encoding = NSUTF8StringEncoding,
        error = null,
    )
    return if (success) filePath else null
}
