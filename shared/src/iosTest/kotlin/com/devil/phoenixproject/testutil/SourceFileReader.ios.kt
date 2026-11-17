@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.testutil

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS test implementation of [readProjectFile]. Resolves the project root from
 * the test bundle's own path (the Kotlin/Native test runner's CWD is not the
 * project root on the simulator, but it runs the test binary straight from the
 * project build tree), then walks up looking for either `shared/<path>` or
 * `<path>` (when a `.git` / `settings.gradle.kts` marker is hit) and returns
 * the file's text, or null if the file cannot be located.
 *
 * The Xcode test runner's working directory may differ between local runs and
 * CI; the bundle-anchored up-walk makes this robust without changing the
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

    // Build the candidate list: the test bundle's own path (the simulator runs
    // the test binary straight from the project build tree, so its
    // executable/bundle path anchors the project root), cwd, then parents.
    val candidates = mutableListOf<String>()
    val bundle = NSBundle.mainBundle
    val bundleRoots = listOf(
        bundle.bundlePath,
        bundle.executablePath?.substringBeforeLast('/'),
        bundle.resourcePath,
    ).filterNotNull()
    val roots: List<String> = bundleRoots + listOf(cwd)
    roots.forEach { root ->
        sourcePaths.forEach { candidates.add("$root/$it") }
        sourcePaths.forEach { candidates.add("$root/shared/$it") }
    }
    roots.forEach { start ->
        var dir: String? = start
        repeat(10) {
            if (dir == null) return@repeat
            val current: String = dir
            sourcePaths.forEach { candidates.add("$current/shared/$it") }
            if (fm.fileExistsAtPath("$current/.git") || fm.fileExistsAtPath("$current/settings.gradle.kts")) {
                sourcePaths.forEach { candidates.add("$current/$it") }
            }
            val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isEmpty() || parent == current) return@repeat
            dir = parent
        }
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
