package com.devil.phoenixproject.testutil

/**
 * KMP-compatible source-file reader used by tests that need to assert on the
 * text of a production source file. The iOS target does not expose `java.io.File`,
 * so we provide a small `expect/actual` shim that the per-platform source set
 * implements using the platform's native file API.
 *
 * The intent is "I want to read a file under the project root from a commonTest
 * test". A failure to read a file is returned as `null` so the caller can produce
 * a useful assertion message with the candidate paths it tried.
 */
expect fun readProjectFile(relativePath: String): String?

internal fun extractFunctionBody(source: String, signature: String): String {
    val start = source.indexOf(signature)
    check(start >= 0) { "Missing `$signature`" }
    val openBrace = source.indexOf('{', start)
    check(openBrace >= 0) { "Missing body for `$signature`" }
    var depth = 0
    for (index in openBrace until source.length) {
        when (source[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return source.substring(openBrace, index + 1)
            }
        }
    }
    error("Unclosed body for `$signature`")
}
