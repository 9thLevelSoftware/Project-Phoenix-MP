@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.testutil

import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS test implementation of [readProjectFile]. Resolves the project root from
 * (in order) the `PWD`, `SRCROOT` and `PROJECT_DIR` environment variables (the
 * Kotlin/Native test runner's CWD is not the project root on the simulator), then
 * walks up from the current working directory looking for either `shared/<path>`
 * or `<path>` (when a `.git` / `settings.gradle.kts` marker is hit) and returns
 * the file's text, or null if the file cannot be located.
 *
 * The Xcode test runner's working directory may differ between local runs and
 * CI; the env candidates plus the up-walk make this robust without changing the
 * test's public API. Merge audit item 5: the previous CWD-only up-walk made
 * source-contract tests fail on iosSimulatorArm64Test runs.
 */
actual fun readProjectFile(relativePath: String): String? {
    val fm = NSFileManager.defaultManager
    val cwd = fm.currentDirectoryPath
    val phoneSourcePath = relativePath.replaceFirst(
        "src/commonMain/kotlin/",
        "src/phoneMain/kotlin/",
    )
    val sourcePaths = if (phoneSourcePath == relativePath) {
        listOf(relativePath)
    } else {
        listOf(relativePath, phoneSourcePath)
    }

    // Build the candidate list: env-provided roots, cwd, then up to 6 parents.
    val candidates = mutableListOf<String>()
    val envRoots = listOf(
        NSProcessInfo.processInfo.environment["PWD"],
        NSProcessInfo.processInfo.environment["SRCROOT"],
        NSProcessInfo.processInfo.environment["PROJECT_DIR"],
    ).filterNotNull()
    (envRoots + listOf(cwd)).forEach { root ->
        sourcePaths.forEach { candidates.add("$root/$it") }
        sourcePaths.forEach { candidates.add("$root/shared/$it") }
    }
    var dir: String? = cwd
    repeat(6) {
        if (dir == null) return@repeat
        sourcePaths.forEach { candidates.add("$dir/shared/$it") }
        if (fm.fileExistsAtPath("$dir/.git") || fm.fileExistsAtPath("$dir/settings.gradle.kts")) {
            sourcePaths.forEach { candidates.add("$dir/$it") }
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
