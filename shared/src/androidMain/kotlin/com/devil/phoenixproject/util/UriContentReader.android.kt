package com.devil.phoenixproject.util

import android.content.Context
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

private val log = Logger.withTag("UriContentReader")

/**
 * Android implementation: reads content from a `content://` URI or a plain file path.
 * Uses the Application [Context] retrieved from Koin (registered by the Android platform module).
 */
actual suspend fun readUriContent(uriOrPath: String): String? = withContext(Dispatchers.IO) {
    try {
        val context: Context = getKoin().get()
        if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
            val uri = uriOrPath.toUri()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        } else {
            java.io.File(uriOrPath).readText()
        }
    } catch (e: Exception) {
        log.e(e) { "Failed to read URI content: $uriOrPath" }
        null
    }
}
