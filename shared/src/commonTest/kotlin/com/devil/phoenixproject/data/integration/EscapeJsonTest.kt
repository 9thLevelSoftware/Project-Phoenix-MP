package com.devil.phoenixproject.data.integration

import kotlin.test.Test
import kotlin.test.assertEquals

class EscapeJsonTest {
    @Test
    fun escapesJsonStringDelimitersAndWhitespaceControls() {
        assertEquals("plain", "plain".escapeJson())
        assertEquals("a\\\\b", "a\\b".escapeJson())
        assertEquals("say \\\"hi\\\"", "say \"hi\"".escapeJson())
        assertEquals("line\\nbreak", "line\nbreak".escapeJson())
        assertEquals("cr\\r", "cr\r".escapeJson())
        assertEquals("tab\\there", "tab\there".escapeJson())
        assertEquals("café/\u0001", "café/\u0001".escapeJson())
    }
}
