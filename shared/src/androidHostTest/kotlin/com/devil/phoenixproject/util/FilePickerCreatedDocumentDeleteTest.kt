package com.devil.phoenixproject.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [saveFilePickerCreatedDocument] against a fake SAF provider.
 * CreateDocument has already inserted the document; a failed write must remove
 * it, and a successful write must leave it in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilePickerCreatedDocumentDeleteTest {

    private val authority = "com.devil.phoenixproject.filepicker.documents"
    private val documentUri = DocumentsContract.buildDocumentUri(authority, "primary:Download/phoenix.json")
    private lateinit var provider: FakeFilePickerDocumentsProvider
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun closePipes() {
        if (::provider.isInitialized) provider.closeReadEnds()
    }

    @Test
    fun writeFailure_deletesTheDocument_andReturnsNull() {
        provider = installProvider(openFailure = FileNotFoundException("disk full"))

        val result = saveFilePickerCreatedDocument(context, documentUri, "{}")

        assertNull(result)
        assertEquals(listOf(documentUri), provider.deletedUris)
    }

    @Test
    fun nullOutputStream_deletesTheDocument_andReturnsNull() {
        provider = installProvider(returnNullStream = true)

        val result = saveFilePickerCreatedDocument(context, documentUri, "{}")

        assertNull(result)
        assertEquals(listOf(documentUri), provider.deletedUris)
    }

    @Test
    fun successfulWrite_leavesTheCreatedDocument() {
        provider = installProvider()

        val result = saveFilePickerCreatedDocument(context, documentUri, """{"ok":true}""")

        assertEquals(documentUri.toString(), result)
        assertTrue(provider.deletedUris.isEmpty())
        assertEquals("""{"ok":true}""", provider.readWritten())
    }

    @Test
    fun deleteApiFailure_preservesTheNullResult() {
        provider = installProvider(
            openFailure = FileNotFoundException("disk full"),
            failDelete = true,
        )

        val result = saveFilePickerCreatedDocument(context, documentUri, "{}")

        assertNull(result)
        assertTrue(
            provider.deleteAttempts >= 2,
            "deleteDocument should fall back to DocumentFile.delete, attempts=${provider.deleteAttempts} calls=${provider.calls}",
        )
    }

    @Test
    fun writeFailure_onAnExistingDocument_keepsIt() {
        // CreateDocument returns the existing document when the user confirms an overwrite.
        provider = installProvider(openFailure = FileNotFoundException("read-only"), existingSize = 512L)

        val result = saveFilePickerCreatedDocument(context, documentUri, "{}")

        assertNull(result)
        assertTrue(provider.deletedUris.isEmpty())
        assertEquals(0, provider.deleteAttempts)
    }

    @Test
    fun writeFailure_withAnUnknownSize_keepsTheDocument() {
        provider = installProvider(openFailure = FileNotFoundException("disk full"), existingSize = null)

        val result = saveFilePickerCreatedDocument(context, documentUri, "{}")

        assertNull(result)
        assertEquals(0, provider.deleteAttempts)
    }

    @Test
    fun writeCancellation_deletesTheDocument_andRethrowsCancellation() {
        val cancellation = CancellationException("cancelled")
        provider = installProvider(openFailure = cancellation)

        val thrown = assertFailsWith<CancellationException> {
            saveFilePickerCreatedDocument(context, documentUri, "{}")
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(documentUri), provider.deletedUris)
    }

    private fun installProvider(
        openFailure: Exception? = null,
        returnNullStream: Boolean = false,
        failDelete: Boolean = false,
        existingSize: Long? = 0L,
    ): FakeFilePickerDocumentsProvider {
        val info = android.content.pm.ProviderInfo().apply {
            authority = this@FilePickerCreatedDocumentDeleteTest.authority
            exported = true
            grantUriPermissions = true
        }
        val installed = Robolectric.buildContentProvider(FakeFilePickerDocumentsProvider::class.java)
            .create(info)
            .get()
        installed.openFailure = openFailure
        installed.returnNullStream = returnNullStream
        installed.failDelete = failDelete
        installed.existingSize = existingSize
        return installed
    }
}

/**
 * Minimal DocumentsProvider stand-in. [DocumentsContract.deleteDocument] reaches
 * [call] with the hidden method `android:deleteDocument`.
 */
class FakeFilePickerDocumentsProvider : ContentProvider() {
    var openFailure: Exception? = null
    var returnNullStream: Boolean = false
    var failDelete: Boolean = false

    /** Size reported for the document before the write; null reports an unknown size. */
    var existingSize: Long? = 0L
    var deleteAttempts: Int = 0
    val calls = mutableListOf<String>()
    val deletedUris = mutableListOf<Uri>()
    private val readEnds = mutableListOf<ParcelFileDescriptor>()
    private var written: ParcelFileDescriptor? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val cursor = MatrixCursor(columns)
        if (columns.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_SIZE))) {
            cursor.addRow(arrayOf<Any?>(existingSize))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        openFailure?.let { throw it }
        if (returnNullStream) return null
        val pipe = ParcelFileDescriptor.createPipe()
        readEnds += pipe[0]
        written = pipe[0]
        return pipe[1]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        calls += method
        if (method != "android:deleteDocument") return super.call(method, arg, extras)
        deleteAttempts += 1
        if (failDelete) throw IllegalStateException("delete rejected")
        // DocumentsContract.EXTRA_URI is hidden; the public value is "uri".
        val target = extras?.getParcelable("uri", Uri::class.java)
            ?: error("deleteDocument missing uri extra: ${extras?.keySet()}")
        deletedUris += target
        return Bundle()
    }

    fun readWritten(): String {
        val readEnd = checkNotNull(written)
        return ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().use { it.readText() }
    }

    fun closeReadEnds() {
        readEnds.forEach { end ->
            try {
                end.close()
            } catch (_: Exception) {
                // already closed by readWritten()
            }
        }
    }
}
