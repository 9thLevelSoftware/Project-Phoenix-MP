package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Desktop implementation of HapticFeedbackEffect - no-op as desktop doesn't support haptics
 *
 * Platform-specific implementation for desktop (JVM). Since desktop systems don't have
 * haptic feedback capabilities, this implementation does nothing. Future enhancements
 * could include audio feedback or visual cues.
 */
@Composable
actual fun HapticFeedbackEffect(hapticEvents: SharedFlow<HapticEvent>) {
    // No-op on desktop - haptic feedback is not available
    // Could potentially add audio feedback in the future
}
