package com.devil.phoenixproject.presentation.components

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlin.random.Random
import kotlinx.coroutines.flow.SharedFlow

private data class RawSound(val name: String, val resId: Int)

private val badgeSounds = listOf(
    RawSound("absolute_domination", R.raw.absolute_domination),
    RawSound("absolute_unit", R.raw.absolute_unit),
    RawSound("another_milestone_crushed", R.raw.another_milestone_crushed),
    RawSound("beast_mode", R.raw.beast_mode),
    RawSound("insane_performance", R.raw.insane_performance),
    RawSound("maxed_out", R.raw.maxed_out),
    RawSound("new_peak_achieved", R.raw.new_peak_achieved),
    RawSound("new_record_secured", R.raw.new_record_secured),
    RawSound("no_ones_stopping_you_now", R.raw.no_ones_stopping_you_now),
    RawSound("power", R.raw.power),
    RawSound("pr", R.raw.pr),
    RawSound("pressure_create_greatness", R.raw.pressure_create_greatness),
    RawSound("record", R.raw.record),
    RawSound("shattered", R.raw.shattered),
    RawSound("strenght_unlocked", R.raw.strenght_unlocked),
    RawSound("that_bar_never_stood_a_chance", R.raw.that_bar_never_stood_a_chance),
    RawSound("that_was_a_demolition", R.raw.that_was_a_demolition),
    RawSound("that_was_god_mode", R.raw.that_was_god_mode),
    RawSound("that_was_monster_level", R.raw.that_was_monster_level),
    RawSound("that_was_next_tier_strenght", R.raw.that_was_next_tier_strenght),
    RawSound("that_was_pure_savagery", R.raw.that_was_pure_savagery),
    RawSound("the_grind_continues", R.raw.the_grind_continues),
    RawSound("the_grind_is_real", R.raw.the_grind_is_real),
    RawSound("this_is_what_champions_are_made", R.raw.this_is_what_champions_are_made),
    RawSound("unchained_power", R.raw.unchained_power),
    RawSound("unstoppable", R.raw.unstoppable),
    RawSound("victory", R.raw.victory),
    RawSound("you_crushed_that", R.raw.you_crushed_that),
    RawSound("you_dominated_that_set", R.raw.you_dominated_that_set),
    RawSound("you_just_broke_your_limits", R.raw.you_just_broke_your_limits),
    RawSound("you_just_destroyed_that_weight", R.raw.you_just_destroyed_that_weight),
    RawSound("you_just_levelled_up", R.raw.you_just_levelled_up),
    RawSound("you_went_full_throttle", R.raw.you_went_full_throttle),
)

private val prSounds = listOf(
    RawSound("new_personal_record", R.raw.new_personal_record),
    RawSound("new_personal_record_2", R.raw.new_personal_record_2),
)

private val repCountSounds = listOf(
    RawSound("rep_01", R.raw.rep_01),
    RawSound("rep_02", R.raw.rep_02),
    RawSound("rep_03", R.raw.rep_03),
    RawSound("rep_04", R.raw.rep_04),
    RawSound("rep_05", R.raw.rep_05),
    RawSound("rep_06", R.raw.rep_06),
    RawSound("rep_07", R.raw.rep_07),
    RawSound("rep_08", R.raw.rep_08),
    RawSound("rep_09", R.raw.rep_09),
    RawSound("rep_10", R.raw.rep_10),
    RawSound("rep_11", R.raw.rep_11),
    RawSound("rep_12", R.raw.rep_12),
    RawSound("rep_13", R.raw.rep_13),
    RawSound("rep_14", R.raw.rep_14),
    RawSound("rep_15", R.raw.rep_15),
    RawSound("rep_16", R.raw.rep_16),
    RawSound("rep_17", R.raw.rep_17),
    RawSound("rep_18", R.raw.rep_18),
    RawSound("rep_19", R.raw.rep_19),
    RawSound("rep_20", R.raw.rep_20),
    RawSound("rep_21", R.raw.rep_21),
    RawSound("rep_22", R.raw.rep_22),
    RawSound("rep_23", R.raw.rep_23),
    RawSound("rep_24", R.raw.rep_24),
    RawSound("rep_25", R.raw.rep_25),
)

private fun fixedSoundForEvent(event: HapticEvent): RawSound? = when (event) {
    is HapticEvent.REP_COMPLETED -> RawSound("chirpchirp", R.raw.chirpchirp)

    is HapticEvent.FINAL_REP -> RawSound("boopbeepbeep", R.raw.boopbeepbeep)

    is HapticEvent.WARMUP_COMPLETE -> RawSound("beepboop", R.raw.beepboop)

    is HapticEvent.WORKOUT_COMPLETE -> RawSound("boopbeepbeep", R.raw.boopbeepbeep)

    is HapticEvent.WORKOUT_START -> RawSound("chirpchirp", R.raw.chirpchirp)

    is HapticEvent.WORKOUT_END -> RawSound("chirpchirp", R.raw.chirpchirp)

    is HapticEvent.REST_ENDING -> RawSound("restover", R.raw.restover)

    is HapticEvent.DISCO_MODE_UNLOCKED -> RawSound("discomode", R.raw.discomode)

    is HapticEvent.REP_COUNT_ANNOUNCED -> repCountSounds.getOrNull(event.repNumber - 1)

    is HapticEvent.COUNTDOWN_TICK -> RawSound("beep", R.raw.beep)

    is HapticEvent.WARMUP_TO_WORKING -> RawSound("beepboop", R.raw.beepboop)

    is HapticEvent.VELOCITY_THRESHOLD_REACHED -> RawSound("boopbeepbeep", R.raw.boopbeepbeep)

    is HapticEvent.ERROR,
    is HapticEvent.BADGE_EARNED,
    is HapticEvent.PERSONAL_RECORD,
    -> null
}

private fun soundForMediaPlayerFallback(event: HapticEvent): RawSound? = when (event) {
    is HapticEvent.BADGE_EARNED -> badgeSounds.random()
    is HapticEvent.PERSONAL_RECORD -> prSounds.random()
    else -> fixedSoundForEvent(event)
}

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

    // Create SoundPool for audio feedback
    // Uses USAGE_GAME to:
    // 1. Tie sounds to media volume (not notification - so they play through DND)
    // 2. Mix with music without interrupting it (game audio is designed for this)
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        loadedSounds.add(sampleId)
                    }
                }
            }
    }

    // Load sounds from compile-time raw resource IDs.
    val soundIds = remember(soundPool) {
        mutableMapOf<HapticEvent, Int>().apply {
            try {
                // Issue #100: chirpchirp is louder/more audible than beep for rep completion
                loadSound(context, soundPool, RawSound("chirpchirp", R.raw.chirpchirp))?.let { put(HapticEvent.REP_COMPLETED, it) }
                // Issue #100: Distinct boopbeepbeep sound on final working rep
                loadSound(context, soundPool, RawSound("boopbeepbeep", R.raw.boopbeepbeep))?.let { put(HapticEvent.FINAL_REP, it) }
                loadSound(context, soundPool, RawSound("beepboop", R.raw.beepboop))?.let { put(HapticEvent.WARMUP_COMPLETE, it) }
                loadSound(context, soundPool, RawSound("boopbeepbeep", R.raw.boopbeepbeep))?.let { put(HapticEvent.WORKOUT_COMPLETE, it) }
                loadSound(context, soundPool, RawSound("chirpchirp", R.raw.chirpchirp))?.let { put(HapticEvent.WORKOUT_START, it) }
                loadSound(context, soundPool, RawSound("chirpchirp", R.raw.chirpchirp))?.let { put(HapticEvent.WORKOUT_END, it) }
                loadSound(context, soundPool, RawSound("restover", R.raw.restover))?.let { put(HapticEvent.REST_ENDING, it) }
                loadSound(context, soundPool, RawSound("discomode", R.raw.discomode))?.let { put(HapticEvent.DISCO_MODE_UNLOCKED, it) }
                // Issue #100: Warmup-to-working transition (ascending tone)
                loadSound(context, soundPool, RawSound("beepboop", R.raw.beepboop))?.let { put(HapticEvent.WARMUP_TO_WORKING, it) }
                // Issue #313: Velocity loss threshold alert (attention-getting)
                loadSound(context, soundPool, RawSound("boopbeepbeep", R.raw.boopbeepbeep))?.let {
                    put(HapticEvent.VELOCITY_THRESHOLD_REACHED, it)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load sounds" }
            }
        }
    }

    // Load badge celebration sounds
    val badgeSoundIds = remember(soundPool) {
        badgeSounds.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Load PR-specific sounds
    val prSoundIds = remember(soundPool) {
        prSounds.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Load rep count sounds (1-25)
    val repCountSoundIds = remember(soundPool) {
        repCountSounds.mapNotNull { loadSound(context, soundPool, it) }
    }

    // Issue #100: Load countdown tick sound (reuses beep for short tick)
    val countdownTickSoundId = remember(soundPool) {
        loadSound(context, soundPool, RawSound("beep", R.raw.beep))
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

private fun loadSound(context: Context, soundPool: SoundPool, sound: RawSound): Int? = try {
    soundPool.load(context, sound.resId, 1)
} catch (e: Exception) {
    Logger.e(e) { "Failed to load sound '${sound.name}' (resId=${sound.resId})" }
    null
}

/**
 * Issue #409: Check media volume and log warning if muted.
 * USAGE_GAME routes to STREAM_MUSIC — if media volume is 0, all sounds are inaudible.
 * Requires Activity.volumeControlStream = AudioManager.STREAM_MUSIC to let
 * hardware volume buttons control the correct stream.
 */
private fun warnIfMediaVolumeMuted(context: Context) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Logger.w {
                "Issue #409: Media volume is 0/$maxVolume — workout sounds will be inaudible. " +
                    "Sounds use USAGE_GAME (STREAM_MUSIC). Raise media volume to hear audio cues."
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

    val audioFocusSession = requestTransientDuckAudioFocus(context, AudioAttributes.USAGE_GAME)

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
            audioFocusSession.abandon()
            playWithMediaPlayer(event, context)
        } else {
            audioFocusSession.abandonAfterDelay(focusHoldDurationMs(context, event))
        }
    } catch (e: Exception) {
        Logger.w(e) { "SoundPool.play threw for $event — MediaPlayer fallback" }
        audioFocusSession.abandon()
        playWithMediaPlayer(event, context)
    }
}

/**
 * Fallback sound playback using MediaPlayer for when SoundPool fails or on Fire OS.
 * Fire OS: Uses USAGE_MEDIA to work around SoundPool volume bug.
 * Standard Android: Uses USAGE_GAME to ensure sounds play through DND and use media volume.
 */
private fun playWithMediaPlayer(event: HapticEvent, context: Context) {
    val sound = soundForMediaPlayerFallback(event) ?: return

    val playbackUsage = if (DeviceInfo.isFireOS()) {
        AudioAttributes.USAGE_MEDIA
    } else {
        AudioAttributes.USAGE_GAME
    }
    val audioFocusSession = requestTransientDuckAudioFocus(context, playbackUsage)

    try {
        // Fire OS: Use USAGE_MEDIA to work around SoundPool volume bug
        // Standard Android: Use USAGE_GAME to mix with music without interrupting
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(playbackUsage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val mediaPlayer = MediaPlayer.create(context, sound.resId, audioAttributes, 0)
        if (mediaPlayer == null) {
            audioFocusSession.abandon()
            return
        }
        mediaPlayer.setVolume(1.0f, 1.0f)
        mediaPlayer.setOnCompletionListener {
            it.release()
            audioFocusSession.abandon()
        }
        mediaPlayer.start()
    } catch (_: Exception) {
        // Silently fail - sound is not critical
        audioFocusSession.abandon()
    }
}

private val soundDurationCacheMs = mutableMapOf<Int, Long>()

private fun focusHoldDurationMs(context: Context, event: HapticEvent): Long {
    val candidateSounds = when (event) {
        is HapticEvent.BADGE_EARNED -> badgeSounds

        is HapticEvent.PERSONAL_RECORD -> prSounds

        is HapticEvent.DISCO_MODE_UNLOCKED,
        is HapticEvent.REP_COUNT_ANNOUNCED,
        is HapticEvent.REST_ENDING,
        -> listOfNotNull(fixedSoundForEvent(event))

        else -> emptyList()
    }

    val maxDurationMs = candidateSounds.maxOfOrNull { getSoundDurationMs(context, it) } ?: 0L
    val fallbackMs = 1500L
    val safetyBufferMs = 250L
    return maxOf(fallbackMs, maxDurationMs + safetyBufferMs)
}

private fun getSoundDurationMs(context: Context, sound: RawSound): Long {
    soundDurationCacheMs[sound.resId]?.let { return it }

    val durationMs = try {
        MediaPlayer.create(context, sound.resId)?.useDurationOrZero() ?: 0L
    } catch (_: Exception) {
        0L
    }

    soundDurationCacheMs[sound.resId] = durationMs
    return durationMs
}

private fun MediaPlayer.useDurationOrZero(): Long = try {
    duration.toLong()
} catch (_: Exception) {
    0L
} finally {
    try {
        release()
    } catch (_: Exception) {
        // Best effort cleanup
    }
}

private fun AudioFocusSession.abandonAfterDelay(delayMs: Long = 1500L) {
    Handler(Looper.getMainLooper()).postDelayed({
        abandon()
    }, delayMs)
}

private interface AudioFocusSession {
    fun abandon()
}

private fun requestTransientDuckAudioFocus(
    context: Context,
    playbackUsage: Int,
): AudioFocusSession {
    val focusChangeListener = AudioManager.OnAudioFocusChangeListener {
        // No-op listener used as a unique identity token for each focus request session.
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return object : AudioFocusSession {
            override fun abandon() = Unit
        }

    return try {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(playbackUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .build()
        audioManager.requestAudioFocus(request)
        object : AudioFocusSession {
            override fun abandon() {
                try {
                    audioManager.abandonAudioFocusRequest(request)
                } catch (_: Exception) {
                    // Best effort
                }
            }
        }
    } catch (_: Exception) {
        object : AudioFocusSession {
            override fun abandon() = Unit
        }
    }
}

@SuppressLint("MissingPermission")
private fun playHapticFeedback(vibrator: Vibrator, event: HapticEvent) {
    // REP_COUNT_ANNOUNCED has no haptic feedback - it's audio only
    if (event is HapticEvent.REP_COUNT_ANNOUNCED) return

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
}
