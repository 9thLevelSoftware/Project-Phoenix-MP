package com.devil.phoenixproject.util

/**
 * Streaming JSON pull-parser that reads character-by-character from a [BackupStreamSource].
 *
 * Navigates JSON structure and extracts individual array elements as raw JSON strings,
 * enabling per-entity deserialization with kotlinx.serialization without loading the
 * entire backup file into memory.
 *
 * **Design rationale**: Backup files can reach hundreds of megabytes (metric samples alone
 * may contain millions of rows). A DOM or even a full-stream kotlinx.serialization parse
 * would require holding the entire object graph in memory. This navigator lets the caller
 * walk the top-level structure, pull one entity at a time as a raw JSON string, deserialize
 * it, persist it to SQLDelight, and discard it — keeping peak memory at roughly one entity
 * (~100 bytes to ~10 KB).
 *
 * **Thread safety**: Not thread-safe. Callers must ensure single-threaded access.
 *
 * Usage example:
 * ```kotlin
 * val nav = BackupJsonNavigator(source)
 * nav.beginObject()
 * while (nav.hasNextInObject()) {
 *     when (nav.nextName()) {
 *         "version" -> nav.nextInt()
 *         "data" -> {
 *             nav.beginObject()
 *             while (nav.hasNextInObject()) {
 *                 when (nav.nextName()) {
 *                     "workoutSessions" -> {
 *                         nav.beginArray()
 *                         while (nav.hasNextInArray()) {
 *                             val json = nav.nextValueAsString()
 *                             // deserialize and persist
 *                         }
 *                         nav.endArray()
 *                     }
 *                     else -> nav.skipValue()
 *                 }
 *             }
 *             nav.endObject()
 *         }
 *         else -> nav.skipValue()
 *     }
 * }
 * nav.endObject()
 * ```
 */
class BackupJsonNavigator(private val source: BackupStreamSource) {

    // -- Buffered reading --------------------------------------------------------

    private val buffer = CharArray(BUFFER_SIZE)
    private var bufPos = 0
    private var bufLen = 0

    /** Running count of characters consumed — used for error position reporting. */
    private var charCount = 0L

    /**
     * Read the next character from the buffered stream.
     *
     * @throws IllegalStateException on unexpected end-of-input.
     */
    private fun nextChar(): Char {
        if (bufPos >= bufLen) refill()
        if (bufPos >= bufLen) parseError("Unexpected end of input")
        charCount++
        return buffer[bufPos++]
    }

    /**
     * Peek at the next character without consuming it.
     *
     * @throws IllegalStateException on unexpected end-of-input.
     */
    private fun peekChar(): Char {
        if (bufPos >= bufLen) refill()
        if (bufPos >= bufLen) parseError("Unexpected end of input")
        return buffer[bufPos]
    }

    /** Returns `true` when the underlying stream has more data (or the buffer is non-empty). */
    private fun hasMore(): Boolean {
        if (bufPos < bufLen) return true
        refill()
        return bufPos < bufLen
    }

    private fun refill() {
        val read = source.read(buffer, 0, buffer.size)
        if (read <= 0) {
            bufLen = 0
            return
        }
        bufPos = 0
        bufLen = read
    }

    // -- Whitespace & utilities --------------------------------------------------

    /** Skip JSON insignificant whitespace (space, tab, LF, CR). */
    private fun skipWhitespace() {
        while (hasMore()) {
            when (buffer[bufPos]) {
                ' ', '\t', '\n', '\r' -> {
                    bufPos++
                    charCount++
                }

                else -> return
            }
        }
    }

    /**
     * Consume the expected character [expected] from the stream.
     *
     * @throws IllegalStateException if the next non-whitespace character is not [expected].
     */
    private fun expect(expected: Char) {
        skipWhitespace()
        val actual = nextChar()
        if (actual != expected) {
            parseError("Expected '$expected' but found '$actual'")
        }
    }

    /**
     * Throw a descriptive parse error including the approximate character position.
     */
    private fun parseError(message: String): Nothing {
        throw IllegalStateException("$message (at character position ~$charCount)")
    }

    // -- Navigation: structural tokens -------------------------------------------

    /**
     * Expect and consume the opening `{` of a JSON object.
     *
     * @throws IllegalStateException if the next token is not `{`.
     */
    fun beginObject() {
        expect('{')
    }

    /**
     * Expect and consume the closing `}` of a JSON object.
     *
     * @throws IllegalStateException if the next token is not `}`.
     */
    fun endObject() {
        expect('}')
    }

    /**
     * Expect and consume the opening `[` of a JSON array.
     *
     * @throws IllegalStateException if the next token is not `[`.
     */
    fun beginArray() {
        expect('[')
    }

    /**
     * Expect and consume the closing `]` of a JSON array.
     *
     * @throws IllegalStateException if the next token is not `]`.
     */
    fun endArray() {
        expect(']')
    }

    /**
     * Check whether the current JSON array has another element.
     *
     * - Skips whitespace.
     * - If the next character is `]`, returns `false` (does **not** consume the bracket).
     * - If the next character is `,`, consumes it (and any trailing whitespace) and returns `true`.
     * - Otherwise returns `true` (first element — no comma needed).
     */
    fun hasNextInArray(): Boolean {
        skipWhitespace()
        if (!hasMore()) return false
        return when (peekChar()) {
            ']' -> false
            ',' -> {
                nextChar() // consume comma
                skipWhitespace()
                true
            }

            else -> true
        }
    }

    /**
     * Check whether the current JSON object has another key-value pair.
     *
     * - Skips whitespace.
     * - If the next character is `}`, returns `false` (does **not** consume the brace).
     * - If the next character is `,`, consumes it (and any trailing whitespace) and returns `true`.
     * - Otherwise returns `true` (first key — no comma needed).
     */
    fun hasNextInObject(): Boolean {
        skipWhitespace()
        if (!hasMore()) return false
        return when (peekChar()) {
            '}' -> false
            ',' -> {
                nextChar() // consume comma
                skipWhitespace()
                true
            }

            else -> true
        }
    }

    /**
     * Read the next object key name and consume the following colon.
     *
     * Expects a JSON string (`"key"`) followed by `:`.
     *
     * @return the key name without surrounding quotes.
     * @throws IllegalStateException if the stream is not positioned at a quoted string + colon.
     */
    fun nextName(): String {
        skipWhitespace()
        val name = readQuotedString()
        skipWhitespace()
        expect(':')
        return name
    }

    // -- Scalar reads ------------------------------------------------------------

    /**
     * Read the next JSON string value.
     *
     * @return the decoded string content (escape sequences resolved, no surrounding quotes).
     * @throws IllegalStateException on malformed string or unexpected end-of-input.
     */
    fun nextString(): String {
        skipWhitespace()
        return readQuotedString()
    }

    /**
     * Read the next JSON number value as [Int].
     *
     * @throws NumberFormatException if the token cannot be parsed as an integer.
     */
    fun nextInt(): Int {
        skipWhitespace()
        return readNumberToken().toInt()
    }

    /**
     * Read the next JSON number value as [Long].
     *
     * @throws NumberFormatException if the token cannot be parsed as a long.
     */
    fun nextLong(): Long {
        skipWhitespace()
        return readNumberToken().toLong()
    }

    /**
     * Read the next JSON boolean value (`true` or `false`).
     *
     * @throws IllegalStateException if the next token is not a boolean literal.
     */
    fun nextBoolean(): Boolean {
        skipWhitespace()
        return when (peekChar()) {
            't' -> {
                expectLiteral("true")
                true
            }

            'f' -> {
                expectLiteral("false")
                false
            }

            else -> parseError("Expected boolean but found '${peekChar()}'")
        }
    }

    // -- Compound reads ----------------------------------------------------------

    /**
     * Read the next complete JSON value as a raw JSON string.
     *
     * This is the **key method** for streaming import: it captures an entire JSON object,
     * array, string, number, boolean, or null as-is so the caller can pass it to
     * `kotlinx.serialization.Json.decodeFromString()`.
     *
     * Peak memory: one entity's JSON (typically 100 bytes -- 10 KB).
     *
     * @return the raw JSON text of the next value.
     * @throws IllegalStateException on malformed JSON or unexpected end-of-input.
     */
    fun nextValueAsString(): String {
        skipWhitespace()
        val sb = StringBuilder()
        readValueInto(sb)
        return sb.toString()
    }

    /**
     * Skip the next JSON value entirely without accumulating it.
     *
     * Slightly more efficient than [nextValueAsString] when the value is not needed —
     * avoids StringBuilder allocation for potentially large sub-trees.
     *
     * @throws IllegalStateException on malformed JSON or unexpected end-of-input.
     */
    fun skipValue() {
        skipWhitespace()
        readValueInto(sink = null)
    }

    // -- Null handling -----------------------------------------------------------

    /**
     * Peek whether the next value is a JSON `null` literal.
     *
     * Does **not** consume the value — call [skipNull] or [skipValue] afterward if it is null.
     *
     * @return `true` when the next non-whitespace character is `n` (start of `null`).
     */
    fun peekIsNull(): Boolean {
        skipWhitespace()
        return hasMore() && peekChar() == 'n'
    }

    /**
     * Consume a JSON `null` literal.
     *
     * @throws IllegalStateException if the next token is not `null`.
     */
    fun skipNull() {
        skipWhitespace()
        expectLiteral("null")
    }

    // -- Internal: string reading ------------------------------------------------

    /**
     * Read a JSON quoted string, resolving escape sequences.
     *
     * Expects the stream to be positioned at the opening `"`. Consumes through the closing `"`.
     *
     * Handles: `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX` (including
     * surrogate pairs for characters outside the BMP).
     */
    private fun readQuotedString(): String {
        val openQuote = nextChar()
        if (openQuote != '"') parseError("Expected '\"' but found '$openQuote'")

        val sb = StringBuilder()
        while (true) {
            val c = nextChar()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> sb.append(readEscapeSequence())
                else -> sb.append(c)
            }
        }
    }

    /**
     * Read the character(s) following a backslash in a JSON string.
     *
     * Called immediately after consuming `\`. Handles standard escapes and `\uXXXX`
     * (including surrogate pairs for supplementary Unicode code points).
     */
    private fun readEscapeSequence(): CharSequence {
        val escaped = nextChar()
        return when (escaped) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\b"
            'f' -> ""
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> readUnicodeEscape()
            else -> parseError("Invalid escape sequence: \\$escaped")
        }
    }

    /**
     * Read a `\uXXXX` escape (already consumed the `u`). Handles surrogate pairs:
     * if the first code unit is a high surrogate (D800-DBFF) and is immediately
     * followed by `\uXXXX` with a low surrogate (DC00-DFFF), both are combined
     * into a single supplementary code point.
     */
    private fun readUnicodeEscape(): CharSequence {
        val highUnit = parseHex4()
        if (highUnit in 0xD800..0xDBFF) {
            // Expect a low surrogate pair: \uXXXX
            if (hasMore() && peekChar() == '\\') {
                nextChar() // consume '\'
                if (hasMore() && peekChar() == 'u') {
                    nextChar() // consume 'u'
                    val lowUnit = parseHex4()
                    if (lowUnit in 0xDC00..0xDFFF) {
                        val codePoint = 0x10000 + ((highUnit - 0xD800) shl 10) + (lowUnit - 0xDC00)
                        return buildString {
                            // Supplementary code point → encode as surrogate pair chars
                            val hi = ((codePoint - 0x10000) shr 10) + 0xD800
                            val lo = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
                            append(hi.toChar())
                            append(lo.toChar())
                        }
                    }
                    // Low surrogate did not follow — emit both as-is
                    return buildString {
                        append(highUnit.toChar())
                        append(lowUnit.toChar())
                    }
                }
                // '\' was not followed by 'u' — emit high surrogate and put back the char
                // We already consumed '\', so emit it and let the high surrogate stand alone.
                return buildString {
                    append(highUnit.toChar())
                    append('\\')
                }
            }
        }
        return highUnit.toChar().toString()
    }

    /** Parse exactly 4 hex digits from the stream and return the integer value. */
    private fun parseHex4(): Int {
        var result = 0
        repeat(4) { i ->
            val c = nextChar()
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> 10 + (c - 'a')
                in 'A'..'F' -> 10 + (c - 'A')
                else -> parseError("Invalid hex digit '$c' in \\u escape (digit ${i + 1} of 4)")
            }
            result = (result shl 4) or digit
        }
        return result
    }

    // -- Internal: number reading ------------------------------------------------

    /**
     * Read a raw JSON number token from the stream.
     *
     * Accumulates characters that are valid within JSON numbers:
     * digits, `-`, `+`, `.`, `e`, `E`.
     *
     * The caller is responsible for converting the returned string to the target numeric type.
     */
    private fun readNumberToken(): String {
        val sb = StringBuilder()
        while (hasMore()) {
            val c = peekChar()
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                sb.append(nextChar())
            } else {
                break
            }
        }
        if (sb.isEmpty()) parseError("Expected a number but found '${if (hasMore()) peekChar() else "EOF"}'")
        return sb.toString()
    }

    // -- Internal: literal reading -----------------------------------------------

    /**
     * Consume the exact sequence of characters in [literal] from the stream.
     *
     * @throws IllegalStateException if the stream does not match.
     */
    private fun expectLiteral(literal: String) {
        for (i in literal.indices) {
            val c = nextChar()
            if (c != literal[i]) {
                parseError("Expected '${literal}' but diverged at character '$c' (index $i)")
            }
        }
    }

    // -- Internal: composite value reading ---------------------------------------

    /**
     * Read the next complete JSON value.
     *
     * If [sink] is non-null, characters are appended to it (for [nextValueAsString]).
     * If [sink] is null, characters are consumed and discarded (for [skipValue]).
     *
     * For objects and arrays, depth tracking ensures nested structures are fully consumed.
     * String literals within objects/arrays are tracked to avoid counting braces/brackets
     * that appear inside quoted strings.
     */
    private fun readValueInto(sink: StringBuilder?) {
        val firstChar = peekChar()
        when {
            firstChar == '{' || firstChar == '[' -> readCompoundValueInto(sink)
            firstChar == '"' -> readRawStringInto(sink)
            firstChar == '-' || firstChar in '0'..'9' -> readRawNumberInto(sink)
            firstChar == 't' -> readRawLiteralInto("true", sink)
            firstChar == 'f' -> readRawLiteralInto("false", sink)
            firstChar == 'n' -> readRawLiteralInto("null", sink)
            else -> parseError("Unexpected character '$firstChar' at start of value")
        }
    }

    /**
     * Read a compound value (object or array) including all nested content.
     *
     * Tracks depth via `{`/`[` (increment) and `}`/`]` (decrement). Properly handles
     * string literals so that structural characters inside strings do not affect depth.
     */
    private fun readCompoundValueInto(sink: StringBuilder?) {
        var depth = 0
        do {
            val c = nextChar()
            sink?.append(c)
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    // Read the rest of the string literal to avoid false brace/bracket matches
                    readRawStringBodyInto(sink)
                }
            }
        } while (depth > 0)
    }

    /**
     * Read a complete JSON string including surrounding quotes, appending to [sink].
     *
     * The opening `"` is consumed and appended, then [readRawStringBodyInto] handles
     * the body and closing `"`.
     */
    private fun readRawStringInto(sink: StringBuilder?) {
        val quote = nextChar() // opening "
        sink?.append(quote)
        readRawStringBodyInto(sink)
    }

    /**
     * Read the body of a JSON string (everything after the opening `"`) including
     * the closing `"`. Handles escape sequences to ensure a `\"` does not terminate
     * the string prematurely.
     *
     * Characters are appended verbatim (raw JSON) — escape sequences are NOT decoded
     * because the output is raw JSON for later deserialization.
     */
    private fun readRawStringBodyInto(sink: StringBuilder?) {
        while (true) {
            val c = nextChar()
            sink?.append(c)
            when (c) {
                '"' -> return // closing quote
                '\\' -> {
                    // Consume the escaped character so it cannot be mistaken for a closing quote
                    val escaped = nextChar()
                    sink?.append(escaped)
                    // For \uXXXX, consume the 4 hex digits too
                    if (escaped == 'u') {
                        repeat(4) {
                            val hex = nextChar()
                            sink?.append(hex)
                        }
                    }
                }
            }
        }
    }

    /**
     * Read a raw JSON number token and append it to [sink].
     */
    private fun readRawNumberInto(sink: StringBuilder?) {
        while (hasMore()) {
            val c = peekChar()
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                val consumed = nextChar()
                sink?.append(consumed)
            } else {
                break
            }
        }
    }

    /**
     * Read a JSON literal ([literal] = `"true"`, `"false"`, or `"null"`) character
     * by character, verifying correctness and optionally appending to [sink].
     */
    private fun readRawLiteralInto(literal: String, sink: StringBuilder?) {
        for (i in literal.indices) {
            val c = nextChar()
            if (c != literal[i]) {
                parseError("Expected '$literal' but diverged at character '$c' (index $i)")
            }
            sink?.append(c)
        }
    }

    companion object {
        /** Size of the internal read buffer in characters. */
        private const val BUFFER_SIZE = 8192
    }
}
