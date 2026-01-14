package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable

/**
 * Android implementation of BackHandler - delegates to AndroidX Activity Compose.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
