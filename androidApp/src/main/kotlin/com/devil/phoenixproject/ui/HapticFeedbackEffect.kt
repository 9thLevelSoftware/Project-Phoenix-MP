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
import kotlin.random.Random

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
    // Uses USAGE_ASSISTANCE_SONIFICATION to mix with music without interrupting it
    // This ensures workout sounds play alongside user's music playback
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
                put(HapticEvent.DISCO_MODE_UNLOCKED, soundPool.load(context, R.raw.discomode, 1))
                // BADGE_EARNED and PERSONAL_RECORD use random sounds from lists below
                // ERROR has no sound
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load sounds" }
            }
        }
    }

    // Load badge celebration sounds (excludes PR-specific sounds)
    val badgeSoundIds = remember(soundPool) {
        mutableListOf<Int>().apply {
            try {
                add(soundPool.load(context, R.raw.absolute_domination, 1))
                add(soundPool.load(context, R.raw.absolute_unit, 1))
                add(soundPool.load(context, R.raw.another_milestone_crushed, 1))
                add(soundPool.load(context, R.raw.beast_mode, 1))
                add(soundPool.load(context, R.raw.insane_performance, 1))
                add(soundPool.load(context, R.raw.maxed_out, 1))
                add(soundPool.load(context, R.raw.new_peak_achieved, 1))
                add(soundPool.load(context, R.raw.new_record_secured, 1))
                add(soundPool.load(context, R.raw.no_ones_stopping_you_now, 1))
                add(soundPool.load(context, R.raw.power, 1))
                add(soundPool.load(context, R.raw.pr, 1))
                add(soundPool.load(context, R.raw.pressure_create_greatness, 1))
                add(soundPool.load(context, R.raw.record, 1))
                add(soundPool.load(context, R.raw.shattered, 1))
                add(soundPool.load(context, R.raw.strenght_unlocked, 1))
                add(soundPool.load(context, R.raw.that_bar_never_stood_a_chance, 1))
                add(soundPool.load(context, R.raw.that_was_a_demolition, 1))
                add(soundPool.load(context, R.raw.that_was_god_mode, 1))
                add(soundPool.load(context, R.raw.that_was_monster_level, 1))
                add(soundPool.load(context, R.raw.that_was_next_tier_strenght, 1))
                add(soundPool.load(context, R.raw.that_was_pure_savagery, 1))
                add(soundPool.load(context, R.raw.the_grind_continues, 1))
                add(soundPool.load(context, R.raw.the_grind_is_real, 1))
                add(soundPool.load(context, R.raw.this_is_what_champions_are_made, 1))
                add(soundPool.load(context, R.raw.unchained_power, 1))
                add(soundPool.load(context, R.raw.unstoppable, 1))
                add(soundPool.load(context, R.raw.victory, 1))
                add(soundPool.load(context, R.raw.you_crushed_that, 1))
                add(soundPool.load(context, R.raw.you_dominated_that_set, 1))
                add(soundPool.load(context, R.raw.you_just_broke_your_limits, 1))
                add(soundPool.load(context, R.raw.you_just_destroyed_that_weight, 1))
                add(soundPool.load(context, R.raw.you_just_levelled_up, 1))
                add(soundPool.load(context, R.raw.you_went_full_throttle, 1))
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load badge sounds" }
            }
        }
    }

    // Load PR-specific sounds
    val prSoundIds = remember(soundPool) {
        mutableListOf<Int>().apply {
            try {
                add(soundPool.load(context, R.raw.new_personal_record, 1))
                add(soundPool.load(context, R.raw.new_personal_record_2, 1))
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load PR sounds" }
            }
        }
    }

    // Collect haptic events and play feedback
    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { event ->
            playHapticFeedback(event, hapticFeedback)
            playSound(event, soundPool, soundIds, badgeSoundIds, prSoundIds)
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
        HapticEvent.ERROR,
        HapticEvent.DISCO_MODE_UNLOCKED,
        HapticEvent.BADGE_EARNED,
        HapticEvent.PERSONAL_RECORD -> HapticFeedbackType.LongPress // Strong vibration
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
    soundIds: Map<HapticEvent, Int>,
    badgeSoundIds: List<Int>,
    prSoundIds: List<Int>
) {
    // ERROR event has no sound
    if (event == HapticEvent.ERROR) return

    val soundId = when (event) {
        HapticEvent.BADGE_EARNED -> {
            if (badgeSoundIds.isNotEmpty()) {
                badgeSoundIds[Random.nextInt(badgeSoundIds.size)]
            } else null
        }
        HapticEvent.PERSONAL_RECORD -> {
            if (prSoundIds.isNotEmpty()) {
                prSoundIds[Random.nextInt(prSoundIds.size)]
            } else null
        }
        else -> soundIds[event]
    } ?: return

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
