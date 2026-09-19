package com.devil.phoenixproject

import androidx.compose.ui.window.ComposeUIViewController
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.installIosCrashLog
import kotlin.native.Platform as NativePlatform
import platform.Foundation.NSLog

/**
 * Creates the main UIViewController for iOS that hosts the Compose Multiplatform UI.
 * This is called from Swift via: MainViewControllerKt.MainViewController()
 */
fun MainViewController() = run {
    installIosCrashLog()
    // Mirror Android release builds: suppress Verbose/Debug/Info logging outside debug binaries.
    Logger.mutableConfig.minSeverity = if (DeviceInfo.isDebugBuild) Severity.Debug else Severity.Warn
    NSLog("iOS UI: MainViewController() called - creating ComposeUIViewController...")
    ComposeUIViewController {
        NSLog("iOS UI: ComposeUIViewController content block executing...")
        NSLog("iOS UI: Setting up image loader...")
        ensureImageLoader()
        NSLog("iOS UI: Image loader ready, loading App()...")
        IosAppHost()
    }
}

/**
 * Mirrors the Android Application image loader setup so Coil uses Ktor and crossfade on iOS too.
 * setSafe prevents re-initialization if the controller is recreated.
 */
private fun ensureImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
            }
            .crossfade(true)
            // DebugLogger only in debug builds — iOS has no BuildConfig, use kotlin.native.Platform
            .apply {
                @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
                if (NativePlatform.isDebugBinary) logger(DebugLogger())
            }
            .build()
    }
}
