package com.devil.phoenixproject.data.csv

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.csv.CsvImportIntentClassifier
import com.devil.phoenixproject.domain.csv.CsvImportIntentDescriptor
import com.devil.phoenixproject.domain.csv.CsvImportPayload
import com.devil.phoenixproject.util.BoundedUriContent
import com.devil.phoenixproject.util.readUpTo
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Android half of opening a shared routine CSV (#1242): turns the incoming intent into a
 * [CsvImportPayload] and reads a `content://` payload into a [CsvImportDeliveryResult].
 * Logs carry outcomes only, never a URI, the CSV or provider error text.
 */
object AndroidCsvImportIntake {
    private val log = Logger.withTag("CsvImportIntake")

    /**
     * Classifies [intent]. Extras are read only for `ACTION_SEND`, and a bundle that cannot be
     * unparcelled (any app can send one) is an unreadable delivery, not a crash.
     */
    fun describe(intent: Intent): CsvImportPayload = try {
        CsvImportIntentClassifier.classify(intent.toDescriptor())
    } catch (e: RuntimeException) {
        log.w { "csv_import_intent outcome=malformed" }
        CsvImportPayload.Unreadable
    }

    private fun Intent.toDescriptor(): CsvImportIntentDescriptor {
        val isSend = action == Intent.ACTION_SEND
        return CsvImportIntentDescriptor(
            action = action,
            mimeType = type,
            scheme = data?.scheme,
            host = data?.host,
            streamUri = if (isSend) IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString() else null,
            clipDataUri = if (isSend) clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString() else null,
            inlineText = if (isSend) getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null,
            data = data?.toString(),
        )
    }

    /**
     * Reads [uri] off the main thread, stopping past [maxBytes]. A URI that is not
     * `content://`, or that names one of this app's own providers, is refused unread: this app
     * can open its own private files, and another app must never be able to make it do so.
     */
    suspend fun read(context: Context, uri: String, maxBytes: Int): CsvImportDeliveryResult {
        val parsed = Uri.parse(uri)
        if (!isForeignContentUri(parsed, context.packageName) { authority -> providerPackage(context, authority) }) {
            log.w { "csv_import_read outcome=refused" }
            return CsvImportDeliveryResult.Unreadable
        }
        return withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(parsed)
                if (stream == null) {
                    log.i { "csv_import_read outcome=unreadable" }
                    CsvImportDeliveryResult.Unreadable
                } else {
                    readBounded(stream, maxBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Revoked grant, missing file or provider: an ordinary unreadable file.
                log.i { "csv_import_read outcome=unreadable" }
                CsvImportDeliveryResult.Unreadable
            }
        }
    }

    /** A cancelled read interrupts the blocking provider stream; the stream is always closed. */
    internal suspend fun readBounded(stream: InputStream, maxBytes: Int): CsvImportDeliveryResult {
        val bounded = stream.use { open -> runInterruptible { readUpTo(open, maxBytes) } }
        return when (bounded) {
            is BoundedUriContent.Read -> {
                log.i { "csv_import_read outcome=read" }
                CsvImportDeliveryResult.Read(bounded.content)
            }

            BoundedUriContent.TooLarge -> {
                log.i { "csv_import_read outcome=too_large" }
                CsvImportDeliveryResult.TooLarge
            }

            BoundedUriContent.Unreadable -> {
                log.i { "csv_import_read outcome=unreadable" }
                CsvImportDeliveryResult.Unreadable
            }
        }
    }

    /**
     * True only for a `content://` URI whose provider is not this app's. A `userId@` prefix is
     * stripped first, because the content resolver strips it too. [providerPackage] returns the
     * package that owns an authority, or null when the provider is unknown (and so not ours).
     */
    internal fun isForeignContentUri(uri: Uri, ownPackage: String, providerPackage: (String) -> String?): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        if (authority == ownPackage || authority.startsWith("$ownPackage.")) return false
        return providerPackage(authority) != ownPackage
    }

    private fun providerPackage(context: Context, authority: String): String? = try {
        context.packageManager.resolveContentProvider(authority, 0)?.packageName
    } catch (e: RuntimeException) {
        null
    }
}
