package com.devil.phoenixproject.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupJsonWriterAndroidTest {

    @Test
    fun writeBeforeOpenAndAfterCloseThrowsIllegalStateException() {
        val tempFile = kotlin.io.path.createTempFile("backup-writer-test", ".json").toFile()
        tempFile.delete()
        val writer = BackupJsonWriter(tempFile.absolutePath)

        val beforeOpen = assertFailsWith<IllegalStateException> {
            writer.write("{\"version\":1}")
        }
        assertTrue(beforeOpen.message?.contains("no open writer") == true)

        writer.open()
        writer.write("{\"version\":1}")
        writer.flush()
        writer.close()

        assertEquals("{\"version\":1}", File(tempFile.absolutePath).readText(Charsets.UTF_8))

        val afterClose = assertFailsWith<IllegalStateException> {
            writer.write(",\"extra\":true")
        }
        assertTrue(afterClose.message?.contains("no open writer") == true)

        writer.delete()
        assertFalse(tempFile.exists())
    }
}
