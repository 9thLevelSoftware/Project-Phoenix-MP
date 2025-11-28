package com.devil.phoenixproject.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.R
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Composable effect that handles haptic feedback and sound playback for workout events.
 * Uses Android SoundPool for efficient audio playback and Compose haptic feedback API.
 *
 * @param hapticEvents SharedFlow of HapticEvent emissions from the ViewModel
 */
@Composable
fun HapticFeedbackEffect(
    hapticEvents: SharedFlow<HapticEvent>
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    // Create and manage SoundPool
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }

    // Load sounds
    val soundIds = remember(soundPool) {
        mutableMapOf<HapticEvent, Int>().apply {
            try {
                put(HapticEvent.REP_COMPLETED, soundPool.load(context, R.raw.beep, 1))
                put(HapticEvent.WARMUP_COMPLETE, soundPool.load(context, R.raw.beepboop, 1))
                put(HapticEvent.WORKOUT_COMPLETE, soundPool.load(context, R.raw.boopbeepbeep, 1))
                put(HapticEvent.WORKOUT_START, soundPool.load(context, R.raw.chirpchirp, 1))
                put(HapticEvent.WORKOUT_END, soundPool.load(context, R.raw.chirpchirp, 1))
                put(HapticEvent.REST_ENDING, soundPool.load(context, R.raw.restover, 1))
                // ERROR has no sound
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load sounds" }
            }
        }
    }

    // Collect haptic events and play feedback
    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { event ->
            playHapticFeedback(event, hapticFeedback)
            playSound(event, soundPool, soundIds)
        }
    }

    // Cleanup SoundPool when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            soundPool.release()
        }
    }
}

/**
 * Play haptic feedback based on event type
 */
private fun playHapticFeedback(event: HapticEvent, hapticFeedback: HapticFeedback) {
    val feedbackType = when (event) {
        HapticEvent.REP_COMPLETED,
        HapticEvent.WORKOUT_START,
        HapticEvent.WORKOUT_END -> HapticFeedbackType.TextHandleMove // Light click

        HapticEvent.WARMUP_COMPLETE,
        HapticEvent.WORKOUT_COMPLETE,
        HapticEvent.REST_ENDING,
        HapticEvent.ERROR -> HapticFeedbackType.LongPress // Strong vibration
    }

    try {
        hapticFeedback.performHapticFeedback(feedbackType)
    } catch (e: Exception) {
        Logger.w { "Haptic feedback failed: ${e.message}" }
    }
}

/**
 * Play sound based on event type
 */
private fun playSound(
    event: HapticEvent,
    soundPool: SoundPool,
    soundIds: Map<HapticEvent, Int>
) {
    // ERROR event has no sound
    if (event == HapticEvent.ERROR) return

    val soundId = soundIds[event] ?: return

    try {
        soundPool.play(
            soundId,
            0.8f, // Left volume
            0.8f, // Right volume
            1,    // Priority
            0,    // Loop (0 = no loop)
            1.0f  // Playback rate
        )
    } catch (e: Exception) {
        Logger.w { "Sound playback failed: ${e.message}" }
    }
}
