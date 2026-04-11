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
