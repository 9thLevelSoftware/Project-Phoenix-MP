package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable

/**
 * iOS implementation of BackHandler.
 *
 * iOS doesn't have a hardware back button like Android.
 * Back navigation is handled through:
 * - Swipe gestures (iOS navigation)
 * - UI back buttons
 *
 * This is effectively a no-op on iOS.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on iOS - back navigation is handled through gestures and UI buttons
}
