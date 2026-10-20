package com.devil.phoenixproject.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.testutil.readProjectFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * The three FileProvider share sheets differ only in MIME type, subject,
 * chooser title, and whether the send intent also carries NEW_TASK.
 * Grant flags, ClipData, the chooser NEW_TASK flag, and authority stay put.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileProviderShareChooserTest {

    @Test
    fun newTaskOnSendIntent_setsReadGrantAndNewTaskOnTheSendIntent() {
        val uri = Uri.parse("content://com.devil.phoenixproject.fileprovider/exports/a.csv")
        val context = RecordingContext(ApplicationProvider.getApplicationContext())

        context.launchFileProviderShareChooser(
            uri = uri,
            mimeType = "text/csv",
            chooserTitle = "Share CSV",
            subject = "Project Phoenix Export: a.csv",
            newTaskOnSendIntent = true,
        )

        val chooser = context.started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share CSV", chooser.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, chooser.flags)
        assertNull(chooser.clipData)

        val send = chooser.sendIntent()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        assertEquals(uri, send.streamUri())
        assertEquals("Project Phoenix Export: a.csv", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.clipData)
        assertNull(send.data)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
            send.flags,
        )
    }

    @Test
    fun databaseExportShape_omitsNewTaskOnTheSendIntent() {
        val uri = Uri.parse("content://com.devil.phoenixproject.fileprovider/exports/db.zip")
        val context = RecordingContext(ApplicationProvider.getApplicationContext())

        context.launchFileProviderShareChooser(
            uri = uri,
            mimeType = "application/zip",
            chooserTitle = "Export database files",
            subject = "Project Phoenix database files",
            newTaskOnSendIntent = false,
        )

        val chooser = context.started.single()
        assertEquals("Export database files", chooser.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, chooser.flags)
        assertNull(chooser.clipData)

        val send = chooser.sendIntent()
        assertEquals("application/zip", send.type)
        assertEquals(uri, send.streamUri())
        assertEquals("Project Phoenix database files", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.clipData)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals(0, send.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun startActivityFailure_propagatesToTheCaller() {
        val context = RecordingContext(ApplicationProvider.getApplicationContext()).apply {
            startError = ActivityNotFoundException("no handler")
        }

        assertFailsWith<ActivityNotFoundException> {
            context.launchFileProviderShareChooser(
                uri = Uri.parse("content://com.devil.phoenixproject.fileprovider/exports/backup.json"),
                mimeType = "application/json",
                chooserTitle = "Share Backup",
                subject = "Project Phoenix Backup",
                newTaskOnSendIntent = true,
            )
        }
        assertEquals(1, context.started.size)
    }

    @Test
    fun callSitesKeepTheirOwnFailurePolicyAndChooserArguments() {
        val helper = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/FileProviderShareChooser.kt",
        ) ?: fail("Missing FileProviderShareChooser.kt")
        val csv = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/CsvExporter.android.kt",
        ) ?: fail("Missing CsvExporter.android.kt")
        val backup = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt",
        ) ?: fail("Missing DataBackupManager.android.kt")
        val database = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/DatabaseFileExport.android.kt",
        ) ?: fail("Missing DatabaseFileExport.android.kt")

        assertEquals(1, helper.split("fun Context.launchFileProviderShareChooser").size - 1)
        assertTrue(helper.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(helper.contains("Intent.createChooser"))
        assertTrue(helper.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertFalse(helper.contains("setClipData"))
        assertFalse(helper.contains("clipData"))
        assertFalse(helper.contains("file.delete"))
        assertFalse(helper.contains("return false"))

        assertEquals(1, csv.split("launchFileProviderShareChooser").size - 1)
        assertTrue(csv.contains("mimeType = \"text/csv\""))
        assertTrue(csv.contains("chooserTitle = \"Share CSV\""))
        assertTrue(csv.contains("subject = \"Project Phoenix Export: \$fileName\""))
        assertTrue(csv.contains("newTaskOnSendIntent = true"))
        assertTrue(csv.contains("\${context.packageName}.fileprovider"))
        assertTrue(
            csv.contains(
                """
                |        } catch (e: Exception) {
                |            android.util.Log.e("CsvExporter", "Failed to share CSV: ${'$'}{e.message}", e)
                |        }
                """.trimMargin(),
            ),
        )
        assertFalse(csv.contains("Intent.createChooser"))

        assertEquals(1, backup.split("launchFileProviderShareChooser").size - 1)
        assertTrue(backup.contains("mimeType = \"application/json\""))
        assertTrue(backup.contains("chooserTitle = \"Share Backup\""))
        assertTrue(backup.contains("subject = \"Project Phoenix Backup\""))
        assertTrue(backup.contains("newTaskOnSendIntent = true"))
        assertTrue(backup.contains("\${context.packageName}.fileprovider"))
        assertTrue(
            backup.contains(
                """
                |        } catch (e: Exception) {
                |            Logger.e(e) { "Failed to share backup file: ${'$'}{file.absolutePath}" }
                |            // Clean up cache file on sharing error
                |            file.delete()
                |            throw e
                |        }
                """.trimMargin(),
            ),
        )
        assertFalse(backup.contains("Intent.createChooser"))

        assertEquals(1, database.split("launchFileProviderShareChooser").size - 1)
        assertTrue(database.contains("mimeType = \"application/zip\""))
        assertTrue(database.contains("chooserTitle = \"Export database files\""))
        assertTrue(database.contains("subject = \"Project Phoenix database files\""))
        assertTrue(database.contains("newTaskOnSendIntent = false"))
        assertTrue(database.contains("\${context.packageName}.fileprovider"))
        assertTrue(
            database.contains(
                """
                |    } catch (e: CancellationException) {
                |        throw e
                |    } catch (e: Exception) {
                |        Logger.e(e) { "Database file export failed" }
                |        false
                |    }
                """.trimMargin(),
            ),
        )
        assertFalse(database.contains("Intent.createChooser"))
    }

    @Suppress("DEPRECATION")
    private fun Intent.sendIntent(): Intent =
        getParcelableExtra(Intent.EXTRA_INTENT) ?: fail("chooser is missing EXTRA_INTENT")

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        var startError: Exception? = null

        override fun startActivity(intent: Intent) {
            started += intent
            startError?.let { throw it }
        }
    }
}
