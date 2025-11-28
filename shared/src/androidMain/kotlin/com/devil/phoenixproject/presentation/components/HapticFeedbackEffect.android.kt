package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

@Composable
actual fun HapticFeedbackEffect(
    hapticEvents: SharedFlow<HapticEvent>
) {
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(hapticEvents) {
        hapticEvents.collectLatest { event ->
            // TODO: Implement proper patterns using Vibrator for "beeps" and specific patterns
            // For now just simple feedback to satisfy the interface
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
