package com.devil.phoenixproject.presentation.components

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.shared.R
import com.devil.phoenixproject.util.DeviceInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlin.random.Random

@Composable
actual fun HapticFeedbackEffect(hapticEvents: SharedFlow<HapticEvent>) {
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

    // Create SoundPool for audio feedback. USAGE_MEDIA keeps cues on STREAM_MUSIC,
    // matching Spotify mix behavior and foreground hardware volume buttons.
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                buildCueAudioAttributes(),
            )
            .build()
    }

    // Load sounds from static shared-module raw resource IDs so release shrinking
    // can see and keep every cue resource.
    val soundIds = remember(soundPool) {
        mutableMapOf<HapticEvent, Int>().apply {
            try {
                AndroidCueResources.eventCues.forEach { (event, cue) ->
                    loadSound(context, soundPool, cue)?.let { put(event, it) }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load sounds" }
            }
        }
    }

    // Load badge celebration sounds
    val badgeSoundIds = remember(soundPool) {
        AndroidCueResources.badgeCues.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Load PR-specific sounds
    val prSoundIds = remember(soundPool) {
        AndroidCueResources.prCues.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Load rep count sounds (1-25)
    val repCountSoundIds = remember(soundPool) {
        AndroidCueResources.repCountCues.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Load countdown tick sound (reuses beep for short tick)
    val countdownTickSoundId = remember(soundPool) {
        loadSound(context, soundPool, AndroidCueResources.countdownTickCue)
    }

    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { event ->
            playHapticFeedback(vibrator, event)
            playSound(event, soundPool, soundIds, badgeSoundIds, prSoundIds, repCountSoundIds, countdownTickSoundId, context)
        }
    }

    // Cleanup SoundPool when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            soundPool.release()
        }
    }
}

private fun loadSound(context: Context, soundPool: SoundPool, cue: AndroidCueResource): Int? {
    return try {
        soundPool.load(context, cue.rawResId, 1)
    } catch (e: Exception) {
        Logger.e(e) { "Failed to load sound '${cue.name}'" }
        null
    }
}

/**
 * Play sound based on event type using SoundPool, with MediaPlayer fallback for key sounds.
 * Fire OS: Always uses MediaPlayer (SoundPool has documented volume bug on Fire OS).
 */
private fun playSound(
    event: HapticEvent,
    soundPool: SoundPool,
    soundIds: Map<HapticEvent, Int>,
    badgeSoundIds: List<Int>,
    prSoundIds: List<Int>,
    repCountSoundIds: List<Int>,
    countdownTickSoundId: Int?,
    context: Context,
) {
    // ERROR event has no sound
    if (event is HapticEvent.ERROR) return

    // Fire OS: Always use MediaPlayer (SoundPool has volume bug)
    if (DeviceInfo.isFireOS()) {
        playWithMediaPlayer(event, context)
        return
    }

    val soundId = when (event) {
        is HapticEvent.BADGE_EARNED -> {
            if (badgeSoundIds.isNotEmpty()) {
                badgeSoundIds[Random.nextInt(badgeSoundIds.size)]
            } else {
                null
            }
        }

        is HapticEvent.PERSONAL_RECORD -> {
            if (prSoundIds.isNotEmpty()) {
                prSoundIds[Random.nextInt(prSoundIds.size)]
            } else {
                null
            }
        }

        is HapticEvent.REP_COUNT_ANNOUNCED -> {
            val index = event.repNumber - 1
            if (index in repCountSoundIds.indices) {
                repCountSoundIds[index]
            } else {
                null
            }
        }

        is HapticEvent.COUNTDOWN_TICK -> countdownTickSoundId

        else -> soundIds[event]
    }

    if (soundId == null) {
        // Try MediaPlayer fallback for key events
        playWithMediaPlayer(event, context)
        return
    }

    try {
        val streamId = soundPool.play(
            soundId,
            1.0f, // Left volume (full)
            1.0f, // Right volume (full)
            1, // Priority
            0, // Loop (0 = no loop)
            1.0f, // Playback rate
        )
        // If SoundPool fails, try MediaPlayer fallback
        if (streamId == 0) {
            playWithMediaPlayer(event, context)
        }
    } catch (_: Exception) {
        playWithMediaPlayer(event, context)
    }
}

/**
 * Fallback sound playback using MediaPlayer for when SoundPool fails or on Fire OS.
 * Uses explicit USAGE_MEDIA for all Android variants so fallback cues match
 * SoundPool routing and foreground hardware volume behavior.
 */
private fun playWithMediaPlayer(event: HapticEvent, context: Context) {
    val cue = AndroidCueResources.cueForEvent(event) ?: return

    var mediaPlayer: MediaPlayer? = null
    try {
        mediaPlayer = MediaPlayer.create(context, cue.rawResId, buildCueAudioAttributes(), 0) ?: return

        mediaPlayer.setVolume(1.0f, 1.0f)
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.setOnErrorListener { player, _, _ ->
            player.release()
            true
        }
        mediaPlayer.start()
    } catch (_: Exception) {
        mediaPlayer?.release()
        // Silently fail - sound is not critical
    }
}

private fun buildCueAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

internal data class AndroidCueResource(
    val name: String,
    val rawResId: Int,
)

internal object AndroidCueResources {
    private val beep = AndroidCueResource("beep", R.raw.beep)
    private val beepBoop = AndroidCueResource("beepboop", R.raw.beepboop)
    private val boopBeepBeep = AndroidCueResource("boopbeepbeep", R.raw.boopbeepbeep)
    private val chirpChirp = AndroidCueResource("chirpchirp", R.raw.chirpchirp)
    private val discoMode = AndroidCueResource("discomode", R.raw.discomode)
    private val restOver = AndroidCueResource("restover", R.raw.restover)

    val eventCues: Map<HapticEvent, AndroidCueResource> = mapOf(
        HapticEvent.REP_COMPLETED to chirpChirp,
        HapticEvent.FINAL_REP to boopBeepBeep,
        HapticEvent.WARMUP_COMPLETE to beepBoop,
        HapticEvent.WORKOUT_COMPLETE to boopBeepBeep,
        HapticEvent.WORKOUT_START to chirpChirp,
        HapticEvent.WORKOUT_END to chirpChirp,
        HapticEvent.REST_ENDING to restOver,
        HapticEvent.DISCO_MODE_UNLOCKED to discoMode,
        HapticEvent.WARMUP_TO_WORKING to beepBoop,
        HapticEvent.VELOCITY_THRESHOLD_REACHED to boopBeepBeep,
    )

    val badgeCues: List<AndroidCueResource> = listOf(
        AndroidCueResource("absolute_domination", R.raw.absolute_domination),
        AndroidCueResource("absolute_unit", R.raw.absolute_unit),
        AndroidCueResource("another_milestone_crushed", R.raw.another_milestone_crushed),
        AndroidCueResource("beast_mode", R.raw.beast_mode),
        AndroidCueResource("insane_performance", R.raw.insane_performance),
        AndroidCueResource("maxed_out", R.raw.maxed_out),
        AndroidCueResource("new_peak_achieved", R.raw.new_peak_achieved),
        AndroidCueResource("new_record_secured", R.raw.new_record_secured),
        AndroidCueResource("no_ones_stopping_you_now", R.raw.no_ones_stopping_you_now),
        AndroidCueResource("power", R.raw.power),
        AndroidCueResource("pr", R.raw.pr),
        AndroidCueResource("pressure_create_greatness", R.raw.pressure_create_greatness),
        AndroidCueResource("record", R.raw.record),
        AndroidCueResource("shattered", R.raw.shattered),
        AndroidCueResource("strenght_unlocked", R.raw.strenght_unlocked),
        AndroidCueResource("that_bar_never_stood_a_chance", R.raw.that_bar_never_stood_a_chance),
        AndroidCueResource("that_was_a_demolition", R.raw.that_was_a_demolition),
        AndroidCueResource("that_was_god_mode", R.raw.that_was_god_mode),
        AndroidCueResource("that_was_monster_level", R.raw.that_was_monster_level),
        AndroidCueResource("that_was_next_tier_strenght", R.raw.that_was_next_tier_strenght),
        AndroidCueResource("that_was_pure_savagery", R.raw.that_was_pure_savagery),
        AndroidCueResource("the_grind_continues", R.raw.the_grind_continues),
        AndroidCueResource("the_grind_is_real", R.raw.the_grind_is_real),
        AndroidCueResource("this_is_what_champions_are_made", R.raw.this_is_what_champions_are_made),
        AndroidCueResource("unchained_power", R.raw.unchained_power),
        AndroidCueResource("unstoppable", R.raw.unstoppable),
        AndroidCueResource("victory", R.raw.victory),
        AndroidCueResource("you_crushed_that", R.raw.you_crushed_that),
        AndroidCueResource("you_dominated_that_set", R.raw.you_dominated_that_set),
        AndroidCueResource("you_just_broke_your_limits", R.raw.you_just_broke_your_limits),
        AndroidCueResource("you_just_destroyed_that_weight", R.raw.you_just_destroyed_that_weight),
        AndroidCueResource("you_just_levelled_up", R.raw.you_just_levelled_up),
        AndroidCueResource("you_went_full_throttle", R.raw.you_went_full_throttle),
    )

    val prCues: List<AndroidCueResource> = listOf(
        AndroidCueResource("new_personal_record", R.raw.new_personal_record),
        AndroidCueResource("new_personal_record_2", R.raw.new_personal_record_2),
    )

    val repCountCues: List<AndroidCueResource> = listOf(
        AndroidCueResource("rep_01", R.raw.rep_01),
        AndroidCueResource("rep_02", R.raw.rep_02),
        AndroidCueResource("rep_03", R.raw.rep_03),
        AndroidCueResource("rep_04", R.raw.rep_04),
        AndroidCueResource("rep_05", R.raw.rep_05),
        AndroidCueResource("rep_06", R.raw.rep_06),
        AndroidCueResource("rep_07", R.raw.rep_07),
        AndroidCueResource("rep_08", R.raw.rep_08),
        AndroidCueResource("rep_09", R.raw.rep_09),
        AndroidCueResource("rep_10", R.raw.rep_10),
        AndroidCueResource("rep_11", R.raw.rep_11),
        AndroidCueResource("rep_12", R.raw.rep_12),
        AndroidCueResource("rep_13", R.raw.rep_13),
        AndroidCueResource("rep_14", R.raw.rep_14),
        AndroidCueResource("rep_15", R.raw.rep_15),
        AndroidCueResource("rep_16", R.raw.rep_16),
        AndroidCueResource("rep_17", R.raw.rep_17),
        AndroidCueResource("rep_18", R.raw.rep_18),
        AndroidCueResource("rep_19", R.raw.rep_19),
        AndroidCueResource("rep_20", R.raw.rep_20),
        AndroidCueResource("rep_21", R.raw.rep_21),
        AndroidCueResource("rep_22", R.raw.rep_22),
        AndroidCueResource("rep_23", R.raw.rep_23),
        AndroidCueResource("rep_24", R.raw.rep_24),
        AndroidCueResource("rep_25", R.raw.rep_25),
    )

    val countdownTickCue: AndroidCueResource = beep

    val allCues: List<AndroidCueResource> = (
        eventCues.values +
            badgeCues +
            prCues +
            repCountCues +
            countdownTickCue
        )
        .distinctBy { it.name }
        .sortedBy { it.name }

    fun cueForEvent(event: HapticEvent): AndroidCueResource? =
        when (event) {
            is HapticEvent.BADGE_EARNED -> badgeCues[Random.nextInt(badgeCues.size)]
            is HapticEvent.PERSONAL_RECORD -> prCues[Random.nextInt(prCues.size)]
            is HapticEvent.REP_COUNT_ANNOUNCED -> repCountCues.getOrNull(event.repNumber - 1)
            is HapticEvent.COUNTDOWN_TICK -> countdownTickCue
            is HapticEvent.ERROR -> null
            else -> eventCues[event]
        }
}

@SuppressLint("MissingPermission")
private fun playHapticFeedback(vibrator: Vibrator, event: HapticEvent) {
    // REP_COUNT_ANNOUNCED has no haptic feedback - it's audio only
    if (event is HapticEvent.REP_COUNT_ANNOUNCED) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Use VibrationEffect for better control
        val effect = when (event) {
            is HapticEvent.REP_COMPLETED -> {
                // Light, quick click for each rep
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            }

            is HapticEvent.FINAL_REP -> {
                // Stronger vibration for final rep — double pulse with escalating amplitude
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 120),
                    intArrayOf(0, 200, 0, 255),
                    -1,
                )
            }

            is HapticEvent.WARMUP_COMPLETE -> {
                // Double pulse - strong
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100),
                    intArrayOf(0, 200, 0, 200),
                    -1,
                )
            }

            is HapticEvent.WORKOUT_COMPLETE -> {
                // Triple pulse - celebration pattern
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 100, 80, 150),
                    intArrayOf(0, 150, 0, 200, 0, 255),
                    -1,
                )
            }

            is HapticEvent.WORKOUT_START -> {
                // Two quick pulses - attention getter
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80),
                    intArrayOf(0, 180, 0, 180),
                    -1,
                )
            }

            is HapticEvent.WORKOUT_END -> {
                // Same as start - symmetrical experience
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80),
                    intArrayOf(0, 180, 0, 180),
                    -1,
                )
            }

            is HapticEvent.REST_ENDING -> {
                // Warning pattern - gets attention
                VibrationEffect.createWaveform(
                    longArrayOf(0, 150, 100, 150, 100, 150),
                    intArrayOf(0, 100, 0, 150, 0, 200),
                    -1,
                )
            }

            is HapticEvent.ERROR -> {
                // Sharp error pulse
                VibrationEffect.createOneShot(200, 255)
            }

            is HapticEvent.DISCO_MODE_UNLOCKED -> {
                // Funky disco celebration pattern - rhythmic pulses
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 80, 60, 80, 60, 120, 80, 120),
                    intArrayOf(0, 180, 0, 200, 0, 220, 0, 255, 0, 255),
                    -1,
                )
            }

            is HapticEvent.BADGE_EARNED, is HapticEvent.PERSONAL_RECORD -> {
                // Celebration pattern - escalating pulses for achievement
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 60, 120, 60, 150),
                    intArrayOf(0, 180, 0, 220, 0, 255),
                    -1,
                )
            }

            is HapticEvent.VELOCITY_THRESHOLD_REACHED -> {
                // Strong alert — double heavy pulse for velocity threshold
                VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 80, 150),
                    intArrayOf(0, 255, 0, 255),
                    -1,
                )
            }

            is HapticEvent.COUNTDOWN_TICK -> {
                // Very light tick for rest countdown (last 10 seconds)
                VibrationEffect.createOneShot(30, 80)
            }

            is HapticEvent.WARMUP_TO_WORKING -> {
                // Ascending double pulse for warmup-to-working transition
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 120),
                    intArrayOf(0, 150, 0, 220),
                    -1,
                )
            }

            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // Already handled above, but needed for exhaustive when
                return
            }
        }
        vibrator.vibrate(effect)
    } else {
        // Fallback for older devices
        @Suppress("DEPRECATION")
        when (event) {
            is HapticEvent.REP_COMPLETED -> {
                vibrator.vibrate(50)
            }

            is HapticEvent.FINAL_REP -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 120), -1)
            }

            is HapticEvent.WARMUP_COMPLETE -> {
                vibrator.vibrate(longArrayOf(0, 100, 100, 100), -1)
            }

            is HapticEvent.WORKOUT_COMPLETE -> {
                vibrator.vibrate(longArrayOf(0, 100, 80, 100, 80, 150), -1)
            }

            is HapticEvent.WORKOUT_START, is HapticEvent.WORKOUT_END -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 80), -1)
            }

            is HapticEvent.REST_ENDING -> {
                vibrator.vibrate(longArrayOf(0, 150, 100, 150, 100, 150), -1)
            }

            is HapticEvent.ERROR -> {
                vibrator.vibrate(200)
            }

            is HapticEvent.DISCO_MODE_UNLOCKED -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 80, 60, 80, 60, 120, 80, 120), -1)
            }

            is HapticEvent.BADGE_EARNED, is HapticEvent.PERSONAL_RECORD -> {
                vibrator.vibrate(longArrayOf(0, 100, 60, 120, 60, 150), -1)
            }

            is HapticEvent.VELOCITY_THRESHOLD_REACHED -> {
                vibrator.vibrate(longArrayOf(0, 120, 80, 150), -1)
            }

            is HapticEvent.COUNTDOWN_TICK -> {
                vibrator.vibrate(30)
            }

            is HapticEvent.WARMUP_TO_WORKING -> {
                vibrator.vibrate(longArrayOf(0, 80, 60, 120), -1)
            }

            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // No haptic for rep count announcement - audio only
            }
        }
    }
}
