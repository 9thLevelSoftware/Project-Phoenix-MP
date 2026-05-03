package com.devil.phoenixproject.util

/**
 * Platform-agnostic character stream source for streaming JSON import.
 * Mirrors [BackupJsonWriter] pattern but for reading.
 *
 * Implementations wrap platform I/O:
 * - Android: InputStream via BufferedReader
 * - iOS: NSInputStream with UTF-8 decoding
 */
interface BackupStreamSource {
    /** Open the underlying stream for reading. */
    fun open()

    /** Close the underlying stream and release resources. */
    fun close()

    /** Read a single character. Returns -1 on EOF. */
    fun read(): Int

    /**
     * Bulk read into [buffer] starting at [offset] for up to [length] chars.
     * Returns the number of characters actually read, or -1 on EOF.
     */
    fun read(buffer: CharArray, offset: Int, length: Int): Int
}
