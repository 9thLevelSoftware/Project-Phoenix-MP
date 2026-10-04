package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS bookmark Base64 conversion and the two callers' checks.
 * The helper itself is Kotlin/Native and does not run on the host.
 */
class IosBookmarkBase64ContractTest {

    @Test
    fun sharedHelperConvertsWithFoundationOptionsZero() {
        val helper = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BookmarkBase64.ios.kt")
        val encode = region(helper, "internal fun encodeBookmarkBase64", "internal fun decodeBookmarkBase64")
        val decode = helper.substring(helper.indexOf("internal fun decodeBookmarkBase64"))

        assertTrue(encode.contains("base64EncodedStringWithOptions(0u)"), "encode uses options 0")
        assertTrue(decode.contains("base64EncodedString = base64"), "decode reads the stored string")
        assertTrue(decode.contains("options = 0u"), "decode uses options 0")
        assertTrue(!helper.contains("catch"), "the helper does not absorb caller error styles")
        assertTrue(!helper.contains("isNullOrEmpty"), "empty encoded bookmarks stay a resolver check")
        assertTrue(!helper.contains("isNullOrBlank"), "a missing stored bookmark stays a resolver check")
        assertTrue(!helper.contains("startAccessingSecurityScopedResource"), "security scope stays at the call sites")
        assertTrue(!helper.contains("log."), "logging stays at the call sites")
    }

    @Test
    fun pickerEncodesThroughTheSharedHelper() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt")
        val body = region(source, "private fun createBookmarkedDestination", "private fun presentDirectoryPicker")

        assertTrue(body.contains("encodeBookmarkBase64(it)"), "picker encodes through the shared helper")
        assertTrue(body.contains("NSURLBookmarkCreationWithSecurityScope"), "picker still creates a security-scoped bookmark")
        assertTrue(
            body.contains("Failed to create security-scoped bookmark — destination would be inaccessible on next launch"),
            "picker still logs when the bookmark cannot be stored",
        )
        assertTrue(body.contains("if (base64Bookmark == null)"), "picker still rejects a null encoding")
        assertTrue(body.contains("catch (e: Exception)"), "picker still catches bookmark creation failures")
        assertTrue(body.contains("return null"), "picker still returns null on failure")
        assertTrue(!source.contains("base64EncodedStringWithOptions"), "Base64 encoding stays in the shared helper")
    }

    @Test
    fun resolverDecodesAndEncodesThroughTheSharedHelper() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt")
        val resolve = region(source, "private suspend fun resolveBookmark", "private suspend fun refreshStaleBookmark")
        val refresh = region(source, "private suspend fun refreshStaleBookmark", "override suspend fun isAccessible")

        val blankAt = resolve.indexOf("base64.isNullOrBlank()")
        val decodeAt = resolve.indexOf("decodeBookmarkBase64(base64)")
        assertTrue(blankAt >= 0 && decodeAt > blankAt, "a missing bookmark returns before decode")
        assertTrue(resolve.contains("Failed to decode Base64 bookmark"), "resolver still logs a corrupt bookmark")
        assertTrue(
            resolve.contains("NSURLBookmarkResolutionWithoutUI or NSURLBookmarkResolutionWithSecurityScope"),
            "resolver still resolves with a security scope and without UI",
        )

        assertTrue(refresh.contains("encodeBookmarkBase64(bookmarkData)"), "stale refresh encodes through the shared helper")
        assertTrue(refresh.contains("encoded.isNullOrEmpty()"), "resolver still rejects an empty encoding")
        assertTrue(refresh.contains("base64 == destination.bookmarkData"), "resolver still keeps an unchanged bookmark")
        assertTrue(refresh.contains("NSURLBookmarkCreationWithSecurityScope"), "stale refresh still recreates a security-scoped bookmark")
        assertTrue(refresh.contains("startAccessingSecurityScopedResource()"), "stale refresh still holds security-scoped access")
        assertTrue(!source.contains("base64EncodedStringWithOptions"), "Base64 encoding stays in the shared helper")
        assertTrue(!source.contains("base64EncodedString ="), "Base64 decoding stays in the shared helper")
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
}
