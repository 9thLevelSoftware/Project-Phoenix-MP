package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the iOS backup stream's read-error vs end-of-file split.
 * [FileBackupStreamSource] is Kotlin/Native and does not run on the host.
 */
class IosBackupStreamSourceContractTest {

    @Test
    fun negativeReadFailsWithStreamErrorInsteadOfEof() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupStreamSource.ios.kt")
        val body = functionBody(source, "private fun refill()")

        assertTrue(!body.contains("bytesRead <= 0"), "a negative read is not end of stream")
        val errorAt = body.indexOf("bytesRead < 0")
        val eofAt = body.indexOf("bytesRead == 0")
        assertTrue(errorAt >= 0 && eofAt > errorAt, "a read error is rejected before end of stream")

        val errorBranch = body.substring(errorAt, eofAt)
        assertTrue(errorBranch.contains("streamError?.localizedDescription"), "the failure surfaces the stream error description")
        assertTrue(
            errorBranch.contains("throw IllegalStateException(\"Backup stream read failed: \$description\")"),
            "import already handles IllegalStateException",
        )
        assertTrue(!errorBranch.contains("eof = true"), "a read error does not complete the import")

        val eofBranch = body.substring(eofAt, body.indexOf("rawLen", eofAt))
        assertTrue(eofBranch.contains("eof = true"), "a zero read is still end of stream")
        assertTrue(!eofBranch.contains("throw"), "end of stream does not fail the import")
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
