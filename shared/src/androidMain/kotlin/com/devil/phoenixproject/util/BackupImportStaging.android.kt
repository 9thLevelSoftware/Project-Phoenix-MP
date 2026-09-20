package com.devil.phoenixproject.util

import java.io.File
import java.io.FileInputStream

internal actual fun createBackupImportStagingArea(): BackupImportStagingArea =
    AndroidBackupImportStagingArea()

private class AndroidBackupImportStagingArea : BackupImportStagingArea {
    private val parent = File(System.getProperty("java.io.tmpdir"), "phoenix-backup-import")
    private val createdAt = System.currentTimeMillis()
    private val directory = File(parent, "stage-$createdAt-${System.nanoTime()}")
    private val writers = mutableMapOf<String, BackupJsonWriter>()
    private val hasArrayValue = mutableSetOf<String>()

    init {
        parent.mkdirs()
        val staleBefore = createdAt - STALE_STAGE_AGE_MILLIS
        parent.listFiles()?.filter { it.isDirectory }?.forEach { candidate ->
            val timestamp = candidate.name.removePrefix("stage-").substringBefore('-').toLongOrNull()
            if (timestamp != null && timestamp < staleBefore) candidate.deleteRecursively()
        }
        check(directory.mkdirs()) { "Failed to create backup import staging directory" }
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

    override fun openSection(section: String): BackupStreamSource? {
        val file = file(section)
        return if (file.isFile) InputStreamBackupSource(FileInputStream(file)) else null
    }

    override fun cleanup() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
        directory.deleteRecursively()
        parent.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    private fun writer(section: String): BackupJsonWriter = writers.getOrPut(section) {
        BackupJsonWriter(file(section).absolutePath).also { it.open() }
    }

    private fun file(section: String): File {
        require(section.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid backup section name" }
        return File(directory, "$section.json")
    }

    private companion object {
        const val STALE_STAGE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
