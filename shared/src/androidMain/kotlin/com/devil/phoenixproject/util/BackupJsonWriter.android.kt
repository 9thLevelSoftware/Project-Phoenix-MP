package com.devil.phoenixproject.util

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

actual class BackupJsonWriter actual constructor(actual val filePath: String) {
    private var writer: BufferedWriter? = null

    actual fun open() {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 8192)
    }

    actual fun write(text: String) {
        writer?.write(text)
    }

    actual fun flush() {
        writer?.flush()
    }

    actual fun close() {
        writer?.close()
        writer = null
    }

    actual fun delete() {
        File(filePath).delete()
    }
}
