package com.devil.phoenixproject.auth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthIosUrlSchemeConfigTest {

    @Test
    fun iosInfoPlistRegistersOAuthCallbackScheme() {
        val repoRoot = findRepoRoot()
        val infoPlist = File(
            repoRoot,
            "iosApp/VitruvianPhoenix/VitruvianPhoenix/Info.plist",
        )

        assertTrue("Expected iOS Info.plist to exist at ${infoPlist.path}", infoPlist.isFile)

        val content = infoPlist.readText()
        assertTrue(
            "Expected iOS Info.plist to declare CFBundleURLTypes for OAuth callbacks",
            content.contains("<key>CFBundleURLTypes</key>"),
        )
        assertTrue(
            "Expected iOS Info.plist to register the com.devil.phoenixproject URL scheme used by mobile OAuth",
            content.contains("<string>com.devil.phoenixproject</string>"),
        )
    }

    private fun findRepoRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is not available"
        }
        var current = File(workingDirectory).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) {
                return current
            }
            current = current.parentFile
                ?: error("Could not locate repo root from $workingDirectory")
        }
    }
}
