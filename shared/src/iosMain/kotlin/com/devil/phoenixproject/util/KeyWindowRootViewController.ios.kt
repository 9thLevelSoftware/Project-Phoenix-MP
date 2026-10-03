package com.devil.phoenixproject.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene

/**
 * Root [UIViewController] of the key window on the first connected [UIWindowScene].
 * Document pickers present from this controller.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun keyWindowRootViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    val windowScene = scenes.firstOrNull {
        it is UIWindowScene
    } as? UIWindowScene

    return windowScene?.keyWindow?.rootViewController
}
