package com.devil.phoenixproject.ui.theme

import com.devil.phoenixproject.util.keyWindowRootViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.UIKit.UIUserInterfaceStyle
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal var currentIosThemeMode: ThemeMode = ThemeMode.SYSTEM
    private set

@OptIn(ExperimentalForeignApi::class)
actual fun applyPlatformNightMode(themeMode: ThemeMode) {
    currentIosThemeMode = themeMode
    applyRootUserInterfaceStyle(uiUserInterfaceStyleFor(themeMode))
}

internal fun uiUserInterfaceStyleFor(themeMode: ThemeMode): UIUserInterfaceStyle = when (themeMode) {
    ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
    ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
}

@OptIn(ExperimentalForeignApi::class)
internal fun applyRootUserInterfaceStyle(style: UIUserInterfaceStyle) {
    val apply = {
        val rootViewController = keyWindowRootViewController()
        if (rootViewController != null && rootViewController.overrideUserInterfaceStyle != style) {
            rootViewController.overrideUserInterfaceStyle = style
            rootViewController.setNeedsStatusBarAppearanceUpdate()
        }
        Unit
    }
    if (NSThread.isMainThread) {
        if (keyWindowRootViewController() != null) {
            apply()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                apply()
            }
        }
    } else {
        dispatch_async(dispatch_get_main_queue()) {
            apply()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun refreshRootStatusBarAppearance() {
    val refresh = {
        keyWindowRootViewController()?.setNeedsStatusBarAppearanceUpdate()
        Unit
    }
    if (NSThread.isMainThread) {
        if (keyWindowRootViewController() != null) {
            refresh()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                refresh()
            }
        }
    } else {
        dispatch_async(dispatch_get_main_queue()) {
            refresh()
        }
    }
}
