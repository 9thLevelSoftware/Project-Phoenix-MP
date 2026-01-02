@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

private val log = Logger.withTag("HapticFeedbackEffect.ios")

/**
 * iOS implementation of HapticFeedbackEffect using UIKit haptic generators
 * and AVAudioPlayer for sound playback.
 *
 * Uses UIImpactFeedbackGenerator for workout events and UINotificationFeedbackGenerator
 * for completion/error states. Different haptic patterns are applied based on event type.
 * Sound files should be bundled in the iOS app as .caf or .m4a files.
 */
@Composable
actual fun HapticFeedbackEffect(hapticEvents: SharedFlow<HapticEvent>) {
    // Initialize sound players for each event type
    val soundPlayers = remember { IosSoundManager() }

    // Cleanup sound players when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            soundPlayers.release()
        }
    }

    LaunchedEffect(hapticEvents) {
        hapticEvents.collectLatest { event ->
            // Play haptic feedback
            playHapticFeedback(event)
            // Play sound (if available)
            soundPlayers.playSound(event)
        }
    }
}

/**
 * Manages sound playback for iOS using AVAudioPlayer.
 * Loads sounds from the app bundle and plays them for workout events.
 */
private class IosSoundManager {
    private val players = mutableMapOf<HapticEvent, AVAudioPlayer?>()
    private val badgeSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val prSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val repCountSoundPlayers = mutableListOf<AVAudioPlayer?>()

    init {
        setupAudioSession()
        loadSounds()
        loadBadgeSounds()
        loadPRSounds()
        loadRepCountSounds()
    }

    private fun setupAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            // Use Ambient category with MixWithOthers to play alongside music
            // This ensures our sounds don't interrupt user's music playback
            session.setCategory(
                AVAudioSessionCategoryAmbient,
                AVAudioSessionCategoryOptionMixWithOthers,
                null
            )
            session.setActive(true, null)
        } catch (e: Exception) {
            log.w { "Failed to setup audio session: ${e.message}" }
        }
    }

    private fun loadSounds() {
        // Map events to sound file names (without extension)
        // Note: Using sealed class data objects as map keys (they have proper equals/hashCode)
        val soundFiles: Map<HapticEvent, String> = mapOf(
            HapticEvent.REP_COMPLETED to "beep",
            HapticEvent.WARMUP_COMPLETE to "beepboop",
            HapticEvent.WORKOUT_COMPLETE to "boopbeepbeep",
            HapticEvent.WORKOUT_START to "chirpchirp",
            HapticEvent.WORKOUT_END to "chirpchirp",
            HapticEvent.REST_ENDING to "restover",
            HapticEvent.DISCO_MODE_UNLOCKED to "discomode"
            // ERROR, BADGE_EARNED, PERSONAL_RECORD, REP_COUNT_ANNOUNCED handled separately
        )

        soundFiles.forEach { (event, fileName) ->
            players[event] = loadSound(fileName)
        }
    }

    private fun loadBadgeSounds() {
        // Badge celebration sounds (excludes PR-specific sounds)
        val badgeSoundFiles = listOf(
            "absolute_domination",
            "absolute_unit",
            "another_milestone_crushed",
            "beast_mode",
            "insane_performance",
            "maxed_out",
            "new_peak_achieved",
            "new_record_secured",
            "no_ones_stopping_you_now",
            "power",
            "pr",
            "pressure_create_greatness",
            "record",
            "shattered",
            "strenght_unlocked",
            "that_bar_never_stood_a_chance",
            "that_was_a_demolition",
            "that_was_god_mode",
            "that_was_monster_level",
            "that_was_next_tier_strenght",
            "that_was_pure_savagery",
            "the_grind_continues",
            "the_grind_is_real",
            "this_is_what_champions_are_made",
            "unchained_power",
            "unstoppable",
            "victory",
            "you_crushed_that",
            "you_dominated_that_set",
            "you_just_broke_your_limits",
            "you_just_destroyed_that_weight",
            "you_just_levelled_up",
            "you_went_full_throttle"
        )

        badgeSoundFiles.forEach { fileName ->
            loadSound(fileName)?.let { badgeSoundPlayers.add(it) }
        }
        log.d { "Loaded ${badgeSoundPlayers.size} badge celebration sounds" }
    }

    private fun loadPRSounds() {
        // PR-specific sounds
        val prSoundFiles = listOf(
            "new_personal_record",
            "new_personal_record_2"
        )

        prSoundFiles.forEach { fileName ->
            loadSound(fileName)?.let { prSoundPlayers.add(it) }
        }
        log.d { "Loaded ${prSoundPlayers.size} PR celebration sounds" }
    }

    private fun loadRepCountSounds() {
        // Load rep count sounds rep_01 through rep_25
        // Files are named rep_01, rep_02, ..., rep_25
        for (i in 1..25) {
            val fileName = "rep_${i.toString().padStart(2, '0')}"
            val player = loadSound(fileName)
            repCountSoundPlayers.add(player)
            if (player == null) {
                log.w { "Failed to load rep count sound: $fileName" }
            }
        }
        val loadedCount = repCountSoundPlayers.count { it != null }
        log.d { "Loaded $loadedCount/25 rep count sounds" }
    }

    private fun loadSound(fileName: String): AVAudioPlayer? {
        // Try different audio formats in order of preference
        val extensions = listOf("caf", "m4a", "wav", "mp3")

        for (ext in extensions) {
            val url = NSBundle.mainBundle.URLForResource(fileName, ext)
            if (url != null) {
                try {
                    val player = AVAudioPlayer(url, null)
                    player.prepareToPlay()
                    player.volume = 0.8f
                    log.d { "Loaded sound: $fileName.$ext" }
                    return player
                } catch (e: Exception) {
                    log.w { "Failed to load $fileName.$ext: ${e.message}" }
                }
            }
        }

        log.d { "Sound file not found: $fileName (tried: ${extensions.joinToString()})" }
        return null
    }

    fun playSound(event: HapticEvent) {
        // ERROR event has no sound
        if (event is HapticEvent.ERROR) return

        val player = when (event) {
            is HapticEvent.BADGE_EARNED -> {
                if (badgeSoundPlayers.isNotEmpty()) {
                    badgeSoundPlayers[kotlin.random.Random.nextInt(badgeSoundPlayers.size)]
                } else null
            }
            is HapticEvent.PERSONAL_RECORD -> {
                if (prSoundPlayers.isNotEmpty()) {
                    prSoundPlayers[kotlin.random.Random.nextInt(prSoundPlayers.size)]
                } else null
            }
            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // Play the numbered rep count sound (index is repNumber - 1)
                val index = event.repNumber - 1
                if (index in repCountSoundPlayers.indices) {
                    repCountSoundPlayers[index]
                } else null
            }
            else -> players[event]
        }

        if (player != null) {
            try {
                // Reset to beginning if already playing
                player.currentTime = 0.0
                player.play()
            } catch (e: Exception) {
                log.w { "Sound playback failed for $event: ${e.message}" }
            }
        }
    }

    fun release() {
        players.values.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        players.clear()

        badgeSoundPlayers.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        badgeSoundPlayers.clear()

        prSoundPlayers.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        prSoundPlayers.clear()

        repCountSoundPlayers.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        repCountSoundPlayers.clear()

        try {
            AVAudioSession.sharedInstance().setActive(
                false,
                AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                null
            )
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

/**
 * Play haptic feedback based on event type
 */
private fun playHapticFeedback(event: HapticEvent) {
    // REP_COUNT_ANNOUNCED has no haptic feedback - it's audio only
    if (event is HapticEvent.REP_COUNT_ANNOUNCED) return

    try {
        when (event) {
            is HapticEvent.REP_COMPLETED -> {
                // Light impact for each rep
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                generator.prepare()
                generator.impactOccurred()
            }
            is HapticEvent.WARMUP_COMPLETE, is HapticEvent.WORKOUT_COMPLETE -> {
                // Success notification for completions
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            }
            is HapticEvent.WORKOUT_START, is HapticEvent.WORKOUT_END -> {
                // Medium impact for start/end
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                generator.prepare()
                generator.impactOccurred()
            }
            is HapticEvent.REST_ENDING -> {
                // Warning notification when rest is ending
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            }
            is HapticEvent.ERROR -> {
                // Error notification
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
            is HapticEvent.DISCO_MODE_UNLOCKED, is HapticEvent.BADGE_EARNED, is HapticEvent.PERSONAL_RECORD -> {
                // Celebration - heavy impact followed by success notification
                val impactGenerator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
                impactGenerator.prepare()
                impactGenerator.impactOccurred()
                // Follow with success notification for the celebration
                val notificationGenerator = UINotificationFeedbackGenerator()
                notificationGenerator.prepare()
                notificationGenerator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            }
            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // No haptic for rep count announcement - audio only
            }
        }
    } catch (e: Exception) {
        log.w { "Haptic feedback failed for $event: ${e.message}" }
    }
}
