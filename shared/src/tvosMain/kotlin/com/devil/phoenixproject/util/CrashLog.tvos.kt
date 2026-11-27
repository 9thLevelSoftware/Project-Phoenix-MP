@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private fun legacyCrashLogPath(): String? {
    val paths = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
    val library = paths.firstOrNull() as? String ?: return null
    return "$library/${CrashLog.FILE_NAME}"
}

/**
 * tvOS has no share sheet or crash-report prompt UI, so no local crash file is retained.
 */
internal actual fun platformCrashLogStore(): CrashLogStore? = null

private var crashHookInstalled = false

/**
 * Idempotent tvOS diagnostics setup, called before Koin and startup logging:
 * - release binaries log at Warn (Debug in debug binaries), mirroring Android;
 * - removes any legacy `Library/crash-last.txt` left by older builds so no unconsumed
 *   crash file remains on disk;
 * - ensures uncaught Kotlin exceptions still print their stack trace before process exit.
 */
internal fun installTvosDiagnostics() {
    Logger.mutableConfig.minSeverity = if (DeviceInfo.isDebugBuild) Severity.Debug else Severity.Warn
    purgeLegacyCrashLog()
    if (crashHookInstalled) return
    crashHookInstalled = true
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { throwable ->
        val chained = previous
        if (chained != null) {
            chained(throwable)
        } else {
            try {
                throwable.printStackTrace()
            } catch (_: Throwable) {
            }
        }
    }
}

private fun purgeLegacyCrashLog() {
    val path = legacyCrashLogPath() ?: return
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(path)) {
        fileManager.removeItemAtPath(path, null)
    }
}

@Suppress("UNUSED_PARAMETER")
actual fun shareCrashReport(report: String, onShown: () -> Unit) {
    Logger.w { "Crash report sharing is not available on tvOS." }
}