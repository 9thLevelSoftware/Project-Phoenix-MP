package com.devil.phoenixproject.util

/**
 * Platform-specific utility for reading file content from a URI or file path string.
 *
 * - Android: handles `content://` URIs via ContentResolver
 * - iOS: handles temp file paths returned by UIDocumentPickerViewController
 *
 * Returns null on failure (file not found, permission denied, etc.).
 */
expect suspend fun readUriContent(uriOrPath: String): String?

/** Result of [readUriContentUpTo]. */
sealed interface BoundedUriContent {
    data class Read(val content: String) : BoundedUriContent

    /** The document is larger than the limit; its content was not kept. */
    data object TooLarge : BoundedUriContent

    data object Unreadable : BoundedUriContent
}

/**
 * Like [readUriContent], but never holds more than [maxBytes] of the document: a larger one is
 * reported as [BoundedUriContent.TooLarge] instead of being read into memory.
 */
expect suspend fun readUriContentUpTo(uriOrPath: String, maxBytes: Int): BoundedUriContent
