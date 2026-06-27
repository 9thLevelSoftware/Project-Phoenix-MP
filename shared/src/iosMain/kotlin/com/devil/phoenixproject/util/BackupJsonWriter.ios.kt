package com.devil.phoenixproject.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class BackupJsonWriter actual constructor(actual val filePath: String) {
    private var fileHandle: NSFileHandle? = null

    actual fun open() {
        val manager = NSFileManager.defaultManager
        // Create parent directory if needed
        val parentPath = filePath.substringBeforeLast('/')
        if (!manager.fileExistsAtPath(parentPath)) {
            manager.createDirectoryAtPath(
                parentPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            // F065: fail loudly if the directory still doesn't exist so the export
            // doesn't believe it wrote a backup that never landed.
            if (!manager.fileExistsAtPath(parentPath)) {
                throw IllegalStateException("Failed to create backup directory: $parentPath")
            }
        }
        // Create the file and open a write handle; surface failures instead of
        // silently leaving fileHandle null and dropping every write.
        if (!manager.createFileAtPath(filePath, contents = null, attributes = null)) {
            throw IllegalStateException("Failed to create backup file: $filePath")
        }
        fileHandle = NSFileHandle.fileHandleForWritingAtPath(filePath)
            ?: throw IllegalStateException("Failed to open backup file for writing: $filePath")
    }

    actual fun write(text: String) {
        // F065: a missing handle or failed UTF-8 conversion previously returned
        // silently, producing a truncated/empty backup reported as success.
        val handle = fileHandle
            ?: throw IllegalStateException("BackupJsonWriter.write() called with no open file handle")
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw IllegalStateException("Failed to encode backup content as UTF-8")
        handle.writeData(data)
    }

    actual fun flush() {
        fileHandle?.synchronizeFile()
    }

    actual fun close() {
        fileHandle?.closeFile()
        fileHandle = null
    }

    actual fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(filePath, error = null)
    }
}
