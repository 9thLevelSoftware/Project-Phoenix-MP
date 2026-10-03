package com.devil.phoenixproject.data.integration

/**
 * Escapes a string for inclusion inside a hand-built JSON string literal.
 * Shared by the Android, iOS, and body-weight sync metadata builders.
 */
internal fun String.escapeJson(): String = buildString {
    this@escapeJson.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
