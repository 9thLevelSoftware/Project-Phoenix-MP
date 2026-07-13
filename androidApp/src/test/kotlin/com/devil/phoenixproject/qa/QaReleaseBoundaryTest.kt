package com.devil.phoenixproject.qa

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class QaReleaseBoundaryTest {
    private val forbiddenReleaseMarkers = listOf(
        "ProfileQa",
        "QA_SEED_PROFILE",
        "[QA] Profile",
        "QaBlockingPortalApiClient",
    )

    @Test
    fun `profile QA runtime files live in the debug source set`() {
        val repoRoot = findRepoRoot()
        val requiredDebugFiles = listOf(
            "androidApp/src/debug/AndroidManifest.xml",
            "androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaDebugApp.kt",
            "androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaFixtureGate.kt",
            "androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/QaBlockingPortalApiClient.kt",
        )

        val missing = requiredDebugFiles.filterNot { File(repoRoot, it).isFile }
        assertTrue(
            "Missing required debug-only QA files:\n${missing.joinToString("\n")}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `QA runtime sources are confined to debug while tests and docs remain allowed`() {
        val repoRoot = findRepoRoot()
        val sourceRoot = File(repoRoot, "androidApp/src")
        val leaks = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java", "xml") }
            .filter { file ->
                val normalizedPath = file.relativeTo(repoRoot).invariantSeparatorsPath
                !isAllowedQaPath(normalizedPath) &&
                    (forbiddenReleaseMarkers.any(file.readText()::contains) ||
                        file.name.contains("ProfileQa") ||
                        file.name.contains("QaBlockingPortalApiClient"))
            }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "QA runtime source escaped src/debug:\n${leaks.joinToString("\n")}",
            leaks.isEmpty(),
        )
    }

    @Test
    fun `release source and merged manifest inputs contain no QA markers`() {
        val repoRoot = findRepoRoot()
        val releaseInputs = buildList {
            add(File(repoRoot, "androidApp/src/main"))
            add(File(repoRoot, "androidApp/src/release"))

            listOf(
                File(repoRoot, "androidApp/build/intermediates/merged_manifest"),
                File(repoRoot, "androidApp/build/intermediates/merged_manifests"),
            ).filter(File::exists).forEach { mergedRoot ->
                addAll(
                    mergedRoot.walkTopDown()
                        .filter { file ->
                            file.isFile &&
                                file.name == "AndroidManifest.xml" &&
                                file.invariantSeparatorsPath.contains("release", ignoreCase = true)
                        }
                        .toList(),
                )
            }
        }.filter(File::exists)

        val leaks = releaseInputs.flatMap { input ->
            val files = if (input.isDirectory) input.walkTopDown().filter(File::isFile) else sequenceOf(input)
            files.mapNotNull { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                val markers = forbiddenReleaseMarkers.filter(content::contains)
                if (markers.isEmpty()) {
                    null
                } else {
                    "${file.relativeTo(repoRoot).invariantSeparatorsPath}: ${markers.joinToString()}"
                }
            }.toList()
        }

        assertTrue(
            "Release inputs contain debug-only QA markers:\n${leaks.joinToString("\n")}",
            leaks.isEmpty(),
        )
    }

    private fun isAllowedQaPath(path: String): Boolean =
        path.contains("/src/debug/") ||
            path.contains("/src/test/") ||
            path.contains("/src/testDebug/") ||
            path.contains("/src/androidTest/") ||
            path.contains("/docs/")

    private fun findRepoRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is not available"
        }
        var current = File(workingDirectory).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
                ?: error("Could not locate repo root from $workingDirectory")
        }
    }
}
