package com.devil.phoenixproject.presentation.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

@Composable
actual fun HapticFeedbackEffect(
    hapticEvents: SharedFlow<HapticEvent>
) {
    val context = LocalContext.current

    // Get vibrator service
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    LaunchedEffect(hapticEvents) {
        hapticEvents.collectLatest { event ->
            playHapticFeedback(vibrator, event)
        }
    }
}

@SuppressLint("MissingPermission")
private fun playHapticFeedback(
    vibrator: Vibrator,
    event: HapticEvent
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Use VibrationEffect for better control
        val effect = when (event) {
            HapticEvent.REP_COMPLETED -> {
                // Light, quick click for each rep
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            HapticEvent.WARMUP_COMPLETE -> {
                // Double pulse - strong
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100), // timings: delay, on, off, on
                    intArrayOf(0, 200, 0, 200),    // amplitudes
                    -1 // don't repeat
                )
            }
            HapticEvent.WORKOUT_COMPLETE -> {
                // Triple pulse - celebration pattern
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 100, 80, 150), // timings
                    intArrayOf(0, 150, 0, 200, 0, 255),    // amplitudes (escalating)
                    -1
                )
            }
            HapticEvent.WORKOUT_START -> {
                // Two quick pulses - attention getter
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80),
                    intArrayOf(0, 180, 0, 180),
                    -1
                )
            }
            HapticEvent.WORKOUT_END -> {
                // Same as start - symmetrical experience
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80),
                    intArrayOf(0, 180, 0, 180),
                    -1
                )
            }
            HapticEvent.REST_ENDING -> {
                // Warning pattern - gets attention
                VibrationEffect.createWaveform(
                    longArrayOf(0, 150, 100, 150, 100, 150),
                    intArrayOf(0, 100, 0, 150, 0, 200),
                    -1
                )
            }
            HapticEvent.ERROR -> {
                // Sharp error pulse
                VibrationEffect.createOneShot(200, 255)
            }
            HapticEvent.DISCO_MODE_UNLOCKED -> {
                // Funky disco celebration pattern - rhythmic pulses
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80, 60, 80, 60, 120, 80, 120),
                    intArrayOf(0, 180, 0, 200, 0, 220, 0, 255, 0, 255),
                    -1
                )
            }
            HapticEvent.BADGE_EARNED, HapticEvent.PERSONAL_RECORD -> {
                // Celebration pattern - escalating pulses for achievement
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 60, 120, 60, 150),
                    intArrayOf(0, 180, 0, 220, 0, 255),
                    -1
                )
            }
        }
        vibrator.vibrate(effect)
    } else {
        // Fallback for older devices
        @Suppress("DEPRECATION")
        when (event) {
            HapticEvent.REP_COMPLETED -> {
                vibrator.vibrate(50)
            }
            HapticEvent.WARMUP_COMPLETE -> {
                vibrator.vibrate(longArrayOf(0, 100, 100, 100), -1)
            }
            HapticEvent.WORKOUT_COMPLETE -> {
                vibrator.vibrate(longArrayOf(0, 100, 80, 100, 80, 150), -1)
            }
            HapticEvent.WORKOUT_START, HapticEvent.WORKOUT_END -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 80), -1)
            }
            HapticEvent.REST_ENDING -> {
                vibrator.vibrate(longArrayOf(0, 150, 100, 150, 100, 150), -1)
            }
            HapticEvent.ERROR -> {
                vibrator.vibrate(200)
            }
            HapticEvent.DISCO_MODE_UNLOCKED -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 80, 60, 80, 60, 120, 80, 120), -1)
            }
            HapticEvent.BADGE_EARNED, HapticEvent.PERSONAL_RECORD -> {
                vibrator.vibrate(longArrayOf(0, 100, 60, 120, 60, 150), -1)
            }
        }
    }
}
