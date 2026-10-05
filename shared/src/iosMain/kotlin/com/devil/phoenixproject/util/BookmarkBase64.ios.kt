package com.devil.phoenixproject.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

/**
 * Converts security-scoped bookmark bytes to the Base64 string stored on
 * [BackupDestination.Custom.bookmarkData], and back.
 *
 * Both directions use Foundation options 0. Callers keep their own null,
 * empty, and logging checks.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun encodeBookmarkBase64(bookmarkData: NSData): String? =
    bookmarkData.base64EncodedStringWithOptions(0u)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun decodeBookmarkBase64(base64: String): NSData? =
    NSData.create(
        base64EncodedString = base64,
        options = 0u,
    )
