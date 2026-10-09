package com.devil.phoenixproject.domain.csv

/**
 * Pure classification of an incoming Android CSV-import intent (#1242 Parts 1+2).
 * No `android.content.Intent` here: the host activity describes the intent with
 * [CsvImportIntentDescriptor] and acts on the returned [CsvImportPayload].
 *
 * Read order (architecture §2):
 * 1. `ACTION_SEND`: `EXTRA_STREAM` URI, else clipData item 0, else `EXTRA_TEXT` only for
 *    `text/plain` or a CSV mime.
 * 2. `ACTION_VIEW` on `content`/`file`: `intent.data` only.
 * 3. `ACTION_VIEW` on `phoenix`/`import`: the percent-decoded UTF-8 `data` query value —
 *    inline text, never a URI — with decoding applied exactly once.
 *
 * Terminal outcomes ([CsvImportPayload.Unreadable] / [CsvImportPayload.TooLarge]) mean the
 * delivery is consumed but nothing is written. [CsvImportPayload.Ignore] leaves the activity
 * intent untouched: launcher starts, OAuth returns, Health Connect screens and workout
 * notifications are not CSV import deliveries.
 */
object CsvImportIntentClassifier {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_MAIN = "android.intent.action.MAIN"

    /** Never CSV import deliveries; always ignored. */
    val NON_IMPORT_ACTIONS: Set<String> = setOf(
        ACTION_MAIN,
        "android.intent.action.VIEW_PERMISSION_USAGE",
        "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
        "androidx.health.ACTION_SHOW_ONBOARDING",
        "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS",
        "android.health.connect.action.HEALTH_HOME_SETTINGS",
        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
    )

    private val CSV_MIMES = setOf("text/csv", "text/comma-separated-values", "application/csv")
    private const val TEXT_PLAIN = "text/plain"
    private const val SCHEME_PHOENIX = "phoenix"
    private const val HOST_IMPORT = "import"
    private const val QUERY_DATA_KEY = "data"

    fun classify(intent: CsvImportIntentDescriptor): CsvImportPayload {
        val action = intent.action
        if (action == null || action in NON_IMPORT_ACTIONS) return CsvImportPayload.Ignore

        return when (action) {
            ACTION_SEND -> classifySend(intent)
            ACTION_VIEW -> classifyView(intent)
            else -> CsvImportPayload.Ignore
        }
    }

    private fun classifySend(intent: CsvImportIntentDescriptor): CsvImportPayload {
        // A stream or clip URI is read as a bounded stream later; only inline text is
        // size-checked here, because only it is retained before the read.
        intent.streamUri?.takeIf { it.isNotBlank() }?.let { return CsvImportPayload.StreamUri(it) }
        intent.clipDataUri?.takeIf { it.isNotBlank() }?.let { return CsvImportPayload.StreamUri(it) }

        val type = intent.mimeType?.lowercase()
        val inlineTextAllowed = type == TEXT_PLAIN || type in CSV_MIMES
        val text = intent.inlineText
        return when {
            !inlineTextAllowed || text == null -> CsvImportPayload.Unreadable
            text.isBlank() -> CsvImportPayload.Unreadable
            text.encodeToByteArray().size > RoutineCsvFormat.MAX_BYTES -> CsvImportPayload.TooLarge
            else -> CsvImportPayload.InlineText(text)
        }
    }

    private fun classifyView(intent: CsvImportIntentDescriptor): CsvImportPayload {
        val scheme = intent.scheme?.lowercase()
        return when {
            scheme == "content" || scheme == "file" -> {
                val uri = intent.data?.takeIf { it.isNotBlank() } ?: return CsvImportPayload.Unreadable
                CsvImportPayload.StreamUri(uri)
            }

            scheme == SCHEME_PHOENIX && intent.host?.equals(HOST_IMPORT, ignoreCase = true) == true -> {
                // `data` carries the CSV itself, not a URI; only that key is read.
                val encoded = queryParameter(intent.data ?: return CsvImportPayload.Unreadable, QUERY_DATA_KEY)
                when {
                    encoded == null || encoded.isBlank() -> CsvImportPayload.Unreadable
                    else -> {
                        val text = percentDecode(encoded)
                        when {
                            text.isBlank() -> CsvImportPayload.Unreadable
                            text.encodeToByteArray().size > RoutineCsvFormat.MAX_BYTES -> CsvImportPayload.TooLarge
                            else -> CsvImportPayload.InlineText(text)
                        }
                    }
                }
            }

            else -> CsvImportPayload.Ignore
        }
    }

    /** The raw (still percent-encoded) value of [key] in `uri`'s query, or null. */
    private fun queryParameter(uri: String, key: String): String? {
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return null
        for (pair in uri.substring(queryStart + 1).split('&')) {
            val eq = pair.indexOf('=')
            val pairKey = if (eq < 0) pair else pair.substring(0, eq)
            if (percentDecode(pairKey) == key) {
                return if (eq < 0) "" else pair.substring(eq + 1)
            }
        }
        return null
    }

    /**
     * Percent-decoding applied exactly once: `%XX` bytes are decoded as UTF-8 and a literal
     * `+` stays `+` (the CSV body may contain it). Malformed escapes are kept as written.
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
    /** `EXTRA_STREAM` URI string. */
    val streamUri: String? = null,
    /** URI string of clipData item 0. */
    val clipDataUri: String? = null,
    /** `EXTRA_TEXT` text. */
    val inlineText: String? = null,
    /** `intent.data` string, query included. */
    val data: String? = null,
)

/** What the intake must do with a delivered intent. */
sealed interface CsvImportPayload {
    /** A provider URI to read as a bounded stream off the main thread. */
    data class StreamUri(val uri: String) : CsvImportPayload

    /** Inline CSV text already bounded to [RoutineCsvFormat.MAX_BYTES]. */
    data class InlineText(val text: String) : CsvImportPayload

    /** Terminal: the delivery is consumed, but nothing readable was delivered. */
    data object Unreadable : CsvImportPayload

    /** Terminal: the delivery is consumed; the payload was over [RoutineCsvFormat.MAX_BYTES]. */
    data object TooLarge : CsvImportPayload

    /** Not a CSV import delivery (launcher, OAuth, Health Connect, workout notification). */
    data object Ignore : CsvImportPayload
}

/**
 * The consume rule (B4 amendment 4): a handled import is recorded at acceptance and restored
 * state must not handle it again. `onNewIntent` always handles a genuine new delivery, so a
 * deliberate re-share of the same file imports again.
 */
object CsvImportDeliveryPolicy {
    /** Cold start: handle only when saved state does not already record a handled delivery. */
    fun acceptOnCreate(restoredHandled: Boolean): Boolean = !restoredHandled

    /** Warm re-entry: a genuine delivery is always accepted. */
    fun acceptOnNewIntent(payload: CsvImportPayload): Boolean = payload.isHandledImport

    /** Only a handled import replaces the activity intent with a plain launcher intent. */
    fun shouldSanitizeActivityIntent(payload: CsvImportPayload): Boolean = payload.isHandledImport
}

/** A delivery the import intake owns (as opposed to [CsvImportPayload.Ignore]). */
val CsvImportPayload.isHandledImport: Boolean
    get() = when (this) {
        is CsvImportPayload.StreamUri,
        is CsvImportPayload.InlineText,
        CsvImportPayload.Unreadable,
        CsvImportPayload.TooLarge,
        -> true

        CsvImportPayload.Ignore -> false
    }
