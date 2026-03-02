package com.devil.phoenixproject.util

/**
 * Platform-specific streaming JSON writer for backup exports.
 * Writes text incrementally to a file to avoid holding the entire JSON in memory.
 */
expect class BackupJsonWriter(filePath: String) {
    val filePath: String
    fun open()
    fun write(text: String)
    fun flush()
    fun close()
    fun delete()
}
