package com.devil.phoenixproject.util

actual class BackupJsonWriter actual constructor(actual val filePath: String) {
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Backup export is not available on tvOS.")

    actual fun open() = unsupported()
    actual fun write(text: String) = unsupported()
    actual fun flush() = unsupported()
    actual fun close() = unsupported()
    actual fun delete() = unsupported()
}
