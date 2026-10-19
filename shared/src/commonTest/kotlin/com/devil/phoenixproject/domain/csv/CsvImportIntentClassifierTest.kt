package com.devil.phoenixproject.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #1242: a shared or opened routine CSV becomes a `content://` stream to read, inline
 * text, a terminal error, or nothing. No other URI scheme is ever handed on for reading.
 */
class CsvImportIntentClassifierTest {
    private fun send(
        mimeType: String? = "text/csv",
        streamUri: String? = null,
        clipDataUri: String? = null,
        inlineText: String? = null,
    ) = CsvImportIntentClassifier.classify(
        CsvImportIntentDescriptor(
            action = CsvImportIntentClassifier.ACTION_SEND,
            mimeType = mimeType,
            scheme = null,
            host = null,
            streamUri = streamUri,
            clipDataUri = clipDataUri,
            inlineText = inlineText,
        ),
    )

    private fun view(scheme: String?, host: String? = null, data: String? = null) = CsvImportIntentClassifier.classify(
        CsvImportIntentDescriptor(
            action = CsvImportIntentClassifier.ACTION_VIEW,
            mimeType = "text/csv",
            scheme = scheme,
            host = host,
            data = data,
        ),
    )

    private fun phoenix(data: String) = view(scheme = "phoenix", host = "import", data = data)

    @Test
    fun sendStreamWinsOverClipDataAndText() {
        assertEquals(
            CsvImportPayload.StreamUri("content://provider/1"),
            send(streamUri = "content://provider/1", clipDataUri = "content://provider/2", inlineText = "# inline"),
        )
    }

    @Test
    fun sendWithoutStreamFallsBackToClipDataItemZero() {
        assertEquals(
            CsvImportPayload.StreamUri("content://provider/2"),
            send(clipDataUri = "content://provider/2", inlineText = "# inline"),
        )
    }

    @Test
    fun sendStreamThatIsNotContentIsRefusedNotRead() {
        // A file:// URI would be opened with this app's own file permissions.
        assertEquals(CsvImportPayload.Unreadable, send(streamUri = "file:///data/data/com.devil.phoenixproject/databases/phoenix.db"))
        assertEquals(CsvImportPayload.Unreadable, send(clipDataUri = "file:///sdcard/a.csv"))
        assertEquals(CsvImportPayload.Unreadable, send(streamUri = "android.resource://com.devil.phoenixproject/raw/x"))
        assertEquals(CsvImportPayload.Unreadable, send(streamUri = "/data/data/com.devil.phoenixproject/files/x"))
    }

    @Test
    fun contentSchemeIsMatchedCaseInsensitively() {
        assertEquals(CsvImportPayload.StreamUri("CONTENT://provider/1"), send(streamUri = "CONTENT://provider/1"))
    }

    @Test
    fun sendInlineTextForTextPlainAndCsvMimes() {
        for (mime in listOf("text/plain", "text/csv", "text/comma-separated-values", "application/csv", "TEXT/CSV")) {
            assertEquals(CsvImportPayload.InlineText("csv"), send(mimeType = mime, inlineText = "csv"), mime)
        }
    }

    @Test
    fun sendInlineTextForAnyOtherMimeIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, send(mimeType = "application/pdf", inlineText = "csv"))
        assertEquals(CsvImportPayload.Unreadable, send(mimeType = null, inlineText = "csv"))
    }

    @Test
    fun sendWithNothingOrBlankTextIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, send())
        assertEquals(CsvImportPayload.Unreadable, send(mimeType = "text/plain", inlineText = "   \n"))
    }

    @Test
    fun inlineTextOverTheByteBoundIsTooLarge() {
        val oversize = "a".repeat(RoutineCsvFormat.MAX_BYTES + 1)
        assertEquals(CsvImportPayload.TooLarge, send(mimeType = "text/plain", inlineText = oversize))
        assertEquals(CsvImportPayload.TooLarge, phoenix("phoenix://import?data=$oversize"))
    }

    @Test
    fun viewReadsOnlyContentData() {
        assertEquals(CsvImportPayload.StreamUri("content://provider/9"), view(scheme = "content", data = "content://provider/9"))
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "file", data = "file:///sdcard/a.csv"))
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "content", data = null))
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "content", data = "   "))
    }

    @Test
    fun phoenixImportDataIsInlineTextNeverAUri() {
        assertEquals(CsvImportPayload.InlineText("content://provider/1"), phoenix("phoenix://import?data=content://provider/1"))
    }

    @Test
    fun phoenixImportDecodesTheDataParameterExactlyOnce() {
        // %2520 decodes to %20, not to a space.
        assertEquals(CsvImportPayload.InlineText("a%20b"), phoenix("phoenix://import?data=a%2520b"))
        assertEquals(CsvImportPayload.InlineText("a b\nsecond"), phoenix("phoenix://import?data=a%20b%0Asecond"))
        assertEquals(CsvImportPayload.InlineText("€,40"), phoenix("phoenix://import?data=%E2%82%AC%2C40"))
        // A literal + is CSV content, not an encoded space.
        assertEquals(CsvImportPayload.InlineText("a+b"), phoenix("phoenix://import?data=a+b"))
        // A malformed escape is kept as written.
        assertEquals(CsvImportPayload.InlineText("50%"), phoenix("phoenix://import?data=50%"))
    }

    @Test
    fun phoenixImportMatchesTheDataKeyLiterally() {
        assertEquals(CsvImportPayload.InlineText("csv"), phoenix("phoenix://import?source=mail&data=csv"))
        assertEquals(CsvImportPayload.Unreadable, phoenix("phoenix://import?d%61ta=csv"))
        assertEquals(CsvImportPayload.Unreadable, phoenix("phoenix://import?source=mail"))
        assertEquals(CsvImportPayload.Unreadable, phoenix("phoenix://import"))
        assertEquals(CsvImportPayload.Unreadable, phoenix("phoenix://import?data=%20%20"))
        assertEquals(CsvImportPayload.InlineText("# v1\na"), phoenix("phoenix://import?data=# v1%0Aa"))
    }

    @Test
    fun otherPhoenixHostsAndOtherActionsAreIgnored() {
        assertEquals(CsvImportPayload.Ignore, view(scheme = "phoenix", host = "settings", data = "phoenix://settings?data=csv"))
        assertEquals(
            CsvImportPayload.Ignore,
            CsvImportIntentClassifier.classify(CsvImportIntentDescriptor("android.intent.action.MAIN", null, null, null)),
        )
        assertEquals(CsvImportPayload.Ignore, CsvImportIntentClassifier.classify(CsvImportIntentDescriptor(null, null, null, null)))
    }
}
