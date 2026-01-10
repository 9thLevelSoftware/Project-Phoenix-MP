package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable

/**
 * Platform-agnostic back handler for intercepting system back button presses.
 *
 * Platform-specific implementations:
 * - Android: Delegates to androidx.activity.compose.BackHandler
 * - iOS: No-op (iOS uses gesture/UI navigation, no system back button)
 *
 * @param enabled Whether the handler is enabled. Default is true.
 * @param onBack Callback invoked when the back button is pressed.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
