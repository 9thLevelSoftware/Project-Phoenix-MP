package com.devil.phoenixproject

import androidx.compose.ui.window.ComposeUIViewController

/**
 * Creates the main UIViewController for iOS that hosts the Compose Multiplatform UI.
 * This is called from Swift via: MainViewControllerKt.MainViewController()
 */
fun MainViewController() = ComposeUIViewController {
    App()
}
