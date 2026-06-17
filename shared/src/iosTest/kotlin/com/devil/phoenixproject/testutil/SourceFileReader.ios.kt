@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.testutil

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS test implementation of [readProjectFile]. Walks up from the current
 * working directory looking for either `shared/<path>` or `<path>` (when a
 * `.git` / `settings.gradle.kts` marker is hit) and returns the file's text,
 * or null if the file cannot be located.
 *
 * The Xcode test runner's working directory may differ between local runs and
 * CI; the up-walk makes this robust without changing the test's public API.
 */
actual fun readProjectFile(relativePath: String): String? {
    val fm = NSFileManager.defaultManager
    val cwd = fm.currentDirectoryPath

    // Build the candidate list: cwd, then up to 6 parent directories.
    val candidates = mutableListOf<String>()
    candidates.add("$cwd/$relativePath")
    var dir: String? = cwd
    repeat(6) {
        if (dir == null) return@repeat
        candidates.add("$dir/shared/$relativePath")
        if (fm.fileExistsAtPath("$dir/.git") || fm.fileExistsAtPath("$dir/settings.gradle.kts")) {
            candidates.add("$dir/$relativePath")
        }
        val parent = dir.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty() || parent == dir) return@repeat
        dir = parent
    }

    for (candidate in candidates) {
        if (candidate.isEmpty()) continue
        if (!fm.fileExistsAtPath(candidate)) continue
        val nsstring = NSString.stringWithContentsOfFile(
            candidate,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (nsstring != null) {
            return nsstring
        }
    }
    return null
}
