package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable

/**
 * iOS actual for [BackHandler].
 *
 * The body is empty. [enabled] and [onBack] are not read: this composable does not
 * install an edge-swipe (or any other) back gesture, does not connect to UIKit
 * navigation, and does not call [onBack].
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Empty on purpose. enabled and onBack are unused; nothing is registered or invoked.
}
