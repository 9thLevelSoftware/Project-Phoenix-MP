package com.devil.phoenixproject.util

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Android [BackupStreamSource] backed by a [java.io.InputStream].
 *
 * Works with both `content://` URIs (via `ContentResolver.openInputStream`)
 * and regular file streams. Uses an 8 KB [BufferedReader] for efficient
 * character decoding.
 */
class InputStreamBackupSource(private val inputStream: InputStream) : BackupStreamSource {
    private var reader: BufferedReader? = null

    override fun open() {
        reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 8192)
    }

    override fun close() {
        reader?.close()
        reader = null
    }

    override fun read(): Int = reader?.read() ?: -1

    override fun read(buffer: CharArray, offset: Int, length: Int): Int =
        reader?.read(buffer, offset, length) ?: -1
}
