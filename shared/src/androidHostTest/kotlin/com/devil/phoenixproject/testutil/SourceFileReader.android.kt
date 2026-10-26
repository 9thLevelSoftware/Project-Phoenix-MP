package com.devil.phoenixproject.testutil

import java.io.File

/**
 * Android/JVM implementation of [readProjectFile]. Uses [java.io.File] which is
 * available on the JVM/Android test source set. The caller is expected to invoke
 * this from a working directory that is inside the project root (the test does
 * the up-walk in shared code via a different mechanism if needed).
 */
actual fun readProjectFile(relativePath: String): String? {
    val phoneSourcePath = relativePath.replaceFirst(
        "src/commonMain/kotlin/",
        "src/phoneMain/kotlin/",
    )
    val sourcePaths = if (phoneSourcePath == relativePath) {
        listOf(relativePath)
    } else {
        listOf(relativePath, phoneSourcePath)
    }
    for (path in sourcePaths) {
        val file = File(path)
        if (file.exists() && file.isFile) {
            return file.readText().replace("\r\n", "\n")
        }
    }
    // Walk up the directory tree looking for the project root.
    var dir: File? = File(".").absoluteFile
    repeat(6) {
        if (dir == null) return@repeat
        for (path in sourcePaths) {
            val candidate = File(dir, "shared/$path")
            if (candidate.exists() && candidate.isFile) {
                return candidate.readText().replace("\r\n", "\n")
            }
        }
        if (File(dir, ".git").exists() || File(dir, "settings.gradle.kts").exists()) {
            for (path in sourcePaths) {
                val rootCandidate = File(dir, path)
                if (rootCandidate.exists() && rootCandidate.isFile) {
                    return rootCandidate.readText().replace("\r\n", "\n")
                }
            }
        }
        dir = dir.parentFile
    }
    return null
}
