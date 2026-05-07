package com.devil.phoenixproject.presentation.components

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
import com.devil.phoenixproject.util.DeviceInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlin.random.Random

// Both standard Android and Fire OS classify workout cues as USAGE_MEDIA so
// playback is bound to STREAM_MUSIC. This matches MainActivity's
// volumeControlStream = STREAM_MUSIC contract: hardware volume buttons control
// the same stream the cues are played on, and cues mix with background music
// without interrupting it.
private val STANDARD_ANDROID_CUE_USAGE = AudioAttributes.USAGE_MEDIA
private val FIRE_OS_CUE_USAGE = AudioAttributes.USAGE_MEDIA

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

    // Track which sounds are loaded and ready to play
    val loadedSounds = remember { mutableSetOf<Int>() }

    // Create SoundPool for audio feedback. USAGE_MEDIA routes to STREAM_MUSIC
    // so hardware volume keys (forced to STREAM_MUSIC by MainActivity) actually
    // control cue loudness, and cues stay audible through DND while mixing with
    // background music.
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(buildCueAudioAttributes(STANDARD_ANDROID_CUE_USAGE))
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        loadedSounds.add(sampleId)
                    }
                }
            }
    }

    // Load sounds using resource identifiers (works across modules)
    val soundIds = remember(soundPool) {
        mutableMapOf<HapticEvent, Int>().apply {
            try {
                // Issue #100: chirpchirp is louder/more audible than beep for rep completion
                loadSoundByName(context, soundPool, "chirpchirp")?.let { put(HapticEvent.REP_COMPLETED, it) }
                // Issue #100: Distinct boopbeepbeep sound on final working rep
                loadSoundByName(context, soundPool, "boopbeepbeep")?.let { put(HapticEvent.FINAL_REP, it) }
                loadSoundByName(context, soundPool, "beepboop")?.let { put(HapticEvent.WARMUP_COMPLETE, it) }
                loadSoundByName(context, soundPool, "boopbeepbeep")?.let { put(HapticEvent.WORKOUT_COMPLETE, it) }
                loadSoundByName(context, soundPool, "chirpchirp")?.let { put(HapticEvent.WORKOUT_START, it) }
                loadSoundByName(context, soundPool, "chirpchirp")?.let { put(HapticEvent.WORKOUT_END, it) }
                loadSoundByName(context, soundPool, "restover")?.let { put(HapticEvent.REST_ENDING, it) }
                loadSoundByName(context, soundPool, "discomode")?.let { put(HapticEvent.DISCO_MODE_UNLOCKED, it) }
                // Issue #100: Warmup-to-working transition (ascending tone)
                loadSoundByName(context, soundPool, "beepboop")?.let { put(HapticEvent.WARMUP_TO_WORKING, it) }
                // Issue #313: Velocity loss threshold alert (attention-getting)
                loadSoundByName(context, soundPool, "boopbeepbeep")?.let { put(HapticEvent.VELOCITY_THRESHOLD_REACHED, it) }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load sounds" }
            }
        }
    }

    // Load badge celebration sounds
    val badgeSoundIds = remember(soundPool) {
        BADGE_SOUND_NAMES.mapNotNull { loadSoundByName(context, soundPool, it) }
    }

    // Load PR-specific sounds
    val prSoundIds = remember(soundPool) {
        PR_SOUND_NAMES.mapNotNull { loadSoundByName(context, soundPool, it) }
    }

    // Load rep count sounds (1-25)
    val repCountSoundIds = remember(soundPool) {
        (1..25).mapNotNull { num ->
            loadSoundByName(context, soundPool, "rep_%02d".format(num))
        }
    }

    // Issue #100: Load countdown tick sound (reuses beep for short tick)
    val countdownTickSoundId = remember(soundPool) {
        loadSoundByName(context, soundPool, "beep")
    }

    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { event ->
            playHapticFeedback(vibrator, event)
            playSound(event, soundPool, soundIds, badgeSoundIds, prSoundIds, repCountSoundIds, countdownTickSoundId, loadedSounds, context)
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
 * Load a sound by name using resource identifier lookup.
 * This works across modules since we're using the application context.
 * Note: Uses base package name (without .debug suffix) for resource lookup.
 */
private fun loadSoundByName(context: Context, soundPool: SoundPool, name: String): Int? {
    // For debug builds, package name might be "com.devil.phoenixproject.debug"
    // but resources are registered under "com.devil.phoenixproject"
    val packageName = context.packageName.removeSuffix(".debug")
    var resId = context.resources.getIdentifier(name, "raw", packageName)

    // Fallback to actual package name if base didn't work
    if (resId == 0) {
        resId = context.resources.getIdentifier(name, "raw", context.packageName)
    }

    if (resId == 0) {
        Logger.w { "Sound resource not found: '$name' (tried packages: $packageName, ${context.packageName})" }
        return null
    }

    return try {
        soundPool.load(context, resId, 1)
    } catch (e: Exception) {
        Logger.e(e) { "Failed to load sound '$name' (resId=$resId)" }
        null
    }
}

/**
 * Issue #409: Check media volume and log warning if muted.
 * Cues use USAGE_MEDIA which routes to STREAM_MUSIC; if media volume is 0, all
 * sounds are inaudible. MainActivity forces hardware volume buttons to
 * STREAM_MUSIC so users can adjust this directly.
 */
private fun warnIfMediaVolumeMuted(context: Context) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Logger.w {
                "Issue #409: Media volume is 0/$maxVolume — workout sounds will be inaudible. " +
                    "Cues use USAGE_MEDIA (STREAM_MUSIC). Raise media volume to hear audio cues."
            }
        }
    } catch (_: Exception) {
        // Non-critical diagnostic — don't crash
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
    loadedSounds: Set<Int>,
    context: Context,
) {
    // ERROR event has no sound
    if (event is HapticEvent.ERROR) return

    // Issue #409: Log warning if media volume is muted
    warnIfMediaVolumeMuted(context)

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
        Logger.d { "No SoundPool ID for event $event — falling back to MediaPlayer" }
        playWithMediaPlayer(event, context)
        return
    }

    // Issue #409: Check if sound has finished async loading before attempting playback
    if (soundId !in loadedSounds) {
        Logger.d { "Sound $soundId not yet loaded for $event — using MediaPlayer fallback" }
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
            Logger.d { "SoundPool.play returned 0 for $event (soundId=$soundId) — MediaPlayer fallback" }
            playWithMediaPlayer(event, context)
        }
    } catch (e: Exception) {
        Logger.w(e) { "SoundPool.play threw for $event — MediaPlayer fallback" }
        playWithMediaPlayer(event, context)
    }
}

/**
 * Fallback sound playback using MediaPlayer for when SoundPool fails or on Fire OS.
 * Both standard Android and Fire OS use USAGE_MEDIA so cues route to STREAM_MUSIC,
 * matching MainActivity's volumeControlStream contract.
 */
private fun playWithMediaPlayer(event: HapticEvent, context: Context) {
    val soundName = when (event) {
        is HapticEvent.REP_COMPLETED -> "chirpchirp" // Issue #100: More audible than beep
        is HapticEvent.FINAL_REP -> "boopbeepbeep" // Issue #100: Distinct final rep sound
        is HapticEvent.WARMUP_COMPLETE -> "beepboop"
        is HapticEvent.WORKOUT_COMPLETE -> "boopbeepbeep"
        is HapticEvent.WORKOUT_START -> "chirpchirp"
        is HapticEvent.WORKOUT_END -> "chirpchirp"
        is HapticEvent.REST_ENDING -> "restover"
        is HapticEvent.DISCO_MODE_UNLOCKED -> "discomode"
        is HapticEvent.BADGE_EARNED -> getRandomBadgeSound()
        is HapticEvent.PERSONAL_RECORD -> getRandomPRSound()
        is HapticEvent.REP_COUNT_ANNOUNCED -> "rep_%02d".format(event.repNumber)
        is HapticEvent.COUNTDOWN_TICK -> "beep"
        is HapticEvent.WARMUP_TO_WORKING -> "beepboop"
        is HapticEvent.VELOCITY_THRESHOLD_REACHED -> "boopbeepbeep"
        is HapticEvent.ERROR -> return
    }

    val packageName = context.packageName.removeSuffix(".debug")
    var resId = context.resources.getIdentifier(soundName, "raw", packageName)

    // Fallback to actual package name if base didn't work
    if (resId == 0) {
        resId = context.resources.getIdentifier(soundName, "raw", context.packageName)
    }
    if (resId == 0) return

    var mediaPlayer: MediaPlayer? = null
    try {
        mediaPlayer = MediaPlayer.create(context, resId, buildMediaPlayerCueAudioAttributes(), 0) ?: return
        mediaPlayer.setVolume(1.0f, 1.0f)
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.start()
    } catch (_: Exception) {
        mediaPlayer?.release()
    }
}

private fun buildMediaPlayerCueAudioAttributes(): AudioAttributes {
    val usage = if (DeviceInfo.isFireOS()) FIRE_OS_CUE_USAGE else STANDARD_ANDROID_CUE_USAGE
    return buildCueAudioAttributes(usage)
}

private fun buildCueAudioAttributes(usage: Int): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

private val BADGE_SOUND_NAMES = listOf(
    "absolute_domination", "absolute_unit", "another_milestone_crushed",
    "beast_mode", "insane_performance", "maxed_out", "new_peak_achieved",
    "new_record_secured", "no_ones_stopping_you_now", "power", "pr",
    "pressure_create_greatness", "record", "shattered", "strenght_unlocked",
    "that_bar_never_stood_a_chance", "that_was_a_demolition", "that_was_god_mode",
    "that_was_monster_level", "that_was_next_tier_strenght", "that_was_pure_savagery",
    "the_grind_continues", "the_grind_is_real", "this_is_what_champions_are_made",
    "unchained_power", "unstoppable", "victory", "you_crushed_that",
    "you_dominated_that_set", "you_just_broke_your_limits", "you_just_destroyed_that_weight",
    "you_just_levelled_up", "you_went_full_throttle",
)

private val PR_SOUND_NAMES = listOf("new_personal_record", "new_personal_record_2")

/**
 * Get a random badge celebration sound name.
 */
private fun getRandomBadgeSound(): String =
    BADGE_SOUND_NAMES[Random.nextInt(BADGE_SOUND_NAMES.size)]

/**
 * Get a random PR celebration sound name.
 */
private fun getRandomPRSound(): String =
    PR_SOUND_NAMES[Random.nextInt(PR_SOUND_NAMES.size)]

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
                // Issue #100: Stronger vibration for final rep — double pulse with escalating amplitude
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 120),
                    intArrayOf(0, 200, 0, 255),
                    -1,
                )
            }

            is HapticEvent.WARMUP_COMPLETE -> {
                // Double pulse - strong
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100), // timings: delay, on, off, on
                    intArrayOf(0, 200, 0, 200), // amplitudes
                    -1, // don't repeat
                )
            }

            is HapticEvent.WORKOUT_COMPLETE -> {
                // Triple pulse - celebration pattern
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 100, 80, 150), // timings
                    intArrayOf(0, 150, 0, 200, 0, 255), // amplitudes (escalating)
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
                // Issue #313: Strong alert — double heavy pulse for velocity threshold
                VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 80, 150),
                    intArrayOf(0, 255, 0, 255),
                    -1,
                )
            }

            is HapticEvent.COUNTDOWN_TICK -> {
                // Issue #100: Very light tick for rest countdown (last 10 seconds)
                VibrationEffect.createOneShot(30, 80)
            }

            is HapticEvent.WARMUP_TO_WORKING -> {
                // Issue #100: Ascending double pulse for warmup-to-working transition
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
                // Issue #100: Stronger double pulse for final rep
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
                // Issue #100: Light tick for countdown
                vibrator.vibrate(30)
            }

            is HapticEvent.WARMUP_TO_WORKING -> {
                // Issue #100: Double pulse for transition
                vibrator.vibrate(longArrayOf(0, 80, 60, 120), -1)
            }

            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // No haptic for rep count announcement - audio only
            }
        }
    }
}
