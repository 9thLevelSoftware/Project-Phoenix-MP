@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.time.Clock
import platform.Foundation.*
import platform.UIKit.UIDevice

private class IosCrashLogStore(private val path: String) : CrashLogStore {
    private val fileManager = NSFileManager.defaultManager

    override fun read(): String? {
        if (!fileManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(
            path,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }

    override fun write(report: String) {
        NSString.create(string = report).writeToFile(
            path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }

    override fun delete() {
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, null)
        }
    }
}

private fun libraryCrashLogPath(): String? {
    val paths = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true)
    val library = paths.firstOrNull() as? String ?: return null
    return "$library/${CrashLog.FILE_NAME}"
}

internal actual fun platformCrashLogStore(): CrashLogStore? = libraryCrashLogPath()?.let { IosCrashLogStore(it) }

private var crashHookInstalled = false

/**
 * Idempotent tvOS diagnostics setup, called before Koin and startup logging:
 * - release binaries log at Warn (Debug in debug binaries), mirroring Android;
 * - an uncaught Kotlin exception's stack trace is written to Library/crash-last.txt.
 *   The runtime still terminates the process after the hook returns.
 */
internal fun installTvosDiagnostics() {
    Logger.mutableConfig.minSeverity = if (DeviceInfo.isDebugBuild) Severity.Debug else Severity.Warn
    if (crashHookInstalled) return
    crashHookInstalled = true
    val store = platformCrashLogStore() ?: return
    // Resolve on the calling (main) thread; the hook may run on any thread.
    val build = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
    val appVersion = if (build != null) "${DeviceInfo.appVersionName} ($build)" else DeviceInfo.appVersionName
    val platformName = "tvOS ${UIDevice.currentDevice.systemVersion}, ${UIDevice.currentDevice.model}"
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { throwable ->
        CrashLog.record(
            store = store,
            throwable = throwable,
            appVersion = appVersion,
            platform = platformName,
            timestampMillis = Clock.System.now().toEpochMilliseconds(),
        )
        val chained = previous
        if (chained != null) {
            chained(throwable)
        } else {
            // A set hook replaces the runtime's default stderr report; keep the trace in the console.
            try {
                throwable.printStackTrace()
            } catch (_: Throwable) {
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
actual fun shareCrashReport(report: String, onShown: () -> Unit) {
    Logger.w { "Crash report sharing is not available on tvOS." }
}