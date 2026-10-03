package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS UTF-8 file read and leaves workout CSV import on its own call.
 * The reader itself is Kotlin/Native and does not run on the host.
 */
class IosUriContentReaderContractTest {

    @Test
    fun bothUriReadersShareOnePrivateUtf8Read() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/UriContentReader.ios.kt")
        val readerStart = source.indexOf("private fun readUtf8File")
        assertTrue(readerStart >= 0, "Missing private fun readUtf8File")
        val reader = source.substring(readerStart)
        val readAll = functionBody(source, "actual suspend fun readUriContent(")
        val bounded = functionBody(source, "actual suspend fun readUriContentUpTo(")

        assertEquals(
            1,
            Regex("""NSString\.stringWithContentsOfFile\(""").findAll(source).count(),
            "one Foundation UTF-8 read",
        )
        assertTrue(reader.contains("NSUTF8StringEncoding"), "the private reader is the UTF-8 read")
        assertTrue(!reader.contains("catch"), "the reader does not absorb caller error styles")
        assertTrue(readAll.contains("readUtf8File(uriOrPath)"), "readUriContent uses the private reader")
        assertTrue(bounded.contains("readUtf8File(uriOrPath)"), "the in-limit branch uses the private reader")
        assertTrue(!readAll.contains("stringWithContentsOfFile"), "readUriContent no longer reads UTF-8 itself")
        assertTrue(!bounded.contains("stringWithContentsOfFile"), "readUriContentUpTo no longer reads UTF-8 itself")

        val tooLarge = bounded.indexOf("size > maxBytes -> BoundedUriContent.TooLarge")
        val read = bounded.indexOf("readUtf8File(uriOrPath)")
        assertTrue(tooLarge >= 0 && read > tooLarge, "oversized files are rejected before the shared read")
        assertTrue(readAll.contains("Failed to read file content"), "readUriContent still logs its own failure")
        assertTrue(bounded.contains("Failed to read file content"), "readUriContentUpTo still logs its own failure")
        assertTrue(bounded.contains("BoundedUriContent.Unreadable"), "a failed in-limit read stays unreadable")
    }

    @Test
    fun csvImporterKeepsItsOwnRead() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/CsvImporter.ios.kt")
        val body = functionBody(source, "override suspend fun importFromCsv")

        assertTrue(body.contains("NSString.stringWithContentsOfFile"), "CSV import still reads the file itself")
        assertTrue(!source.contains("readUtf8File"), "CSV import does not use the URI reader")
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

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
