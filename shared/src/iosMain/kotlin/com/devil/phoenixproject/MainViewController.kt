package com.devil.phoenixproject

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import androidx.compose.ui.window.ComposeUIViewController
import com.devil.phoenixproject.presentation.components.RequireBlePermissions
import com.devil.phoenixproject.presentation.components.RequireOptionalPermissions
import platform.Foundation.NSLog
import kotlin.native.Platform as NativePlatform

/**
 * Creates the main UIViewController for iOS that hosts the Compose Multiplatform UI.
 * This is called from Swift via: MainViewControllerKt.MainViewController()
 */
fun MainViewController() = run {
    NSLog("iOS UI: MainViewController() called - creating ComposeUIViewController...")
    ComposeUIViewController {
        NSLog("iOS UI: ComposeUIViewController content block executing...")
        NSLog("iOS UI: Setting up image loader...")
        ensureImageLoader()
        NSLog("iOS UI: Image loader ready, loading App()...")
        RequireBlePermissions {
            NSLog("iOS UI: BLE permissions checked, checking optional permissions...")
            RequireOptionalPermissions {
                NSLog("iOS UI: All permissions handled, rendering App()...")
                App()
            }
        }
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
            .apply { @OptIn(kotlin.experimental.ExperimentalNativeApi::class) if (NativePlatform.isDebugBinary) logger(DebugLogger()) }
            .build()
    }
}
