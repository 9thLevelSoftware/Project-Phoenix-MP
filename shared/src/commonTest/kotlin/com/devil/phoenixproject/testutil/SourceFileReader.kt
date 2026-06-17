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
