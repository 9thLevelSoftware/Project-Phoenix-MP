package com.devil.phoenixproject.util

import android.content.Context
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.InputStream

private val log = Logger.withTag("UriContentReader")

/**
 * Android implementation: reads content from a `content://` URI or a plain file path.
 * Uses the Application [Context] retrieved from Koin (registered by the Android platform module).
 */
actual suspend fun readUriContent(uriOrPath: String): String? = withContext(Dispatchers.IO) {
    try {
        openUriOrPath(uriOrPath)?.use { stream ->
            stream.bufferedReader().readText()
        }
    } catch (e: Exception) {
        log.e(e) { "Failed to read URI content: $uriOrPath" }
        null
    }
}

actual suspend fun readUriContentUpTo(uriOrPath: String, maxBytes: Int): BoundedUriContent = withContext(Dispatchers.IO) {
    try {
        openUriOrPath(uriOrPath)?.use { stream -> readUpTo(stream, maxBytes) } ?: BoundedUriContent.Unreadable
    } catch (e: Exception) {
        log.e(e) { "Failed to read URI content: $uriOrPath" }
        BoundedUriContent.Unreadable
    }
}

private fun openUriOrPath(uriOrPath: String): InputStream? {
    val context: Context = getKoin().get()
    return if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
        context.contentResolver.openInputStream(uriOrPath.toUri())
    } else {
        java.io.File(uriOrPath).inputStream()
    }
}

/** Reads [stream] as UTF-8, stopping as soon as it passes [maxBytes]. */
internal fun readUpTo(stream: InputStream, maxBytes: Int): BoundedUriContent {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val count = stream.read(chunk)
        if (count < 0) break
        if (buffer.size() + count > maxBytes) return BoundedUriContent.TooLarge
        buffer.write(chunk, 0, count)
    }
    return BoundedUriContent.Read(String(buffer.toByteArray(), Charsets.UTF_8))
}
