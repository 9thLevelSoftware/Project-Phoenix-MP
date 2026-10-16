package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable

/**
 * Platform-agnostic handler for system back.
 *
 * Platform-specific implementations:
 * - Android: Delegates to androidx.activity.compose.BackHandler, which invokes [onBack]
 *   while [enabled] is true.
 * - iOS: No-op. The actual ignores [enabled] and [onBack]. It does not register a back
 *   gesture or any other callback, and it never invokes [onBack].
 *
 * @param enabled Whether the handler is enabled. Default is true. Ignored on iOS.
 * @param onBack Invoked for system back on Android. Never invoked by the iOS actual.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
