@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.tvnative

import com.devil.phoenixproject.util.CrashLog
import com.devil.phoenixproject.util.installTvosDiagnostics
import com.devil.phoenixproject.util.platformCrashLogStore
import com.devil.phoenixproject.util.shareCrashReport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

class TvCrashLogLifecycleTest {

    @Test
    fun tvosLeavesNoUnconsumedCrashArtifactsAndPurgesLegacyFiles() {
        val paths = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
        val library = paths.firstOrNull() as? String
        val legacyPath = library?.let { "$it/${CrashLog.FILE_NAME}" }
        val fileManager = NSFileManager.defaultManager

        if (legacyPath != null) {
            NSString.create(string = "stale crash report").writeToFile(
                legacyPath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
        }

        installTvosDiagnostics()

        assertNull(platformCrashLogStore(), "tvOS must not retain a crash-file store")
        assertNull(CrashLog.pending(), "tvOS must not surface or leave pending crash files")
        if (legacyPath != null) {
            assertFalse(
                fileManager.fileExistsAtPath(legacyPath),
                "Legacy unconsumed crash-last.txt must be cleaned up during diagnostics setup",
            )
        }

        var shown = false
        shareCrashReport("report") { shown = true }
        assertFalse(shown, "tvOS shareCrashReport must not falsely acknowledge presentation")
    }
}
