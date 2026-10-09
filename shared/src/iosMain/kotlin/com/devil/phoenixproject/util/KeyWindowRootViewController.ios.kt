package com.devil.phoenixproject.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Key window of the first connected [UIWindowScene].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun keyWindow(): UIWindow? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    val windowScene = scenes.firstOrNull {
        it is UIWindowScene
    } as? UIWindowScene

    return windowScene?.keyWindow
}

/**
 * Root [UIViewController] of the key window on the first connected [UIWindowScene].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun keyWindowRootViewController(): UIViewController? {
    return keyWindow()?.rootViewController
}
