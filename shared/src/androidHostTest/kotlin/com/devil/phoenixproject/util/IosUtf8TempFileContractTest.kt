package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS UTF-8 temp writer and the two callers' error styles.
 * The writer itself is Kotlin/Native and does not run on the host.
 */
class IosUtf8TempFileContractTest {

    @Test
    fun sharedWriterDeletesThenWritesUtf8() {
        val writer = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/Utf8TempFile.ios.kt")
        val body = functionBody(writer, "internal fun writeUtf8TempFile")

        val deleteAt = body.indexOf("removeItemAtPath")
        val writeAt = body.indexOf("writeToFile")
        assertTrue(deleteAt >= 0 && writeAt > deleteAt, "existing temp file is removed before the write")
        assertTrue(body.contains("fileExistsAtPath"), "delete runs only when a file is already there")
        assertTrue(body.contains("NSUTF8StringEncoding"), "the shared writer is the UTF-8 write")
        assertTrue(body.contains("atomically = true"), "the write stays atomic")
        assertTrue(!body.contains("catch"), "the writer does not absorb caller error styles")
        assertTrue(!body.contains("IllegalStateException"), "CSV failure stays in CsvExporter")
    }

    @Test
    fun filePickerKeepsNullableLoggedFailure() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/FilePicker.ios.kt")
        val body = region(source, "private fun saveToTempFile", "Remember a FilePicker instance")
        val catchAt = body.indexOf("catch (e: Exception)")

        assertTrue(body.contains("writeUtf8TempFile(fileName, content)"), "picker saves through the shared writer")
        assertTrue(catchAt >= 0, "picker still catches write exceptions")
        assertTrue(body.contains("Failed to save temp file"), "picker still logs its own message")
        assertTrue(body.indexOf("null", catchAt) > catchAt, "picker still returns null on failure")
        assertTrue(!body.substring(catchAt).contains("throw"), "picker does not throw on failure")
        assertTrue(!source.contains("writeToFile"), "picker no longer writes UTF-8 itself")
        assertTrue(!source.contains("NSUTF8StringEncoding"), "UTF-8 encoding stays in the shared writer")
    }

    @Test
    fun csvExporterKeepsThrowingFailure() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/CsvExporter.ios.kt")
        val body = functionBody(source, "private fun writeToTempFile")

        assertTrue(body.contains("writeUtf8TempFile(fileName, content)"), "CSV export writes through the shared writer")
        assertTrue(
            body.contains("throw IllegalStateException(\"Failed to write CSV to \${NSTemporaryDirectory()}\$fileName\")"),
            "CSV export still throws with the temp path",
        )
        assertTrue(source.contains("Result.failure(e)"), "writeCsv still wraps exceptions as Result.failure")
        assertTrue(!source.contains("writeToFile"), "CSV export no longer writes UTF-8 itself")
        assertTrue(!source.contains("NSUTF8StringEncoding"), "UTF-8 encoding stays in the shared writer")
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

    private fun region(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue(start >= 0, "Missing $startMarker")
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue(end > start, "Missing $endMarker")
        return source.substring(start, end)
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("Unclosed $signature")
    }
}
