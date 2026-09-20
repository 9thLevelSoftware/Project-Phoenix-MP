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

/** Adds cooperative cancellation (or another caller-supplied guard) to blocking reads. */
internal class GuardedBackupStreamSource(
    private val delegate: BackupStreamSource,
    private val beforeRead: () -> Unit,
) : BackupStreamSource {
    override fun open() = delegate.open()
    override fun close() = delegate.close()

    override fun read(): Int {
        beforeRead()
        return delegate.read()
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        beforeRead()
        return delegate.read(buffer, offset, length)
    }
}

internal class StringBackupStreamSource(private val value: String) : BackupStreamSource {
    private var index = 0

    override fun open() {
        index = 0
    }

    override fun close() = Unit

    override fun read(): Int = if (index < value.length) value[index++].code else -1

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (index >= value.length) return -1
        val count = minOf(length, value.length - index)
        value.toCharArray(index, index + count).copyInto(buffer, offset)
        index += count
        return count
    }
}
