package com.devil.phoenixproject.domain.csv

/**
 * Pure classification of an Android intent that shares or opens a routine CSV (#1242).
 * No `android.content.Intent` here: the intake activity describes the intent with
 * [CsvImportIntentDescriptor] and acts on the returned [CsvImportPayload].
 *
 * Read order:
 * 1. `ACTION_SEND`: `EXTRA_STREAM`, else clipData item 0, else `EXTRA_TEXT` for `text/plain`
 *    or a CSV mime.
 * 2. `ACTION_VIEW` on `content`: `intent.data`.
 * 3. `ACTION_VIEW` on `phoenix://import`: the percent-decoded UTF-8 `data` query value. That
 *    value is the CSV itself, never a URI, and is decoded exactly once.
 *
 * Only `content://` URIs are ever returned for reading. A `file://` URI (or any other scheme)
 * would be opened with this app's own file permissions, so it is unreadable by construction.
 * Whether a `content://` authority belongs to this app is checked on Android, where the
 * package manager is available.
 */
object CsvImportIntentClassifier {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private val CSV_MIMES = setOf("text/csv", "text/comma-separated-values", "application/csv")
    private const val TEXT_PLAIN = "text/plain"
    private const val SCHEME_CONTENT = "content"
    private const val SCHEME_PHOENIX = "phoenix"
    private const val HOST_IMPORT = "import"
    private const val QUERY_DATA_KEY = "data"

    fun classify(intent: CsvImportIntentDescriptor): CsvImportPayload = when (intent.action) {
        ACTION_SEND -> classifySend(intent)
        ACTION_VIEW -> classifyView(intent)
        else -> CsvImportPayload.Ignore
    }

    private fun classifySend(intent: CsvImportIntentDescriptor): CsvImportPayload {
        // A stream or clip URI is read later as a bounded stream; only inline text is
        // size-checked here, because only it is already in memory.
        val uri = intent.streamUri?.takeIf { it.isNotBlank() }
            ?: intent.clipDataUri?.takeIf { it.isNotBlank() }
        if (uri != null) return contentUriPayload(uri)

        val type = intent.mimeType?.lowercase()
        val text = intent.inlineText
        return when {
            type != TEXT_PLAIN && type !in CSV_MIMES -> CsvImportPayload.Unreadable
            text == null || text.isBlank() -> CsvImportPayload.Unreadable
            else -> inlineTextPayload(text)
        }
    }

    private fun classifyView(intent: CsvImportIntentDescriptor): CsvImportPayload {
        val scheme = intent.scheme?.lowercase()
        return when {
            scheme == SCHEME_PHOENIX && intent.host.equals(HOST_IMPORT, ignoreCase = true) -> {
                val encoded = queryParameter(intent.data ?: return CsvImportPayload.Unreadable, QUERY_DATA_KEY)
                if (encoded.isNullOrBlank()) CsvImportPayload.Unreadable else inlineTextPayload(percentDecode(encoded))
            }

            scheme == SCHEME_PHOENIX -> CsvImportPayload.Ignore

            else -> intent.data?.takeIf { it.isNotBlank() }?.let(::contentUriPayload) ?: CsvImportPayload.Unreadable
        }
    }

    private fun contentUriPayload(uri: String): CsvImportPayload =
        if (uri.substringBefore(':', missingDelimiterValue = "").lowercase() == SCHEME_CONTENT) {
            CsvImportPayload.StreamUri(uri)
        } else {
            CsvImportPayload.Unreadable
        }

    private fun inlineTextPayload(text: String): CsvImportPayload = when {
        text.isBlank() -> CsvImportPayload.Unreadable
        text.encodeToByteArray().size > RoutineCsvFormat.MAX_BYTES -> CsvImportPayload.TooLarge
        else -> CsvImportPayload.InlineText(text)
    }

    /**
     * The raw (still percent-encoded) value of [key] in `uri`'s query, or null. Keys are matched
     * literally, as standard URL parsing does: `d%61ta` is not the `data` parameter.
     */
    private fun queryParameter(uri: String, key: String): String? {
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return null
        // No fragment split: an unencoded `#` (the CSV header starts with one) stays CSV content.
        for (pair in uri.substring(queryStart + 1).split('&')) {
            val eq = pair.indexOf('=')
            val pairKey = if (eq < 0) pair else pair.substring(0, eq)
            if (pairKey == key) return if (eq < 0) "" else pair.substring(eq + 1)
        }
        return null
    }

    /**
     * Percent-decoding applied exactly once: `%XX` bytes are decoded as UTF-8 and a literal `+`
     * stays `+` (the CSV body may contain it). Malformed escapes are kept as written.
     */
    internal fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            bytes.addAll(c.toString().encodeToByteArray().asList())
            i += 1
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** An incoming intent described without `android.content.Intent` (#1242). */
data class CsvImportIntentDescriptor(
    val action: String?,
    val mimeType: String?,
    val scheme: String?,
    val host: String?,
    /** `EXTRA_STREAM` URI string (SEND only). */
    val streamUri: String? = null,
    /** URI string of clipData item 0 (SEND only). */
    val clipDataUri: String? = null,
    /** `EXTRA_TEXT` text (SEND only). */
    val inlineText: String? = null,
    /** `intent.data` string, query included. */
    val data: String? = null,
)

/** What the intake does with a delivered intent. */
sealed interface CsvImportPayload {
    /** A `content://` URI to read as a bounded stream off the main thread. */
    data class StreamUri(val uri: String) : CsvImportPayload

    /** Inline CSV text already bounded to [RoutineCsvFormat.MAX_BYTES]. */
    data class InlineText(val text: String) : CsvImportPayload

    /** Nothing readable was delivered; the import reports it and writes nothing. */
    data object Unreadable : CsvImportPayload

    /** The payload was over [RoutineCsvFormat.MAX_BYTES]; nothing was kept. */
    data object TooLarge : CsvImportPayload

    /** Not a CSV import delivery. */
    data object Ignore : CsvImportPayload
}
