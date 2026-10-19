package com.devil.phoenixproject.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [AndroidBackupDestinationResolver.writeFile] against a fake SAF provider.
 * A document created for the backup must be removed when the write fails, and left
 * in place when the write succeeds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSafPartialDocumentDeleteTest {

    private val authority = "com.android.externalstorage.documents"
    private lateinit var provider: FakeSafDocumentsProvider
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun closePipes() {
        if (::provider.isInitialized) provider.closeReadEnds()
    }

    @Test
    fun writeFailureAfterCreate_deletesTheDocument_andReturnsTheOriginalFailure() = runBlocking {
        provider = installProvider(openFailure = FileNotFoundException("disk full"))
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = File.createTempFile("phoenix-backup", ".json").apply {
                writeText("{}")
                deleteOnExit()
            }.absolutePath,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FileNotFoundException)
        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertEquals(listOf(provider.createdUri), provider.deletedUris)
    }

    @Test
    fun nullOutputStreamAfterCreate_deletesTheDocument_andFails() = runBlocking {
        provider = installProvider(returnNullStream = true)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = File.createTempFile("phoenix-backup", ".json").apply {
                writeText("{}")
                deleteOnExit()
            }.absolutePath,
        )

        assertTrue(result.isFailure)
        assertEquals(
            "Failed to open output stream for ${provider.createdUri}",
            result.exceptionOrNull()?.message,
        )
        assertEquals(listOf(provider.createdUri), provider.deletedUris)
    }

    @Test
    fun successfulWrite_leavesTheCreatedDocument() = runBlocking {
        provider = installProvider()
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")
        val temp = File.createTempFile("phoenix-backup", ".json").apply {
            writeText("""{"ok":true}""")
            deleteOnExit()
        }

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = temp.absolutePath,
        )

        assertTrue(result.isSuccess)
        assertEquals(provider.createdUri.toString(), result.getOrNull())
        assertTrue(provider.deletedUris.isEmpty())
        val written = provider.readWritten()
        assertEquals("""{"ok":true}""", written)
    }

    @Test
    fun createFailure_doesNotDelete() = runBlocking {
        provider = installProvider(failCreate = true)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = "unused.json",
        )

        assertTrue(result.isFailure)
        assertNull(provider.createdUri)
        assertTrue(provider.deletedUris.isEmpty())
    }

    @Test
    fun deleteApiFailure_preservesTheOriginalWriteFailure() = runBlocking {
        provider = installProvider(
            openFailure = FileNotFoundException("disk full"),
            failDelete = true,
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = "unused.json",
        )

        val failure = result.exceptionOrNull()
        assertTrue(failure is FileNotFoundException)
        assertEquals("disk full", failure.message)
        assertTrue(
            provider.deleteAttempts >= 2,
            "deleteDocument throw should fall back to DocumentFile.delete, attempts=${provider.deleteAttempts}",
        )
    }

    @Test
    fun deleteDocumentThrowsOnce_fallsBackToDocumentFileDelete_andRemovesThePartial() = runBlocking {
        provider = installProvider(
            openFailure = FileNotFoundException("disk full"),
            throwDeleteDocumentTimes = 1,
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Phoenix")

        val result = AndroidBackupDestinationResolver(context).writeFile(
            destination = BackupDestination.Custom(treeUri.toString(), "Phoenix"),
            fileName = "backup.json",
            tempFilePath = "unused.json",
        )

        val failure = result.exceptionOrNull()
        assertTrue(failure is FileNotFoundException)
        assertEquals("disk full", failure.message)
        assertTrue(
            provider.deleteAttempts >= 2,
            "deleteDocument throw should fall back to DocumentFile.delete, attempts=${provider.deleteAttempts}",
        )
        assertEquals(listOf(provider.createdUri), provider.deletedUris)
    }

    private fun installProvider(
        openFailure: Exception? = null,
        returnNullStream: Boolean = false,
        failCreate: Boolean = false,
        failDelete: Boolean = false,
        throwDeleteDocumentTimes: Int = 0,
    ): FakeSafDocumentsProvider {
        val info = android.content.pm.ProviderInfo().apply {
            authority = this@AndroidSafPartialDocumentDeleteTest.authority
            exported = true
            grantUriPermissions = true
        }
        val installed = Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java).create(info).get()
        installed.openFailure = openFailure
        installed.returnNullStream = returnNullStream
        installed.failCreate = failCreate
        installed.failDelete = failDelete
        installed.throwDeleteDocumentTimes = throwDeleteDocumentTimes
        return installed
    }
}

class FakeSafDocumentsProvider : ContentProvider() {
    var openFailure: Exception? = null
    var returnNullStream: Boolean = false
    var failCreate: Boolean = false
    var failDelete: Boolean = false

    /** Times [DocumentsContract.deleteDocument] throws before a later call can succeed. */
    var throwDeleteDocumentTimes: Int = 0
    var createdUri: Uri? = null
    var deleteAttempts: Int = 0
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
    ): Cursor = MatrixCursor(
        projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
    )

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
        return when (method) {
            // Hidden DocumentsContract constants: "android:createDocument", "android:deleteDocument", "uri".
            "android:createDocument" -> {
                if (failCreate) throw IllegalStateException("create rejected")
                val parent = extras?.getParcelable("uri", Uri::class.java)
                    ?: error("createDocument missing uri extra: ${extras?.keySet()}")
                val documentId = DocumentsContract.getTreeDocumentId(parent) + "/backup.json"
                val created = DocumentsContract.buildDocumentUriUsingTree(parent, documentId)
                createdUri = created
                Bundle().apply { putParcelable("uri", created) }
            }
            "android:deleteDocument" -> {
                deleteAttempts += 1
                if (failDelete || throwDeleteDocumentTimes > 0) {
                    if (throwDeleteDocumentTimes > 0) throwDeleteDocumentTimes -= 1
                    throw IllegalStateException("delete rejected")
                }
                val target = extras?.getParcelable("uri", Uri::class.java)
                    ?: error("deleteDocument missing uri extra: ${extras?.keySet()}")
                deletedUris += target
                Bundle()
            }
            else -> super.call(method, arg, extras)
        }
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
