package com.devil.phoenixproject

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source guard (Codex 4083814541): startup now resolves on a background dispatcher, so while
 * `resolution == null` each host must draw [StartupPendingSurface] rather than nothing. The
 * hosts are Koin/Compose entry points that JVM tests can't compose, and iOS isn't compiled
 * here, so this checks both host sources directly.
 */
class AppHostStartupSurfaceGuardTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/iosMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val hosts = listOf(
        "shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt",
        "shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt",
    )

    @Test
    fun everyHostDrawsTheSplashWhileStartupIsUnresolved() {
        val pendingBranch = Regex("""null\s*->\s*(\S+)""")
        hosts.forEach { path ->
            val source = File(projectRoot, path).readText()
            val branch = pendingBranch.find(source)?.groupValues?.get(1)
            assertTrue(branch != null, "$path must handle the unresolved (null) startup state")
            assertTrue(
                branch.startsWith("StartupPendingSurface("),
                "$path must draw StartupPendingSurface() while startup resolves off the main thread, not '$branch'",
            )
            assertFalse(
                Regex("""null\s*->\s*Unit\b""").containsMatchIn(source),
                "$path must not render nothing while startup is unresolved",
            )
        }
    }

    @Test
    fun thePendingSurfaceIsTheSplash() {
        val app = File(projectRoot, "shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt").readText()
        val body = app.substringAfter("fun StartupPendingSurface()").take(200)
        assertTrue(body.contains("SplashScreen(visible = true)"), "StartupPendingSurface must show the splash")
    }
}
