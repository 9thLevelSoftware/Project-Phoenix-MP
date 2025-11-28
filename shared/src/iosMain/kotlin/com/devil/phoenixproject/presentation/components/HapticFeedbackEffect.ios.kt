package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * iOS implementation of HapticFeedbackEffect using UIKit haptic generators.
 *
 * Uses UIImpactFeedbackGenerator for workout events and UINotificationFeedbackGenerator
 * for completion/error states. Different haptic patterns are applied based on event type.
 */
@Composable
actual fun HapticFeedbackEffect(hapticEvents: SharedFlow<HapticEvent>) {
    LaunchedEffect(hapticEvents) {
        hapticEvents.collectLatest { event ->
            when (event) {
                HapticEvent.REP_COMPLETED -> {
                    // Light impact for each rep
                    val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                    generator.prepare()
                    generator.impactOccurred()
                }
                HapticEvent.WARMUP_COMPLETE, HapticEvent.WORKOUT_COMPLETE -> {
                    // Success notification for completions
                    val generator = UINotificationFeedbackGenerator()
                    generator.prepare()
                    generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                }
                HapticEvent.WORKOUT_START, HapticEvent.WORKOUT_END -> {
                    // Medium impact for start/end
                    val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                    generator.prepare()
                    generator.impactOccurred()
                }
                HapticEvent.REST_ENDING -> {
                    // Warning notification when rest is ending
                    val generator = UINotificationFeedbackGenerator()
                    generator.prepare()
                    generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
                }
                HapticEvent.ERROR -> {
                    // Error notification
                    val generator = UINotificationFeedbackGenerator()
                    generator.prepare()
                    generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
                }
            }
        }
    }
}
