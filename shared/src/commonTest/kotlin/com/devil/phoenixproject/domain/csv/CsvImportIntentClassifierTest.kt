package com.devil.phoenixproject.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #1242: intent classification is pure and decides exactly four things — read a stream,
 * use inline text, a terminal error, or ignore the intent entirely. Decoding happens once and
 * `data` is inline CSV, never a URI.
 */
class CsvImportIntentClassifierTest {
    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"

    private fun send(
        mimeType: String? = "text/csv",
        streamUri: String? = null,
        clipDataUri: String? = null,
        inlineText: String? = null,
    ) = CsvImportIntentClassifier.classify(
        CsvImportIntentDescriptor(
            action = send,
            mimeType = mimeType,
            scheme = null,
            host = null,
            streamUri = streamUri,
            clipDataUri = clipDataUri,
            inlineText = inlineText,
        ),
    )

    private fun view(
        scheme: String?,
        host: String? = null,
        data: String? = null,
        mimeType: String? = "text/csv",
    ) = CsvImportIntentClassifier.classify(
        CsvImportIntentDescriptor(
            action = view,
            mimeType = mimeType,
            scheme = scheme,
            host = host,
            data = data,
        ),
    )

    @Test
    fun sendStreamWinsOverEverything() {
        assertEquals(
            CsvImportPayload.StreamUri("content://provider/1"),
            send(
                streamUri = "content://provider/1",
                clipDataUri = "content://provider/2",
                inlineText = "# inline",
            ),
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
    fun sendTextPlainInlineTextIsAccepted() {
        assertEquals(
            CsvImportPayload.InlineText("# phoenix_routine_csv_version=1"),
            send(mimeType = "text/plain", inlineText = "# phoenix_routine_csv_version=1"),
        )
    }

    @Test
    fun sendCsvMimeInlineTextIsAccepted() {
        for (mime in listOf("text/csv", "text/comma-separated-values", "application/csv")) {
            assertEquals(CsvImportPayload.InlineText("csv"), send(mimeType = mime, inlineText = "csv"), mime)
        }
    }

    @Test
    fun sendInlineTextForAnyOtherMimeIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, send(mimeType = "application/pdf", inlineText = "csv"))
    }

    @Test
    fun sendWithNoSourceAtAllIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, send())
    }

    @Test
    fun blankInlineTextIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, send(mimeType = "text/plain", inlineText = "   \n"))
    }

    @Test
    fun inlineTextOverTheByteBoundIsTooLargeNotRetained() {
        val oversize = "a".repeat(RoutineCsvFormat.MAX_BYTES + 1)
        assertEquals(CsvImportPayload.TooLarge, send(mimeType = "text/plain", inlineText = oversize))
    }

    @Test
    fun viewContentAndFileUseIntentDataOnly() {
        assertEquals(CsvImportPayload.StreamUri("content://provider/9"), view(scheme = "content", data = "content://provider/9"))
        assertEquals(CsvImportPayload.StreamUri("file:///sdcard/a.csv"), view(scheme = "file", data = "file:///sdcard/a.csv"))
    }

    @Test
    fun viewWithoutDataIsUnreadable() {
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "content", data = null))
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "content", data = "   "))
    }

    @Test
    fun phoenixImportDataIsInlineTextNotAUri() {
        assertEquals(
            CsvImportPayload.InlineText("content://provider/1"),
            view(scheme = "phoenix", host = "import", data = "phoenix://import?data=content://provider/1"),
        )
    }

    @Test
    fun phoenixImportDecodesTheDataParameterExactlyOnce() {
        // %2520 decodes to %20, not to a space: one decode pass, ever.
        val once = view(scheme = "phoenix", host = "import", data = "phoenix://import?data=a%2520b")
        assertEquals(CsvImportPayload.InlineText("a%20b"), once)

        val decoded = view(scheme = "phoenix", host = "import", data = "phoenix://import?data=a%20b%0Asecond")
        assertEquals(CsvImportPayload.InlineText("a b\nsecond"), decoded)

        val utf8 = view(scheme = "phoenix", host = "import", data = "phoenix://import?data=%E2%82%AC%2C40")
        assertEquals(CsvImportPayload.InlineText("€,40"), utf8)
    }

    @Test
    fun phoenixImportIgnoresOtherQueryKeysButNeedsData() {
        assertEquals(
            CsvImportPayload.InlineText("csv"),
            view(scheme = "phoenix", host = "import", data = "phoenix://import?source=mail&data=csv"),
        )
        assertEquals(
            CsvImportPayload.Unreadable,
            view(scheme = "phoenix", host = "import", data = "phoenix://import?source=mail"),
        )
        assertEquals(CsvImportPayload.Unreadable, view(scheme = "phoenix", host = "import", data = "phoenix://import"))
        assertEquals(
            CsvImportPayload.Unreadable,
            view(scheme = "phoenix", host = "import", data = "phoenix://import?data=%20%20"),
        )
    }

    @Test
    fun phoenixImportOversizeDataIsTooLarge() {
        val oversize = "a".repeat(RoutineCsvFormat.MAX_BYTES + 1)
        val result = view(scheme = "phoenix", host = "import", data = "phoenix://import?data=$oversize")
        assertEquals(CsvImportPayload.TooLarge, result)
    }

    @Test
    fun phoenixWithAnotherHostIsIgnored() {
        val result = view(scheme = "phoenix", host = "other", data = "phoenix://other?data=csv")
        assertEquals(CsvImportPayload.Ignore, result)
    }

    @Test
    fun mainNullAndHealthConnectActionsAreIgnored() {
        val ignored = listOf(
            CsvImportIntentClassifier.ACTION_MAIN,
            null,
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
            "android.intent.action.VIEW_PERMISSION_USAGE",
            "androidx.health.ACTION_SHOW_ONBOARDING",
        )
        for (action in ignored) {
            val payload = CsvImportIntentClassifier.classify(
                CsvImportIntentDescriptor(action = action, mimeType = "text/csv", scheme = null, host = null, streamUri = "content://x"),
            )
            assertEquals(CsvImportPayload.Ignore, payload, "$action must be ignored")
        }
    }

    @Test
    fun otherViewSchemesAreIgnored() {
        assertEquals(CsvImportPayload.Ignore, view(scheme = "https", data = "https://example.com/a.csv"))
        assertEquals(CsvImportPayload.Ignore, view(scheme = null, data = "content://x"))
    }

    @Test
    fun aHandledDeliveryIsAnythingButIgnore() {
        assertTrue(CsvImportPayload.StreamUri("content://x").isHandledImport)
        assertTrue(CsvImportPayload.InlineText("x").isHandledImport)
        assertTrue(CsvImportPayload.Unreadable.isHandledImport)
        assertTrue(CsvImportPayload.TooLarge.isHandledImport)
        assertTrue(!CsvImportPayload.Ignore.isHandledImport)
    }
}

/** The consume rule (B4): handled once, never again on restore, always on a new delivery. */
class CsvImportDeliveryPolicyTest {
    @Test
    fun onCreateHandlesOnlyWhenSavedStateHasNoHandledDelivery() {
        assertTrue(CsvImportDeliveryPolicy.acceptOnCreate(restoredHandled = false))
        assertTrue(!CsvImportDeliveryPolicy.acceptOnCreate(restoredHandled = true))
    }

    @Test
    fun onNewIntentAlwaysHandlesAGenuineImportDelivery() {
        assertTrue(CsvImportDeliveryPolicy.acceptOnNewIntent(CsvImportPayload.StreamUri("content://x")))
        assertTrue(CsvImportDeliveryPolicy.acceptOnNewIntent(CsvImportPayload.InlineText("csv")))
        assertTrue(!CsvImportDeliveryPolicy.acceptOnNewIntent(CsvImportPayload.Ignore))
    }

    @Test
    fun onlyHandledImportsReplaceTheActivityIntent() {
        assertTrue(CsvImportDeliveryPolicy.shouldSanitizeActivityIntent(CsvImportPayload.Unreadable))
        assertTrue(CsvImportDeliveryPolicy.shouldSanitizeActivityIntent(CsvImportPayload.TooLarge))
        assertTrue(!CsvImportDeliveryPolicy.shouldSanitizeActivityIntent(CsvImportPayload.Ignore))
    }
}

/** Percent decoding is one pass, keeps `+`, and leaves malformed escapes alone. */
class CsvImportPercentDecodeTest {
    @Test
    fun decodesPercentEscapesOnce() {
        assertEquals("a b", CsvImportIntentClassifier.percentDecode("a%20b"))
        assertEquals("a%20b", CsvImportIntentClassifier.percentDecode("a%2520b"))
        assertEquals("100%", CsvImportIntentClassifier.percentDecode("100%25"))
    }

    @Test
    fun keepsPlusAndMalformedEscapes() {
        assertEquals("a+b", CsvImportIntentClassifier.percentDecode("a+b"))
        assertEquals("100%zz", CsvImportIntentClassifier.percentDecode("100%zz"))
        assertEquals("tail%", CsvImportIntentClassifier.percentDecode("tail%"))
    }

    @Test
    fun decodesUtf8ByteSequences() {
        assertEquals("Übung", CsvImportIntentClassifier.percentDecode("%C3%9Cbung"))
        assertEquals("€,40", CsvImportIntentClassifier.percentDecode("%E2%82%AC%2C40"))
    }
}
