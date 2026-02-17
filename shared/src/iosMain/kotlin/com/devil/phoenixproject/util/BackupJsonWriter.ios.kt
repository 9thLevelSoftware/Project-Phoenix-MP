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
                error = null
            )
        }
        // Create the file
        manager.createFileAtPath(filePath, contents = null, attributes = null)
        fileHandle = NSFileHandle.fileHandleForWritingAtPath(filePath)
    }

    actual fun write(text: String) {
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        fileHandle?.writeData(data)
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
