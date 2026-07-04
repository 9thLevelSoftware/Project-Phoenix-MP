@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.presentation.manager.ExerciseCountdownCuePolicy
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.darwin.NSObjectProtocol

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
        hapticEvents.collect { event ->
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
    private var countdownTickPlayer: AVAudioPlayer? = null // Issue #100

    // Issue #611: Verbal encouragement pools (4 pools + 1 unlock SFX from PR #612)
    private val encouragementNeutralSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementMildSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementStrongSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementDominatrixSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private var dominatrixUnlockPlayer: AVAudioPlayer? = null

    // Issue #522: Observer tokens for foreground + AVAudioSession interruption
    // notifications. Removed in release() to avoid leaking observers across
    // recompositions of HapticFeedbackEffect.
    private val lifecycleObservers = mutableListOf<NSObjectProtocol>()
    private var released = false

    init {
        setupAudioSession()
        loadSounds()
        loadBadgeSounds()
        loadPRSounds()
        loadRepCountSounds()
        loadEncouragementNeutralSounds()
        loadEncouragementMildSounds()
        loadEncouragementStrongSounds()
        loadEncouragementDominatrixSounds()
        loadDominatrixUnlockSound()
        // Issue #611 §9.4: One-time boot log asserting the 4 verbal-encouragement pool sizes
        // match the PR #612 contract. Required by the implementation Gate 11-equivalent.
        log.i {
            "VBT: encouragement pool sizes — " +
                "neutral=${encouragementNeutralSoundPlayers.size} " +
                "mild=${encouragementMildSoundPlayers.size} " +
                "strong=${encouragementStrongSoundPlayers.size} " +
                "dominatrix=${encouragementDominatrixSoundPlayers.size} " +
                "unlock=${if (dominatrixUnlockPlayer != null) 1 else 0}"
        }
        installLifecycleObservers()
    }

    private fun setupAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            // Use Ambient category with MixWithOthers to play alongside music,
            // but do not downgrade an active PlayAndRecord session owned by
            // SafeWordListener. AVAudioSession is process-wide; switching back
            // to Ambient during a workout would break microphone input.
            if (session.category != AVAudioSessionCategoryPlayAndRecord) {
                session.setCategory(
                    AVAudioSessionCategoryAmbient,
                    AVAudioSessionCategoryOptionMixWithOthers,
                    null,
                )
            }
            session.setActive(true, null)
        } catch (e: Exception) {
            log.w { "Failed to setup audio session: ${e.message}" }
        }
    }

    /**
     * Issue #522: Observe iOS app-foreground and AVAudioSession interruption
     * notifications so that voice prompts and sound effects resume after the
     * user backgrounds then foregrounds the app, or after a phone call /
     * Siri / Alarm interruption ends. On either event we re-activate the
     * session and re-prepare the cached AVAudioPlayer instances (which can
     * become invalid when the underlying AVAudioSession tears itself down
     * during backgrounding).
     */
    private fun installLifecycleObservers() {
        if (released) return
        val center = NSNotificationCenter.defaultCenter

        val foregroundObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                recoverAudioSession()
            },
        )
        val interruptionObserver = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { notification ->
                val userInfo = notification?.userInfo
                val typeValue = (userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)
                    ?.unsignedLongLongValue
                // Only react to "ended" — "began" means the system has already
                // suspended playback, and we will be re-foregrounded / re-shown
                // when the interruption ends.
                if (typeValue == AVAudioSessionInterruptionTypeEnded) {
                    recoverAudioSession()
                }
            },
        )
        lifecycleObservers += foregroundObserver
        lifecycleObservers += interruptionObserver
    }

    private fun recoverAudioSession() {
        if (released) return
        setupAudioSession()
        prepareAllPlayers()
    }

    /**
     * Re-prepare every cached AVAudioPlayer. After long backgrounding or an
     * AVAudioSession interruption the players' underlying audio files can be
     * deallocated; calling prepareToPlay() makes them usable again before
     * the next playSound() call.
     */
    private fun prepareAllPlayers() {
        players.values.forEach { it?.prepareToPlay() }
        badgeSoundPlayers.forEach { it?.prepareToPlay() }
        prSoundPlayers.forEach { it?.prepareToPlay() }
        repCountSoundPlayers.forEach { it?.prepareToPlay() }
        countdownTickPlayer?.prepareToPlay()
        // Issue #611: Verbal encouragement pools + dominatrix unlock SFX
        encouragementNeutralSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementMildSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementStrongSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementDominatrixSoundPlayers.forEach { it?.prepareToPlay() }
        dominatrixUnlockPlayer?.prepareToPlay()
    }

    private fun loadSounds() {
        // Map events to sound file names (without extension)
        // Note: Using sealed class data objects as map keys (they have proper equals/hashCode)
        // Issue #100: Sound files converted from .ogg → .caf via iosApp/convert_sounds.sh
        val soundFiles: Map<HapticEvent, String> = mapOf(
            HapticEvent.REP_COMPLETED to "rep_complete_strong", // Issue #490: distinct regular rep cue
            HapticEvent.FINAL_REP to "boopbeepbeep", // Issue #100: Distinct final rep sound
            HapticEvent.WARMUP_COMPLETE to "beepboop",
            HapticEvent.WORKOUT_COMPLETE to "boopbeepbeep",
            HapticEvent.WORKOUT_START to "chirpchirp",
            HapticEvent.WORKOUT_END to "chirpchirp",
            HapticEvent.REST_ENDING to "restover",
            HapticEvent.DISCO_MODE_UNLOCKED to "discomode",
            // Issue #611: Dominatrix easter-egg unlock SFX
            HapticEvent.DOMINATRIX_MODE_UNLOCKED to "dominatrix_unlock",
            // Issue #100: Warmup-to-working transition (ascending tone)
            HapticEvent.WARMUP_TO_WORKING to "beepboop",
            // Issue #313: Velocity loss threshold alert (attention-getting)
            HapticEvent.VELOCITY_THRESHOLD_REACHED to "boopbeepbeep",
            // ERROR, BADGE_EARNED, PERSONAL_RECORD, REP_COUNT_ANNOUNCED, COUNTDOWN_TICK handled separately
        )

        soundFiles.forEach { (event, fileName) ->
            players[event] = loadSound(fileName)
        }

        // Issue #100: Load countdown tick sound (reuses beep)
        countdownTickPlayer = loadSound("beep")
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
            "you_went_full_throttle",
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
            "new_personal_record_2",
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

    // Issue #611: Verbal encouragement audio pools. Bare filenames match PR #612's
    // `Sounds/` drop-in contract (no extension; loadSound() tries .caf/.m4a/.wav/.mp3).
    private fun loadEncouragementNeutralSounds() {
        val soundFiles = (1..15).map { i -> "encouragement_${i.toString().padStart(2, '0')}" }
        soundFiles.forEach { fileName ->
            loadSound(fileName)?.let { encouragementNeutralSoundPlayers.add(it) }
        }
        log.d { "Loaded ${encouragementNeutralSoundPlayers.size} encouragement neutral sounds" }
    }

    private fun loadEncouragementMildSounds() {
        val soundFiles = (1..12).map { i -> "vulgar_mild_${i.toString().padStart(2, '0')}" }
        soundFiles.forEach { fileName ->
            loadSound(fileName)?.let { encouragementMildSoundPlayers.add(it) }
        }
        log.d { "Loaded ${encouragementMildSoundPlayers.size} vulgar mild sounds" }
    }

    private fun loadEncouragementStrongSounds() {
        val soundFiles = (1..12).map { i -> "vulgar_strong_${i.toString().padStart(2, '0')}" }
        soundFiles.forEach { fileName ->
            loadSound(fileName)?.let { encouragementStrongSoundPlayers.add(it) }
        }
        log.d { "Loaded ${encouragementStrongSoundPlayers.size} vulgar strong sounds" }
    }

    private fun loadEncouragementDominatrixSounds() {
        val soundFiles = (1..12).map { i -> "dominatrix_${i.toString().padStart(2, '0')}" }
        soundFiles.forEach { fileName ->
            loadSound(fileName)?.let { encouragementDominatrixSoundPlayers.add(it) }
        }
        log.d { "Loaded ${encouragementDominatrixSoundPlayers.size} dominatrix sounds" }
    }

    private fun loadDominatrixUnlockSound() {
        dominatrixUnlockPlayer = loadSound("dominatrix_unlock")
        log.d { "Dominatrix unlock SFX loaded: ${dominatrixUnlockPlayer != null}" }
    }

    /**
     * Issue #611: Play the dominatrix unlock whip-crack SFX from Settings when the
     * 7-tap easter egg fires. Mirrors onPlayDiscoSound() in SettingsTab.kt.
     */
    fun playDominatrixUnlockSound() {
        dominatrixUnlockPlayer?.let { player ->
            try {
                player.prepareToPlay()
                player.currentTime = 0.0
                player.play()
            } catch (e: Exception) {
                log.w { "Dominatrix unlock SFX playback failed: ${e.message}" }
            }
        }
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
                } else {
                    null
                }
            }

            is HapticEvent.PERSONAL_RECORD -> {
                if (prSoundPlayers.isNotEmpty()) {
                    prSoundPlayers[kotlin.random.Random.nextInt(prSoundPlayers.size)]
                } else {
                    null
                }
            }

            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // Play the numbered rep count sound (index is repNumber - 1)
                val index = event.repNumber - 1
                if (index in repCountSoundPlayers.indices) {
                    repCountSoundPlayers[index]
                } else {
                    null
                }
            }

            is HapticEvent.COUNTDOWN_TICK -> countdownTickPlayer

            // Issue #611: Verbal encouragement pool routing. Mirrors Android cueForEvent.
            is HapticEvent.VERBAL_ENCOURAGEMENT -> {
                when {
                    event.dominatrixMode && encouragementDominatrixSoundPlayers.isNotEmpty() ->
                        encouragementDominatrixSoundPlayers[
                            kotlin.random.Random.nextInt(encouragementDominatrixSoundPlayers.size),
                        ]
                    event.vulgarTier == VulgarTier.MILD && encouragementMildSoundPlayers.isNotEmpty() ->
                        encouragementMildSoundPlayers[
                            kotlin.random.Random.nextInt(encouragementMildSoundPlayers.size),
                        ]
                    event.vulgarTier == VulgarTier.STRONG && encouragementStrongSoundPlayers.isNotEmpty() ->
                        encouragementStrongSoundPlayers[
                            kotlin.random.Random.nextInt(encouragementStrongSoundPlayers.size),
                        ]
                    event.vulgarTier == VulgarTier.MIX -> {
                        val combined = encouragementMildSoundPlayers + encouragementStrongSoundPlayers
                        if (combined.isNotEmpty()) {
                            combined[kotlin.random.Random.nextInt(combined.size)]
                        } else {
                            null
                        }
                    }
                    else -> if (encouragementNeutralSoundPlayers.isNotEmpty()) {
                        encouragementNeutralSoundPlayers[
                            kotlin.random.Random.nextInt(encouragementNeutralSoundPlayers.size),
                        ]
                    } else {
                        null
                    }
                }
            }

            else -> players[event]
        }

        if (player != null) {
            try {
                // Issue #522: Defensively re-prepare the chosen player. The
                // lifecycle observers own session reactivation so this hot
                // path does not repeatedly reconfigure the process-wide
                // AVAudioSession or downgrade SafeWordListener's PlayAndRecord
                // category during active workouts.
                player.prepareToPlay()
                // Reset to beginning if already playing
                player.currentTime = 0.0
                if (event is HapticEvent.COUNTDOWN_TICK) {
                    player.enableRate = true
                    player.rate = ExerciseCountdownCuePolicy.playbackRate(event.secondsRemaining)
                }
                player.play()
            } catch (e: Exception) {
                log.w { "Sound playback failed for $event: ${e.message}" }
            }
        }
    }

    fun release() {
        // Issue #522: Prevent late-arriving foreground / interruption
        // notifications from re-activating the session after we have already
        // torn it down, and remove the observers we installed in init.
        released = true
        lifecycleObservers.forEach { observer ->
            try {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        lifecycleObservers.clear()

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
            countdownTickPlayer?.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        countdownTickPlayer = null

        // Issue #611: Stop + clear the verbal encouragement pools
        listOf(
            encouragementNeutralSoundPlayers,
            encouragementMildSoundPlayers,
            encouragementStrongSoundPlayers,
            encouragementDominatrixSoundPlayers,
        ).forEach { pool ->
            pool.forEach { player ->
                try { player?.stop() } catch (e: Exception) { /* ignore */ }
            }
            pool.clear()
        }
        try {
            dominatrixUnlockPlayer?.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        dominatrixUnlockPlayer = null

        try {
            // F063: AVAudioSession is process-wide. If SafeWordListener currently
            // holds it in playAndRecord for emergency voice detection, deactivating
            // here would break the microphone. Only deactivate when we are not
            // stepping on an active record session (mirrors the setup-time guard).
            val session = AVAudioSession.sharedInstance()
            if (session.category != AVAudioSessionCategoryPlayAndRecord) {
                session.setActive(
                    false,
                    AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    null,
                )
            } else {
                log.d { "Skipping audio-session deactivation: playAndRecord is active (safe-word listener owns it)" }
            }
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
    // Issue #611: VERBAL_ENCOURAGEMENT is audio-only - no haptic feedback
    if (event is HapticEvent.VERBAL_ENCOURAGEMENT) return

    try {
        when (event) {
            is HapticEvent.REP_COMPLETED -> {
                // Light impact for each rep
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                generator.prepare()
                generator.impactOccurred()
            }

            is HapticEvent.FINAL_REP -> {
                // Issue #100: Heavy impact for final rep — stands out from regular rep feedback
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
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

            // Issue #611: Dominatrix unlock - heavy impact matching the whip crack SFX
            is HapticEvent.DOMINATRIX_MODE_UNLOCKED -> {
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
                generator.prepare()
                generator.impactOccurred()
            }

            is HapticEvent.COUNTDOWN_TICK -> {
                // Issue #100: Light tick for rest countdown
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                generator.prepare()
                generator.impactOccurred()
            }

            is HapticEvent.WARMUP_TO_WORKING -> {
                // Issue #100: Medium impact for warmup-to-working transition
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                generator.prepare()
                generator.impactOccurred()
            }

            is HapticEvent.VELOCITY_THRESHOLD_REACHED -> {
                // Issue #313: Heavy impact + warning notification for velocity threshold alert
                val impactGenerator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
                impactGenerator.prepare()
                impactGenerator.impactOccurred()
                val notificationGenerator = UINotificationFeedbackGenerator()
                notificationGenerator.prepare()
                notificationGenerator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            }

            is HapticEvent.REP_COUNT_ANNOUNCED -> {
                // No haptic for rep count announcement - audio only
            }

            // Issue #611: VERBAL_ENCOURAGEMENT is audio-only - early return at top of fn,
            // but the exhaustive when requires an explicit arm.
            is HapticEvent.VERBAL_ENCOURAGEMENT -> {
                // No haptic for verbal encouragement - audio only
            }
        }
    } catch (e: Exception) {
        log.w { "Haptic feedback failed for $event: ${e.message}" }
    }
}
