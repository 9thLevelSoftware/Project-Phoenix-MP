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

class IosComposeResourceContractTest {
    /** The staging script runs under /bin/sh; skip on hosts without it (Windows). CI runs it on Linux. */
    @Before
    fun requirePosixShell() {
        Assume.assumeTrue("/bin/sh not available on this host", File("/bin/sh").canExecute())
    }

    @Test
    fun xcodeStagesResourcesForSimulatorAndDeviceTargets() {
        val shellScript = resourceStagingScript()

        assertStagingCopiesNestedResources(shellScript, sdkName = "iphonesimulator")
        assertStagingCopiesNestedResources(shellScript, sdkName = "iphoneos")
    }

    @Test
    fun xcodeResourceStagingFailsWhenProcessedResourcesAreMissing() {
        val shellScript = resourceStagingScript()
        val fixture = Files.createTempDirectory("ios-compose-resources-missing")
        try {
            val result = runScript(
                shellScript = shellScript,
                sdkName = "iphonesimulator",
                fixtureRoot = fixture,
            )

            assertTrue(result.exitCode != 0, "missing processed resources must fail the build")
            assertTrue(
                result.output.contains("Processed Compose resources not found"),
                "failure should identify the missing processed resources",
            )
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun assertStagingCopiesNestedResources(shellScript: String, sdkName: String) {
        val fixture = Files.createTempDirectory("ios-compose-resources")
        try {
            val target = if (sdkName == "iphonesimulator") "iosSimulatorArm64" else "iosArm64"
            val source = fixture.resolve("shared/build/processedResources/$target/main/composeResources")
            source.resolve("nested/strings.xml").also {
                it.parent.createDirectories()
                it.writeText("fixture-$target")
            }

            val result = runScript(shellScript, sdkName, fixture)

            assertEquals(0, result.exitCode, result.output)
            val destination = fixture.resolve(
                "build/Products/Phoenix.app/compose-resources/composeResources/nested/strings.xml",
            )
            assertTrue(destination.exists(), "staging should copy nested resource content")
            assertEquals("fixture-$target", destination.readText())
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun resourceStagingScript(): String {
        val project = requireNotNull(
            readProjectFile("../iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj")
                ?: readProjectFile("iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj"),
        )
        return Regex(
            """shellScript = \"(.*?)\";""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(project)
            ?.groupValues
            ?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: fail("Copy Compose Resources shell script not found")
    }

    private fun runScript(shellScript: String, sdkName: String, fixtureRoot: Path): ScriptResult {
        val srcRoot = fixtureRoot.resolve("iosApp/PhoenixApp").also { it.createDirectories() }
        val process = ProcessBuilder("/bin/sh", "-c", shellScript)
            .directory(srcRoot.toFile())
            .redirectErrorStream(true)
        process.environment().apply {
            put("SDK_NAME", sdkName)
            put("SRCROOT", srcRoot.toString())
            put("TARGET_BUILD_DIR", fixtureRoot.resolve("build/Products").toString())
            put("UNLOCALIZED_RESOURCES_FOLDER_PATH", "Phoenix.app")
        }
        val completed = process.start()
        return ScriptResult(
            exitCode = completed.waitFor(),
            output = completed.inputStream.bufferedReader().use { it.readText() },
        )
    }

    private data class ScriptResult(val exitCode: Int, val output: String)

    private fun Path.deleteRecursively() {
        if (!exists()) return
        toFile().walkBottomUp().forEach { it.delete() }
    }
}
