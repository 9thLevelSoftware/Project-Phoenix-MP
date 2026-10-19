package com.devil.phoenixproject.data.csv

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.domain.csv.CsvImportPayload
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1242 Android intake: any app can send this activity an intent, so a malformed bundle
 * is an unreadable file (not a crash), extras are only read for SEND, and only another app's
 * `content://` URI is ever opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCsvImportIntakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val own = "com.devil.phoenixproject"

    /** An intent whose extras throw on access, as an unknown Parcelable does when unparcelled. */
    private class PoisonedIntent(action: String, data: Uri? = null) : Intent(action, data) {
        var extrasRead = false

        private fun poison(): Nothing {
            extrasRead = true
            throw BadParcelableException("ClassNotFoundException when unmarshalling: evil.Payload")
        }

        override fun getExtras(): Bundle? = poison()

        @Deprecated("Deprecated in Java")
        override fun <T : android.os.Parcelable?> getParcelableExtra(name: String?): T? = poison()

        override fun <T : Any?> getParcelableExtra(name: String?, clazz: Class<T>): T? = poison()

        override fun getCharSequenceExtra(name: String?): CharSequence? = poison()
    }

    @Test
    fun aSendWithAStreamIsReadAsAContentStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.example.files/routine.csv"))
        assertEquals(CsvImportPayload.StreamUri("content://com.example.files/routine.csv"), AndroidCsvImportIntake.describe(intent))
    }

    @Test
    fun aSendWithOnlyClipDataUsesItemZero() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/csv")
        intent.clipData = ClipData.newRawUri("csv", Uri.parse("content://com.example.files/clip.csv"))
        assertEquals(CsvImportPayload.StreamUri("content://com.example.files/clip.csv"), AndroidCsvImportIntake.describe(intent))
    }

    @Test
    fun aSendWithTextIsInlineText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "# csv")
        assertEquals(CsvImportPayload.InlineText("# csv"), AndroidCsvImportIntake.describe(intent))
    }

    @Test
    fun aSendOfAFileUriIsRefused() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/$own/databases/phoenix.db"))
        assertEquals(CsvImportPayload.Unreadable, AndroidCsvImportIntake.describe(intent))
    }

    @Test
    fun aSendWhoseExtrasCannotBeUnparcelledIsUnreadableNotACrash() {
        val intent = PoisonedIntent(Intent.ACTION_SEND).apply { type = "text/csv" }
        assertEquals(CsvImportPayload.Unreadable, AndroidCsvImportIntake.describe(intent))
        assertTrue(intent.extrasRead)
    }

    @Test
    fun aViewNeverTouchesItsExtras() {
        val intent = PoisonedIntent(Intent.ACTION_VIEW, Uri.parse("content://com.example.files/a.csv"))
        assertEquals(CsvImportPayload.StreamUri("content://com.example.files/a.csv"), AndroidCsvImportIntake.describe(intent))
        assertFalse(intent.extrasRead)

        val link = PoisonedIntent(Intent.ACTION_VIEW, Uri.parse("phoenix://import?data=%23%20csv"))
        assertEquals(CsvImportPayload.InlineText("# csv"), AndroidCsvImportIntake.describe(link))
        assertFalse(link.extrasRead)
    }

    @Test
    fun onlyAnotherAppsContentProviderIsForeign() {
        val unknown: (String) -> String? = { null }
        assertTrue(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://com.example.files/a.csv"), own, unknown))
        assertTrue(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://com.example.files/a.csv"), own) { "com.example" })

        // This app's own providers, by name or by owner, with or without a userId@ prefix.
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://$own.fileprovider/x"), own, unknown))
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://0@$own.fileprovider/x"), own, unknown))
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://$own/x"), own, unknown))
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content://library.startup/x"), own) { own })

        // Never anything but content://.
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("file:///sdcard/a.csv"), own, unknown))
        assertFalse(AndroidCsvImportIntake.isForeignContentUri(Uri.parse("content:///no-authority"), own, unknown))
    }

    @Test
    fun aForeignStreamIsReadWithinTheByteBound() = runTest {
        val uri = Uri.parse("content://com.example.files/routine.csv")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("# csv".toByteArray()))
        assertEquals(CsvImportDeliveryResult.Read("# csv"), AndroidCsvImportIntake.read(context, uri.toString(), maxBytes = 100))

        val big = Uri.parse("content://com.example.files/big.csv")
        shadowOf(context.contentResolver).registerInputStream(big, ByteArrayInputStream(ByteArray(101)))
        assertEquals(CsvImportDeliveryResult.TooLarge, AndroidCsvImportIntake.read(context, big.toString(), maxBytes = 100))
    }

    @Test
    fun thisAppsOwnProviderIsRefusedWithoutBeingOpened() = runTest {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/private.csv")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("secret".toByteArray()))
        assertEquals(CsvImportDeliveryResult.Unreadable, AndroidCsvImportIntake.read(context, uri.toString(), maxBytes = 100))
    }
}
