@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.devil.phoenixproject.util

import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.time.Clock
import platform.Foundation.*
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private class IosCrashLogStore(private val path: String) : CrashLogStore {
    private val fileManager = NSFileManager.defaultManager

    override fun read(): String? {
        if (!fileManager.fileExistsAtPath(path)) return null
        @Suppress("UNCHECKED_CAST")
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as? String
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
 * Writes the Kotlin stack trace of an uncaught exception to Library/crash-last.txt.
 * The runtime still terminates the process after the hook returns.
 */
fun installIosCrashLog() {
    if (crashHookInstalled) return
    crashHookInstalled = true
    val store = platformCrashLogStore() ?: return
    // Resolve on the calling (main) thread; the hook may run on any thread.
    val appVersion = DeviceInfo.appVersionName
    val platformName = "iOS ${UIDevice.currentDevice.systemVersion}, ${UIDevice.currentDevice.model}"
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { throwable ->
        CrashLog.record(
            store = store,
            throwable = throwable,
            appVersion = appVersion,
            platform = platformName,
            timestampMillis = Clock.System.now().toEpochMilliseconds(),
        )
        previous?.invoke(throwable)
    }
}

actual fun shareCrashReport(report: String) {
    // Same presentation as IosCsvExporter.shareCSV, sharing the report as text.
    dispatch_async(dispatch_get_main_queue()) {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.firstOrNull {
            it is platform.UIKit.UIWindowScene
        } as? platform.UIKit.UIWindowScene

        val rootViewController = windowScene?.keyWindow?.rootViewController
            ?: return@dispatch_async

        val activityVC = UIActivityViewController(
            activityItems = listOf(report),
            applicationActivities = null,
        )

        // iPad requires a popover anchor or presenting crashes.
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            activityVC.valueForKey("popoverPresentationController")?.let { popover ->
                (popover as? NSObject)?.setValue(rootViewController.view, forKey = "sourceView")
            }
        }

        rootViewController.presentViewController(
            activityVC,
            animated = true,
            completion = null,
        )
    }
}
