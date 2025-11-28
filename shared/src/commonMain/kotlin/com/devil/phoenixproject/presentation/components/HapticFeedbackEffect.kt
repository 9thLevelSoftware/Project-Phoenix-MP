package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Composable effect that provides haptic and audio feedback in response to workout events.
 *
 * Platform-specific implementation required for actual haptic feedback.
 * Different haptic patterns and tones are used for different events:
 * - REP_COMPLETED: Light click + short high beep
 * - WARMUP_COMPLETE: Long press + success tone
 * - WORKOUT_COMPLETE: Long press + success tone
 * - WORKOUT_START: Light click + medium beep
 * - WORKOUT_END: Light click + medium beep
 * - ERROR: Long press + error tone
 */
@Composable
expect fun HapticFeedbackEffect(
    hapticEvents: SharedFlow<HapticEvent>
)
