package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume
import org.junit.Before

class IosXcodeFrameworkInstallTest {
    @Before
    fun requirePosixShell() {
        Assume.assumeTrue("/bin/sh not available on this host", File("/bin/sh").canExecute())
    }

    @Test
    fun xcodeProjectLinksOnlyTheNeutralFrameworkPath() {
        val project = requireNotNull(readProjectFile(PBXPROJ)) { "project.pbxproj not found" }

        assertEquals(
            1,
            Regex.escape(XCODE_FRAMEWORK_REF).toRegex().findAll(project).count(),
            "file reference must point at xcodeFramework/shared.framework",
        )
        assertEquals(
            2,
            Regex.escape(XCODE_SEARCH_PATH).toRegex().findAll(project).count(),
            "Debug and Release must share one FRAMEWORK_SEARCH_PATHS entry",
        )
        assertTrue(!project.contains("debugFramework"), "Xcode must not hardcode debugFramework")
        assertTrue(!project.contains("dSYM"), "static shared.framework has no dSYM phase")
        assertTrue(!project.contains("iphonesimulator"), "the app does not build the simulator framework")
        assertTrue(!project.contains("iosSimulatorArm64"), "Copy Compose Resources is device-only")
        assertTrue(project.contains("shared.framework in Frameworks"), "the link phase must keep shared.framework")
        assertTrue(project.contains("libsqlite3.tbd in Frameworks"), "the link phase must keep libsqlite3.tbd")
        assertTrue(!project.contains("Embed Frameworks"), "static shared.framework is linked, not copied into the app")
        assertTrue(!project.contains("PBXCopyFilesBuildPhase"), "there is no embed-frameworks copy phase")
        assertTrue(
            !project.contains("FRAMEWORKS_FOLDER_PATH"),
            "the privacy manifest is copied only to the app bundle root",
        )
    }

    @Test
    fun releaseWorkflowsInstallTheReleaseBinaryInsteadOfSymlinking() {
        WORKFLOWS.forEach { relativePath ->
            val workflow = requireNotNull(readProjectFile(relativePath)) { "$relativePath not found" }
            assertTrue(
                workflow.contains("iosApp/install-xcode-framework.sh release"),
                "$relativePath must install the release framework",
            )
            assertTrue(!workflow.contains("create-xcframework"), "$relativePath must not build an unused XCFramework")
            assertTrue(!workflow.contains("ln -sfn"), "$relativePath must not symlink release into debugFramework")
        }
    }

    @Test
    fun installCopiesDebugBinaryToTheXcodePath() {
        val fixture = Files.createTempDirectory("ios-xcode-framework")
        try {
            writeFramework(fixture, "debug", "debug-binary")

            val result = install(fixture, "debug")

            assertEquals(0, result.exitCode, result.output)
            assertEquals("debug-binary", installedBinary(fixture).readText())
            assertTrue(Files.isRegularFile(installedBinary(fixture)))
            assertTrue(!Files.isSymbolicLink(installedFramework(fixture)))
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun releaseInstallReplacesAPreviousDebugCopy() {
        val fixture = Files.createTempDirectory("ios-xcode-framework-replace")
        try {
            writeFramework(fixture, "debug", "debug-binary")
            writeFramework(fixture, "release", "release-binary")

            assertEquals(0, install(fixture, "debug").exitCode)
            val release = install(fixture, "release")

            assertEquals(0, release.exitCode, release.output)
            assertEquals("release-binary", installedBinary(fixture).readText())
            assertTrue(installedModuleMap(fixture).exists())
            assertTrue(installedInfoPlist(fixture).exists())
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun installDereferencesASymlinkedSourceBundle() {
        val fixture = Files.createTempDirectory("ios-xcode-framework-symlink")
        try {
            writeFramework(fixture, "release-real", "release-binary")
            val linkParent = fixture.resolve("shared/build/bin/iosArm64/releaseFramework")
            linkParent.createDirectories()
            Files.createSymbolicLink(
                linkParent.resolve("shared.framework"),
                fixture.resolve("shared/build/bin/iosArm64/release-realFramework/shared.framework"),
            )

            val result = install(fixture, "release")

            assertEquals(0, result.exitCode, result.output)
            assertEquals("release-binary", installedBinary(fixture).readText())
            assertTrue(!Files.isSymbolicLink(installedFramework(fixture)))
            assertTrue(!Files.isSymbolicLink(installedBinary(fixture)))
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun installFailsWhenTheFrameworkBinaryIsMissing() {
        val fixture = Files.createTempDirectory("ios-xcode-framework-missing")
        try {
            val result = install(fixture, "release")

            assertTrue(result.exitCode != 0, "missing framework must fail")
            assertTrue(result.output.contains("linkable shared.framework not found"))
            assertTrue(!installedFramework(fixture).exists())
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun writeFramework(root: Path, configuration: String, binary: String) {
        val framework = root.resolve("shared/build/bin/iosArm64/${configuration}Framework/shared.framework")
        framework.resolve("Modules").createDirectories()
        framework.resolve("shared").writeText(binary)
        framework.resolve("Modules/module.modulemap").writeText("framework module shared {}")
        framework.resolve("Info.plist").writeText("<?xml version=\"1.0\"?><plist></plist>")
    }

    private fun install(root: Path, configuration: String): ScriptResult {
        val script = repoFile("iosApp/install-xcode-framework.sh")
        val process = ProcessBuilder("/bin/sh", script.absolutePath, configuration)
            .redirectErrorStream(true)
        process.environment()["PHOENIX_ROOT"] = root.toString()
        val completed = process.start()
        return ScriptResult(
            exitCode = completed.waitFor(),
            output = completed.inputStream.bufferedReader().use { it.readText() },
        )
    }

    private fun installedFramework(root: Path): Path =
        root.resolve("shared/build/bin/iosArm64/xcodeFramework/shared.framework")

    private fun installedBinary(root: Path): Path = installedFramework(root).resolve("shared")

    private fun installedModuleMap(root: Path): Path =
        installedFramework(root).resolve("Modules/module.modulemap")

    private fun installedInfoPlist(root: Path): Path = installedFramework(root).resolve("Info.plist")

    private fun repoFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not find $relativePath from ${File(".").absolutePath}")
    }

    private data class ScriptResult(val exitCode: Int, val output: String)

    private fun Path.deleteRecursively() {
        if (!exists()) return
        toFile().walkBottomUp().forEach { it.delete() }
    }

    private companion object {
        const val PBXPROJ = "iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj"
        const val XCODE_FRAMEWORK_REF =
            "../../shared/build/bin/iosArm64/xcodeFramework/shared.framework"
        const val XCODE_SEARCH_PATH =
            "\$(SRCROOT)/../../shared/build/bin/iosArm64/xcodeFramework"
        val WORKFLOWS = listOf(
            ".github/workflows/ios-testflight.yml",
            ".github/workflows/ios-release-ipa.yml",
            ".github/workflows/ios-testflight-internal.yml",
        )
    }
}
