@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.presentation.manager.ExerciseCountdownCuePolicy
import com.devil.phoenixproject.util.IosAudioDiagnostics
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionInterruptionWasSuspendedKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereLostNotification
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.darwin.NSObjectProtocol

private val log = Logger.withTag("HapticFeedbackEffect.ios")
private const val AUDIO_DIAGNOSTIC_TAG = "IosWorkoutAudio"

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
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "collector_start")
        try {
            hapticEvents.collect { event ->
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "cue_received") { "cue=${event::class.simpleName}" }
                // Play haptic feedback
                playHapticFeedback(event)
                // Play sound (if available)
                soundPlayers.playSound(event)
            }
        } finally {
            IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "collector_stop")
        }
    }
}

internal val IOS_EVENT_SOUND_FILES: Map<HapticEvent, String> = mapOf(
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
    // Issue #1356: Just Lift/Echo auto-start readiness cue (distinct asset, not chirpchirp)
    HapticEvent.AUTO_START_READY to "auto_start_ready",
    // Issue #313: Velocity loss threshold alert (attention-getting)
    HapticEvent.VELOCITY_THRESHOLD_REACHED to "boopbeepbeep",
    // ERROR, BADGE_EARNED, PERSONAL_RECORD, REP_COUNT_ANNOUNCED, COUNTDOWN_TICK handled separately
)

internal val IOS_BADGE_SOUND_FILES: List<String> = listOf(
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

internal val IOS_PR_SOUND_FILES: List<String> = listOf(
    "new_personal_record",
    "new_personal_record_2",
)

internal data class IosNumberedPoolSpec(
    val prefix: String,
    val count: Int,
    val label: String,
)

internal val IOS_ENCOURAGEMENT_POOL_SPECS: List<IosNumberedPoolSpec> = listOf(
    IosNumberedPoolSpec("encouragement", 15, "encouragement neutral sounds"),
    IosNumberedPoolSpec("vulgar_mild", 12, "vulgar mild sounds"),
    IosNumberedPoolSpec("vulgar_strong", 12, "vulgar strong sounds"),
    IosNumberedPoolSpec("dominatrix", 12, "dominatrix sounds"),
)

internal fun <T> List<T>.randomPoolEntry(): T =
    this[kotlin.random.Random.nextInt(size)]

internal fun <P : Any> loadNamedSoundPool(
    fileNames: List<String>,
    destination: MutableList<P?>,
    label: String,
    load: (String) -> P?,
) {
    fileNames.forEach { fileName ->
        load(fileName)?.let { destination.add(it) }
    }
    log.d { "Loaded ${destination.size} $label" }
}

internal fun <P : Any> loadNumberedSoundPool(
    prefix: String,
    count: Int,
    destination: MutableList<P?>,
    label: String,
    load: (String) -> P?,
) {
    for (i in 1..count) {
        load("${prefix}_${i.toString().padStart(2, '0')}")?.let { destination.add(it) }
    }
    log.d { "Loaded ${destination.size} $label" }
}

internal fun <P : Any> loadRepCountSoundPool(
    destination: MutableList<P?>,
    load: (String) -> P?,
) {
    for (i in 1..25) {
        val fileName = "rep_${i.toString().padStart(2, '0')}"
        val player = load(fileName)
        destination.add(player)
        if (player == null) {
            log.w { "Failed to load rep count sound: $fileName" }
        }
    }
    val loadedCount = destination.count { it != null }
    log.d { "Loaded $loadedCount/25 rep count sounds" }
}

internal fun <P : Any> selectIosSoundPlayer(
    event: HapticEvent,
    eventPlayers: Map<HapticEvent, P?>,
    badgePlayers: List<P?>,
    prPlayers: List<P?>,
    repCountPlayers: List<P?>,
    countdownTickPlayer: P?,
    neutralPlayers: List<P?>,
    mildPlayers: List<P?>,
    strongPlayers: List<P?>,
    dominatrixPlayers: List<P?>,
): P? = when (event) {
    is HapticEvent.ERROR -> null
    is HapticEvent.BADGE_EARNED -> if (badgePlayers.isNotEmpty()) badgePlayers.randomPoolEntry() else null
    is HapticEvent.PERSONAL_RECORD -> if (prPlayers.isNotEmpty()) prPlayers.randomPoolEntry() else null
    is HapticEvent.REP_COUNT_ANNOUNCED -> {
        val index = event.repNumber - 1
        if (index in repCountPlayers.indices) repCountPlayers[index] else null
    }
    is HapticEvent.COUNTDOWN_TICK -> countdownTickPlayer
    is HapticEvent.VERBAL_ENCOURAGEMENT -> when {
        !event.vulgarMode -> if (neutralPlayers.isNotEmpty()) neutralPlayers.randomPoolEntry() else null
        event.dominatrixMode && dominatrixPlayers.isNotEmpty() -> dominatrixPlayers.randomPoolEntry()
        event.dominatrixMode -> null
        event.vulgarTier == VulgarTier.MILD && mildPlayers.isNotEmpty() -> mildPlayers.randomPoolEntry()
        event.vulgarTier == VulgarTier.STRONG && strongPlayers.isNotEmpty() -> strongPlayers.randomPoolEntry()
        event.vulgarTier == VulgarTier.MIX -> {
            val combined = mildPlayers + strongPlayers
            if (combined.isNotEmpty()) combined.randomPoolEntry() else null
        }
        else -> if (neutralPlayers.isNotEmpty()) neutralPlayers.randomPoolEntry() else null
    }
    else -> eventPlayers[event]
}

/**
 * Manages sound playback for iOS using AVAudioPlayer.
 * Loads sounds from the app bundle and plays them for workout events.
 */
internal class IosSoundManager {
    private val players = mutableMapOf<HapticEvent, AVAudioPlayer?>()
    private val badgeSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val prSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val repCountSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private var countdownTickPlayer: AVAudioPlayer? = null // Issue #100

    // Issue #611: Verbal encouragement pools (unlock SFX is the event-keyed players map)
    private val encouragementNeutralSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementMildSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementStrongSoundPlayers = mutableListOf<AVAudioPlayer?>()
    private val encouragementDominatrixSoundPlayers = mutableListOf<AVAudioPlayer?>()

    // Issue #522: Observer tokens for foreground + AVAudioSession interruption
    // notifications. Removed in release() to avoid leaking observers across
    // recompositions of HapticFeedbackEffect.
    private val lifecycleObservers = mutableListOf<NSObjectProtocol>()
    private var released = false

    // Issue #1357: session-recovery and player-rebuild flags, the rebuild cap
    // and the retry decision live in the UIKit-free IosSoundRecoveryPolicy.
    // Focus loss (background, interruption, background cue, refused play)
    // arms a pool rebuild; re-preparing the cached players alone left them
    // silent until the app was restarted.
    private val recoveryPolicy = IosSoundRecoveryPolicy(
        object : IosSoundRecoveryHost {
            override fun activateSession(): Boolean = setupAudioSession()

            override fun rebuildPlayers(reason: String) {
                stopAndClearPlayers()
                loadAllSounds()
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "players_rebuilt") {
                    "reason=$reason ${playerCountsSummary()}"
                }
            }

            override fun reprepareCachedPlayers() = prepareAllPlayers()

            override fun recordRecovery(trigger: String, outcome: IosSoundRecoveryOutcome) {
                IosAudioDiagnostics.record(
                    AUDIO_DIAGNOSTIC_TAG,
                    "recovery_result",
                    warning = outcome == IosSoundRecoveryOutcome.ACTIVATION_FAILED,
                ) { "trigger=$trigger outcome=$outcome" }
            }
        },
    )

    init {
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "manager_init")
        recoveryPolicy.onInitialActivation(setupAudioSession())
        loadAllSounds()
        installLifecycleObservers()
    }

    private fun playerCountsSummary(): String =
        "eventPlayers=${players.values.count { it != null }} badge=${badgeSoundPlayers.count { it != null }} " +
            "pr=${prSoundPlayers.count { it != null }} rep=${repCountSoundPlayers.count { it != null }} " +
            "countdown=${countdownTickPlayer != null} neutral=${encouragementNeutralSoundPlayers.size} " +
            "mild=${encouragementMildSoundPlayers.size} strong=${encouragementStrongSoundPlayers.size} " +
            "dominatrix=${encouragementDominatrixSoundPlayers.size}"

    private fun loadAllSounds() {
        loadSounds()
        loadNamedSoundPool(IOS_BADGE_SOUND_FILES, badgeSoundPlayers, "badge celebration sounds", ::loadSound)
        loadNamedSoundPool(IOS_PR_SOUND_FILES, prSoundPlayers, "PR celebration sounds", ::loadSound)
        loadRepCountSoundPool(repCountSoundPlayers, ::loadSound)
        // Issue #611: verbal encouragement pools (PR #612 Sounds/ drop-in names).
        val targets = listOf(
            encouragementNeutralSoundPlayers,
            encouragementMildSoundPlayers,
            encouragementStrongSoundPlayers,
            encouragementDominatrixSoundPlayers,
        )
        IOS_ENCOURAGEMENT_POOL_SPECS.zip(targets).forEach { (spec, destination) ->
            loadNumberedSoundPool(spec.prefix, spec.count, destination, spec.label, ::loadSound)
        }
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "players_loaded") {
            "${playerCountsSummary()} ${IosAudioDiagnostics.sessionDetails()}"
        }
    }

    /**
     * Returns whether the shared session is active afterwards.
     *
     * Issue #1357: a refused activation (another app's session still tearing
     * down as we foreground) used to be logged and then forgotten. Callers
     * keep the result so the next cue can retry.
     */
    private fun setupAudioSession(): Boolean {
        return try {
            val session = AVAudioSession.sharedInstance()
            // Use Ambient category with MixWithOthers to play alongside music,
            // but do not downgrade an active PlayAndRecord session owned by
            // SafeWordListener. AVAudioSession is process-wide; switching back
            // to Ambient during a workout would break microphone input.
            if (session.category != AVAudioSessionCategoryPlayAndRecord) {
                IosAudioDiagnostics.nativeResult(AUDIO_DIAGNOSTIC_TAG, "set_category_ambient") { error ->
                    session.setCategory(
                        AVAudioSessionCategoryAmbient,
                        AVAudioSessionCategoryOptionMixWithOthers,
                        error,
                    )
                }
            } else {
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "preserve_play_and_record") {
                    IosAudioDiagnostics.sessionDetails()
                }
            }
            IosAudioDiagnostics.nativeResult(AUDIO_DIAGNOSTIC_TAG, "activate") { error ->
                session.setActive(true, error)
            }
        } catch (e: Exception) {
            IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "session_setup_exception", warning = true)
            log.w { "Failed to setup audio session: ${e.message}" }
            false
        }
    }

    private fun isAppActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    /**
     * Issue #522: Observe iOS app-foreground and AVAudioSession interruption
     * notifications so that voice prompts and sound effects resume after the
     * user backgrounds then foregrounds the app, or after a phone call /
     * Siri / Alarm interruption ends. On either event we re-activate the
     * session. Issue #1357: the cached AVAudioPlayer instances can become
     * invalid when the underlying AVAudioSession tears itself down during
     * backgrounding, and prepareToPlay() does not revive them, so a background
     * transition or interruption Began arms a full pool rebuild for that
     * recovery; otherwise the cached players are re-prepared.
     */
    private fun installLifecycleObservers() {
        if (released) return
        val center = NSNotificationCenter.defaultCenter

        val foregroundObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "app_active") {
                    "released=$released ${IosAudioDiagnostics.sessionDetails()}"
                }
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
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "interruption") {
                    "type=$typeValue options=${(userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongLongValue} " +
                        "wasSuspended=${(userInfo?.get(AVAudioSessionInterruptionWasSuspendedKey) as? NSNumber)?.boolValue} " +
                        "released=$released ${IosAudioDiagnostics.sessionDetails()}"
                }
                // "Began" means the system has already deactivated us. Arm
                // reactivation and a pool rebuild so the next foreground cue
                // recovers even if "ended" never arrives, which iOS does not
                // guarantee (Issue #1357).
                if (typeValue == AVAudioSessionInterruptionTypeBegan) {
                    recoveryPolicy.onInterruptionBegan()
                } else if (typeValue == AVAudioSessionInterruptionTypeEnded) {
                    recoverAudioSession()
                }
            },
        )
        // Issue #1357: after a media services reset every AVAudioPlayer is
        // dead and prepareToPlay() cannot revive it, so rebuild them.
        val mediaResetObserver = center.addObserverForName(
            name = AVAudioSessionMediaServicesWereResetNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                rebuildAfterMediaServicesReset()
            },
        )
        // Issue #1357: the system can invalidate the cached players while we
        // are backgrounded, so entering the background arms a pool rebuild
        // for the next foreground recovery. Nothing runs in the background.
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "app_background") {
                    "released=$released ${IosAudioDiagnostics.sessionDetails()}"
                }
                if (!released) recoveryPolicy.onEnteredBackground()
            },
        )
        lifecycleObservers += foregroundObserver
        lifecycleObservers += interruptionObserver
        lifecycleObservers += mediaResetObserver
        lifecycleObservers += backgroundObserver
        // Observation only: these notifications do not add recovery or change session ownership.
        // (The media services reset rebuild and the background arming above are separate observers.)
        listOf(
            UIApplicationWillResignActiveNotification to "app_resign_active",
            AVAudioSessionRouteChangeNotification to "route_change",
            AVAudioSessionMediaServicesWereLostNotification to "media_services_lost",
            AVAudioSessionMediaServicesWereResetNotification to "media_services_reset",
        ).forEach { (name, event) ->
            try {
                lifecycleObservers += center.addObserverForName(
                    name = name,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue(),
                    usingBlock = { notification ->
                        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, event) {
                            "released=$released routeReason=${(notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongLongValue} " +
                                IosAudioDiagnostics.sessionDetails()
                        }
                    },
                )
            } catch (_: Exception) {
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "diagnostic_observer_failed", warning = true) { "event=$event" }
            }
        }
    }

    /**
     * Issue #1357: activates the session, then rebuilds the player pool when
     * focus loss armed a rebuild (stopAndClearPlayers() + loadAllSounds() via
     * the policy host), otherwise re-prepares the cached players. Skipped
     * while the app is not active or after release.
     */
    private fun recoverAudioSession() {
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "recovery_requested") { "released=$released" }
        if (released) return
        recoveryPolicy.recover(appActive = isAppActive(), trigger = "lifecycle")
    }

    private fun rebuildAfterMediaServicesReset() {
        if (released) return
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "rebuild_after_media_reset", warning = true)
        stopAndClearPlayers()
        val activated = setupAudioSession()
        loadAllSounds()
        recoveryPolicy.onPoolRebuiltExternally(activated, isAppActive())
    }

    /**
     * Re-prepare every cached AVAudioPlayer for a recovery that did not arm a
     * rebuild. Issue #1357: this cannot revive players the system invalidated
     * while backgrounded; those recoveries rebuild the pool instead.
     */
    private fun prepareAllPlayers() {
        players.values.forEach { it?.prepareToPlay() }
        badgeSoundPlayers.forEach { it?.prepareToPlay() }
        prSoundPlayers.forEach { it?.prepareToPlay() }
        repCountSoundPlayers.forEach { it?.prepareToPlay() }
        countdownTickPlayer?.prepareToPlay()
        // Issue #611: Verbal encouragement pools
        encouragementNeutralSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementMildSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementStrongSoundPlayers.forEach { it?.prepareToPlay() }
        encouragementDominatrixSoundPlayers.forEach { it?.prepareToPlay() }
    }

    private fun loadSounds() {
        IOS_EVENT_SOUND_FILES.forEach { (event, fileName) ->
            players[event] = loadSound(fileName)
        }

        // Issue #100: Load countdown tick sound (reuses beep)
        countdownTickPlayer = loadSound("beep")
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
        if (event is HapticEvent.ERROR) {
            IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "cue_has_no_sound") { "cue=ERROR" }
            return
        }

        try {
            // Issue #1357: flagged recovery (which may rebuild the pool) runs
            // BEFORE a player is selected, and a refused play() gets one
            // recovery and one retry on a player re-selected afterwards. A
            // player captured before a rebuild is never played. While the app
            // is not active a cue only arms recovery for the next foreground.
            val outcome = recoveryPolicy.playCue<AVAudioPlayer>(
                appActive = { isAppActive() },
                selectPlayer = {
                    selectIosSoundPlayer(
                        event = event,
                        eventPlayers = players,
                        badgePlayers = badgeSoundPlayers,
                        prPlayers = prSoundPlayers,
                        repCountPlayers = repCountSoundPlayers,
                        countdownTickPlayer = countdownTickPlayer,
                        neutralPlayers = encouragementNeutralSoundPlayers,
                        mildPlayers = encouragementMildSoundPlayers,
                        strongPlayers = encouragementStrongSoundPlayers,
                        dominatrixPlayers = encouragementDominatrixSoundPlayers,
                    )
                },
                play = { player, isRetry ->
                    // Issue #522: Defensively re-prepare the chosen player. The
                    // lifecycle observers and the recovery policy own session
                    // reactivation so this hot path does not reconfigure the
                    // process-wide AVAudioSession on every cue or downgrade
                    // SafeWordListener's PlayAndRecord category.
                    val prepared = player.prepareToPlay()
                    // Reset to beginning if already playing
                    player.currentTime = 0.0
                    if (event is HapticEvent.COUNTDOWN_TICK) {
                        player.enableRate = true
                        player.rate = ExerciseCountdownCuePolicy.playbackRate(event.secondsRemaining)
                    }
                    val played = player.play()
                    IosAudioDiagnostics.record(
                        AUDIO_DIAGNOSTIC_TAG,
                        if (isRetry) "player_retry_result" else "player_result",
                        warning = !prepared || !played,
                    ) {
                        "cue=${event::class.simpleName} prepared=$prepared played=$played isPlaying=${player.isPlaying()} " +
                            "playerVolume=${player.volume} released=$released ${IosAudioDiagnostics.sessionDetails()}"
                    }
                    played
                },
            )
            if (outcome == IosCuePlaybackOutcome.NO_PLAYER || outcome == IosCuePlaybackOutcome.RETRY_NO_PLAYER) {
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "player_missing", warning = true) {
                    "cue=${event::class.simpleName} outcome=$outcome released=$released ${IosAudioDiagnostics.sessionDetails()}"
                }
            }
        } catch (e: Exception) {
            IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "player_exception", warning = true) { "cue=${event::class.simpleName}" }
            log.w { "Sound playback failed for $event: ${e.message}" }
        }
    }

    fun release() {
        IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "manager_release")
        // Issue #522: Prevent late-arriving foreground / interruption
        // notifications from re-activating the session after we have already
        // torn it down, and remove the observers we installed in init.
        released = true
        recoveryPolicy.release()
        lifecycleObservers.forEach { observer ->
            try {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        lifecycleObservers.clear()

        stopAndClearPlayers()

        try {
            // F063: AVAudioSession is process-wide. If SafeWordListener currently
            // holds it in playAndRecord for emergency voice detection, deactivating
            // here would break the microphone. Only deactivate when we are not
            // stepping on an active record session (mirrors the setup-time guard).
            val session = AVAudioSession.sharedInstance()
            if (session.category != AVAudioSessionCategoryPlayAndRecord) {
                IosAudioDiagnostics.nativeResult(AUDIO_DIAGNOSTIC_TAG, "deactivate") { error ->
                    session.setActive(
                        false,
                        AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                        error,
                    )
                }
            } else {
                IosAudioDiagnostics.record(AUDIO_DIAGNOSTIC_TAG, "deactivation_skipped_play_and_record")
                log.d { "Skipping audio-session deactivation: playAndRecord is active (safe-word listener owns it)" }
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    private fun stopAndClearPlayers() {
        players.values.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        players.clear()

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

        // Stop and clear badge/PR pools with the verbal encouragement pools.
        listOf(
            badgeSoundPlayers,
            prSoundPlayers,
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

            // Issue #1356: Auto-start readiness — one firm impact, deliberately distinct
            // from WORKOUT_START's medium start impact.
            is HapticEvent.AUTO_START_READY -> {
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
                generator.prepare()
                generator.impactOccurred()
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
