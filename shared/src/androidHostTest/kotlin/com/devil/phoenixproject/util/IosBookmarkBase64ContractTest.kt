package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS bookmark Base64 conversion: Foundation options 0 in
 * one place, and both callers going through it. The helper is Kotlin/Native
 * and does not run on the host.
 */
class IosBookmarkBase64ContractTest {

    @Test
    fun sharedHelperConvertsWithFoundationOptionsZero() {
        val helper = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BookmarkBase64.ios.kt")
        val encodeAt = helper.indexOf("internal fun encodeBookmarkBase64")
        val decodeAt = helper.indexOf("internal fun decodeBookmarkBase64")
        assertTrue(encodeAt >= 0 && decodeAt > encodeAt, "helper defines encode then decode")

        assertTrue(helper.substring(encodeAt, decodeAt).contains("base64EncodedStringWithOptions(0u)"), "encode uses options 0")
        assertTrue(helper.substring(decodeAt).contains("options = 0u"), "decode uses options 0")
    }

    @Test
    fun callersConvertOnlyThroughTheSharedHelper() {
        val picker = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt")
        val resolver = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt")

        assertTrue(picker.contains("encodeBookmarkBase64("), "picker encodes through the shared helper")
        assertTrue(resolver.contains("decodeBookmarkBase64("), "resolver decodes through the shared helper")
        assertTrue(resolver.contains("encodeBookmarkBase64("), "stale refresh encodes through the shared helper")
        for ((name, source) in listOf("picker" to picker, "resolver" to resolver)) {
            assertTrue(!source.contains("base64EncodedStringWithOptions"), "$name has no inline Base64 encode")
            assertTrue(!source.contains("base64EncodedString ="), "$name has no inline Base64 decode")
        }
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")
}
