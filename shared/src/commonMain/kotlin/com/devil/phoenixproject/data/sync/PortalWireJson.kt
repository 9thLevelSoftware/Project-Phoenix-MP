package com.devil.phoenixproject.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val PortalWireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal data class RawJsonSpan(val start: Int, val endExclusive: Int)

internal data class EncodedPortalSyncPayload(
    val raw: String,
    val rawBytes: ByteArray,
    val preferenceElementSpans: List<RawJsonSpan>,
) {
    fun preferenceElementByteCount(span: RawJsonSpan): Int =
        raw.substring(span.start, span.endExclusive).encodeToByteArray().size
}

internal fun encodePortalSyncPayload(payload: PortalSyncPayload): EncodedPortalSyncPayload {
    val raw = PortalWireJson.encodeToString(PortalSyncPayload.serializer(), payload)
    val spans = scanProfilePreferenceElementSpans(raw)
    check(spans.size == payload.profilePreferenceSections.orEmpty().size) {
        "Serialized profile preference span count mismatch"
    }
    return EncodedPortalSyncPayload(raw, raw.encodeToByteArray(), spans)
}

internal fun scanProfilePreferenceElementSpans(raw: String): List<RawJsonSpan> =
    PortalSyncPayloadRawScanner(raw).scan()

private class PortalSyncPayloadRawScanner(
    private val raw: String,
) {
    private var index = 0

    fun scan(): List<RawJsonSpan> {
        skipWhitespace()
        if (!consume('{')) fail(INVALID_JSON_ROOT)

        val spans = mutableListOf<RawJsonSpan>()
        var foundPreferenceSections = false
        skipWhitespace()
        if (!consume('}')) {
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail(INVALID_JSON_STRUCTURE)
                val propertyName = parseString()
                skipWhitespace()
                if (!consume(':')) fail(INVALID_JSON_STRUCTURE)
                skipWhitespace()

                if (propertyName == PROFILE_PREFERENCE_SECTIONS) {
                    if (foundPreferenceSections) {
                        fail(DUPLICATE_PROFILE_PREFERENCE_SECTIONS)
                    }
                    foundPreferenceSections = true
                    if (peek() != '[') fail(INVALID_PROFILE_PREFERENCE_ARRAY)
                    spans += parsePreferenceArray()
                } else {
                    parseValue()
                }

                skipWhitespace()
                when {
                    consume(',') -> {
                        skipWhitespace()
                        if (peek() == '}') fail(INVALID_JSON_STRUCTURE)
                    }
                    consume('}') -> break
                    else -> fail(INVALID_JSON_STRUCTURE)
                }
            }
        }

        skipWhitespace()
        if (index != raw.length) fail(TRAILING_JSON_DATA)
        return spans
    }

    private fun parsePreferenceArray(): List<RawJsonSpan> {
        if (!consume('[')) fail(INVALID_PROFILE_PREFERENCE_ARRAY)
        val spans = mutableListOf<RawJsonSpan>()
        skipWhitespace()
        if (consume(']')) return spans

        while (true) {
            skipWhitespace()
            val start = index
            parseValue()
            spans += RawJsonSpan(start = start, endExclusive = index)
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == ']') fail(INVALID_JSON_STRUCTURE)
                }
                consume(']') -> return spans
                else -> fail(INVALID_JSON_STRUCTURE)
            }
        }
    }

    private fun parseValue() {
        skipWhitespace()
        when (peek()) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> parseArray()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail(INVALID_JSON_STRUCTURE)
        }
    }

    private fun parseObject() {
        if (!consume('{')) fail(INVALID_JSON_STRUCTURE)
        skipWhitespace()
        if (consume('}')) return

        while (true) {
            skipWhitespace()
            if (peek() != '"') fail(INVALID_JSON_STRUCTURE)
            parseString()
            skipWhitespace()
            if (!consume(':')) fail(INVALID_JSON_STRUCTURE)
            parseValue()
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == '}') fail(INVALID_JSON_STRUCTURE)
                }
                consume('}') -> return
                else -> fail(INVALID_JSON_STRUCTURE)
            }
        }
    }

    private fun parseArray() {
        if (!consume('[')) fail(INVALID_JSON_STRUCTURE)
        skipWhitespace()
        if (consume(']')) return

        while (true) {
            parseValue()
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == ']') fail(INVALID_JSON_STRUCTURE)
                }
                consume(']') -> return
                else -> fail(INVALID_JSON_STRUCTURE)
            }
        }
    }

    private fun parseString(): String {
        if (!consume('"')) fail(INVALID_JSON_STRUCTURE)
        val decoded = StringBuilder()
        while (index < raw.length) {
            val character = raw[index++]
            when {
                character == '"' -> return decoded.toString()
                character == '\\' -> decoded.append(parseEscape())
                character.code < 0x20 -> fail(INVALID_JSON_STRUCTURE)
                else -> decoded.append(character)
            }
        }
        fail(INVALID_JSON_STRUCTURE)
    }

    private fun parseEscape(): Char {
        if (index >= raw.length) fail(INVALID_JSON_STRUCTURE)
        return when (val escaped = raw[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail(INVALID_JSON_STRUCTURE)
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > raw.length) fail(INVALID_JSON_STRUCTURE)
        var value = 0
        repeat(4) {
            value = value * 16 + hexValue(raw[index++])
        }
        return value.toChar()
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> fail(INVALID_JSON_STRUCTURE)
    }

    private fun parseLiteral(literal: String) {
        if (index + literal.length > raw.length ||
            raw.substring(index, index + literal.length) != literal
        ) {
            fail(INVALID_JSON_STRUCTURE)
        }
        index += literal.length
    }

    private fun parseNumber() {
        consume('-')
        when (peek()) {
            '0' -> {
                index++
                if (peek() in '0'..'9') fail(INVALID_JSON_STRUCTURE)
            }
            in '1'..'9' -> {
                index++
                while (peek() in '0'..'9') index++
            }
            else -> fail(INVALID_JSON_STRUCTURE)
        }

        if (consume('.')) {
            if (peek() !in '0'..'9') fail(INVALID_JSON_STRUCTURE)
            while (peek() in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') fail(INVALID_JSON_STRUCTURE)
            while (peek() in '0'..'9') index++
        }
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
            index++
        }
    }

    private fun consume(character: Char): Boolean = if (peek() == character) {
        index++
        true
    } else {
        false
    }

    private fun peek(): Char? = raw.getOrNull(index)

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private companion object {
        const val PROFILE_PREFERENCE_SECTIONS = "profilePreferenceSections"
        const val INVALID_JSON_ROOT = "INVALID_JSON_ROOT"
        const val INVALID_JSON_STRUCTURE = "INVALID_JSON_STRUCTURE"
        const val INVALID_PROFILE_PREFERENCE_ARRAY = "INVALID_PROFILE_PREFERENCE_ARRAY"
        const val DUPLICATE_PROFILE_PREFERENCE_SECTIONS =
            "DUPLICATE_PROFILE_PREFERENCE_SECTIONS"
        const val TRAILING_JSON_DATA = "TRAILING_JSON_DATA"
    }
}
