@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual fun createBackupImportStagingArea(): BackupImportStagingArea =
    IosBackupImportStagingArea()

private class IosBackupImportStagingArea : BackupImportStagingArea {
    private val manager = NSFileManager.defaultManager
    private val parent = NSTemporaryDirectory().trimEnd('/') + "/phoenix-backup-import"
    private val createdAt = KmpUtils.currentTimeMillis()
    private val directory = "$parent/stage-$createdAt-${NSUUID().UUIDString}"
    private val writers = mutableMapOf<String, BackupJsonWriter>()
    private val hasArrayValue = mutableSetOf<String>()

    init {
        manager.createDirectoryAtPath(parent, true, null, null)
        @Suppress("UNCHECKED_CAST")
        val existing = manager.contentsOfDirectoryAtPath(parent, error = null) as? List<String> ?: emptyList()
        val staleBefore = createdAt - STALE_STAGE_AGE_MILLIS
        existing.forEach { candidate ->
            val timestamp = candidate.removePrefix("stage-").substringBefore('-').toLongOrNull()
            if (timestamp != null && timestamp < staleBefore) {
                manager.removeItemAtPath("$parent/$candidate", error = null)
            }
        }
        check(manager.createDirectoryAtPath(directory, true, null, null)) {
            "Failed to create backup import staging directory"
        }
    }

    override fun beginArray(section: String) {
        writer(section).write("[")
    }

    override fun appendArrayValue(section: String, rawJson: String) {
        val writer = writer(section)
        if (!hasArrayValue.add(section)) writer.write(",")
        writer.write(rawJson)
    }

    override fun endArray(section: String) {
        writer(section).apply {
            write("]")
            close()
        }
        writers.remove(section)
    }

    override fun writeValue(section: String, rawJson: String) {
        writer(section).apply {
            write(rawJson)
            close()
        }
        writers.remove(section)
    }

    override fun openSection(section: String): BackupStreamSource? =
        path(section).takeIf(manager::fileExistsAtPath)?.let(::FileBackupStreamSource)

    override fun cleanup() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
        manager.removeItemAtPath(directory, error = null)
        val remaining = manager.contentsOfDirectoryAtPath(parent, error = null)
        if (remaining.isNullOrEmpty()) manager.removeItemAtPath(parent, error = null)
    }

    private fun writer(section: String): BackupJsonWriter = writers.getOrPut(section) {
        BackupJsonWriter(path(section)).also { it.open() }
    }

    private fun path(section: String): String {
        require(section.all { it.isLetterOrDigit() || it == '_' }) { "Invalid backup section name" }
        return "$directory/$section.json"
    }

    private companion object {
        const val STALE_STAGE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
