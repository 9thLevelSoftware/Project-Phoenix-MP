package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #522 source-guard tests for the iOS [HapticFeedbackEffect] lifecycle
 * fix. These tests are static source-grep guards: they ensure the iOS sound
 * manager wires up the foreground + AVAudioSession interruption observers
 * on init, removes them on release, and that the play path defensively
 * re-prepares the selected [AVAudioPlayer] before playback without
 * repeatedly reconfiguring the process-wide audio session.
 *
 * Issue #1357: focus loss arms a player-pool rebuild, which the UIKit-free
 * IosSoundRecoveryPolicy decides (behaviour is covered by its iosTest); these
 * guards lock the native wiring.
 */
class HapticFeedbackEffectIosLifecycleGuardTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/iosMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val iosHapticSource: File
        get() = File(
            projectRoot,
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt",
        )

    private val iosRecoveryPolicySource: File
        get() = File(
            projectRoot,
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/IosSoundRecoveryPolicy.kt",
        )

    @Test
    fun iosHapticFeedbackEffect_observesForegroundAndInterruptionNotifications() {
        val source = iosHapticSource.readText()

        assertTrue(
            source.contains("UIApplicationDidBecomeActiveNotification"),
            "iOS HapticFeedbackEffect must observe UIApplicationDidBecomeActiveNotification to recover after backgrounding.",
        )
        assertTrue(
            source.contains("AVAudioSessionInterruptionNotification"),
            "iOS HapticFeedbackEffect must observe AVAudioSessionInterruptionNotification to recover after calls/Siri.",
        )
        assertTrue(
            source.contains("AVAudioSessionInterruptionTypeEnded"),
            "iOS HapticFeedbackEffect must only react to the *ended* half of an AVAudioSession interruption.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_recoversAfterEveryEndedInterruption() {
        val source = iosHapticSource.readText()

        val interruptionObserverIndex = source.indexOf("AVAudioSessionInterruptionNotification")
        val endedCheckIndex = source.indexOf("typeValue == AVAudioSessionInterruptionTypeEnded", interruptionObserverIndex)
        val recoverCallIndex = source.indexOf("recoverAudioSession()", endedCheckIndex)

        assertTrue(
            interruptionObserverIndex >= 0,
            "iOS HapticFeedbackEffect must observe AVAudioSession interruption notifications.",
        )
        assertTrue(
            endedCheckIndex >= 0,
            "iOS HapticFeedbackEffect must branch on the ended interruption type.",
        )
        assertTrue(
            recoverCallIndex >= 0 && recoverCallIndex > endedCheckIndex,
            "Ended AVAudioSession interruptions must always re-activate and re-prepare haptic audio for future cues.",
        )
        assertTrue(
            !source.contains("AVAudioSessionInterruptionOptionShouldResume"),
            "Haptic audio recovery must not depend on ShouldResume, which only describes interrupted playback.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_installsObserversOnInit() {
        val source = iosHapticSource.readText()

        // The init block must call installLifecycleObservers() so the
        // observers are attached the first time HapticFeedbackEffect is
        // composed. This catches regressions where observers are wired up
        // lazily or from a Composable side-effect.
        val initBlockIndex = source.indexOf("init {")
        val installCallIndex = source.indexOf("installLifecycleObservers()", initBlockIndex)
        assertTrue(
            initBlockIndex >= 0,
            "iOS HapticFeedbackEffect must define an init block to wire up lifecycle observers.",
        )
        assertTrue(
            installCallIndex >= 0 && installCallIndex > initBlockIndex,
            "IosSoundManager.init must call installLifecycleObservers() so observers are attached on construction.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_removesObserversInRelease() {
        val source = iosHapticSource.readText()

        // release() must remove the observer tokens it installed in init and
        // set the `released` guard so late-arriving notifications are no-ops.
        val releaseIndex = source.indexOf("fun release()")
        val removedFlagIndex = source.indexOf("released = true", releaseIndex)
        val clearIndex = source.indexOf("lifecycleObservers.clear()", releaseIndex)

        assertTrue(releaseIndex >= 0, "IosSoundManager must define a release() function.")
        val releaseBody = source.substring(releaseIndex)
        val hasRemoveObserver = releaseBody.contains("removeObserver")
        val referencesObserverToken = releaseBody.contains("observer")
        assertTrue(
            removedFlagIndex >= 0 && removedFlagIndex > releaseIndex,
            "release() must set the released guard so late-arriving notifications do not re-activate the session.",
        )
        assertTrue(
            hasRemoveObserver && referencesObserverToken,
            "release() must remove installed lifecycle observer tokens.",
        )
        assertTrue(
            clearIndex >= 0 && clearIndex > releaseIndex,
            "release() must clear the lifecycleObservers list after detaching tokens.",
        )
        assertTrue(
            releaseBody.contains("recoveryPolicy.release()"),
            "release() must release the recovery policy so nothing arms or rebuilds after release (Issue #1357).",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_playSoundPreparesPlayerWithoutReconfiguringSession() {
        val source = iosHapticSource.readText()

        // The playSound() defensive path must call prepareToPlay() on the
        // selected AVAudioPlayer before play(), but must not repeatedly call
        // setupAudioSession() on the hot path. Reconfiguring the process-wide
        // AVAudioSession for every cue can downgrade SafeWordListener's
        // PlayAndRecord category during active workouts.
        val playSoundIndex = source.indexOf("fun playSound(event: HapticEvent)")
        val prepareCallIndex = source.indexOf("player.prepareToPlay()", playSoundIndex)
        val playCallIndex = source.indexOf("player.play()", playSoundIndex)

        assertTrue(playSoundIndex >= 0, "IosSoundManager must define playSound().")
        assertTrue(
            prepareCallIndex >= 0 && prepareCallIndex > playSoundIndex,
            "playSound() must call player.prepareToPlay() before play() so the cached player is reusable after backgrounding.",
        )
        assertTrue(
            prepareCallIndex < playCallIndex,
            "prepareToPlay() must run before play() so the player is fully primed for playback.",
        )
        val playSoundBody = source.substring(playSoundIndex, playCallIndex)
        assertTrue(
            !playSoundBody.contains("setupAudioSession()"),
            "playSound() must not call setupAudioSession() on every cue; lifecycle recovery owns session activation.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_preservesSafeWordPlayAndRecordCategory() {
        val source = iosHapticSource.readText()

        assertTrue(
            source.contains("AVAudioSessionCategoryPlayAndRecord"),
            "Haptic audio recovery must know about SafeWordListener's PlayAndRecord category.",
        )
        assertTrue(
            source.contains("session.category != AVAudioSessionCategoryPlayAndRecord"),
            "Haptic audio recovery must not downgrade an active SafeWordListener PlayAndRecord session to Ambient.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_recoveryRebuildsArmedPoolAndOtherwiseRepreparesAllCachedPlayers() {
        val source = iosHapticSource.readText()

        // Issue #1357: re-preparing the cached players alone left a pool the
        // system invalidated while backgrounded silent until the app was
        // restarted. Lifecycle recovery goes through the recovery policy:
        // when focus loss armed a rebuild, its host rebuilds the pool through
        // the media-reset path; otherwise it re-primes every cached player.
        val recoverIndex = source.indexOf("private fun recoverAudioSession()")
        assertTrue(
            recoverIndex >= 0,
            "IosSoundManager must define a recoverAudioSession() helper for lifecycle recovery.",
        )
        val recoverBody = source.substring(recoverIndex, source.indexOf("\n    }", recoverIndex))
        assertTrue(
            "if (released) return" in recoverBody && "recoveryPolicy.recover(" in recoverBody,
            "recoverAudioSession() must short-circuit after release and delegate to the recovery policy.",
        )

        val rebuildHostIndex = source.indexOf("override fun rebuildPlayers(reason: String)")
        assertTrue(rebuildHostIndex >= 0, "The recovery host must implement rebuildPlayers().")
        val rebuildHostBody = source.substring(rebuildHostIndex, source.indexOf("\n            }", rebuildHostIndex))
        assertTrue(
            rebuildHostBody.indexOf("stopAndClearPlayers()") in 0 until rebuildHostBody.indexOf("loadAllSounds()"),
            "An armed recovery must rebuild the pool with stopAndClearPlayers() then loadAllSounds(), not re-prepare stale players.",
        )
        assertTrue(
            "\"players_rebuilt\"" in rebuildHostBody && "reason=\$reason" in rebuildHostBody,
            "A pool rebuild must record a players_rebuilt diagnostic with its arming reason.",
        )
        assertTrue(
            source.contains("override fun reprepareCachedPlayers() = prepareAllPlayers()"),
            "An unarmed recovery must still re-prime every cached player through prepareAllPlayers().",
        )

        // The re-prime path must prepare every cached player list (event map,
        // badge sounds, PR sounds, rep count sounds, the countdown tick
        // player, and the four verbal-encouragement pools). This guards
        // against a regression where only a subset of players is re-primed.
        val helperIndex = source.indexOf("private fun prepareAllPlayers()")
        assertTrue(helperIndex >= 0, "IosSoundManager must define prepareAllPlayers().")
        val helperBody = source.substring(helperIndex)
        assertTrue(
            "players.values.forEach" in helperBody,
            "prepareAllPlayers() must iterate the event-keyed player map.",
        )
        assertTrue(
            "badgeSoundPlayers.forEach" in helperBody,
            "prepareAllPlayers() must iterate the badge celebration players.",
        )
        assertTrue(
            "prSoundPlayers.forEach" in helperBody,
            "prepareAllPlayers() must iterate the PR celebration players.",
        )
        assertTrue(
            "repCountSoundPlayers.forEach" in helperBody,
            "prepareAllPlayers() must iterate the rep-count players.",
        )
        assertTrue(
            "countdownTickPlayer?.prepareToPlay()" in helperBody,
            "prepareAllPlayers() must re-prepare the countdown tick player.",
        )
        assertTrue(
            "encouragementNeutralSoundPlayers.forEach { it?.prepareToPlay() }" in helperBody,
            "prepareAllPlayers() must iterate the neutral encouragement players.",
        )
        assertTrue(
            "encouragementMildSoundPlayers.forEach { it?.prepareToPlay() }" in helperBody,
            "prepareAllPlayers() must iterate the mild encouragement players.",
        )
        assertTrue(
            "encouragementStrongSoundPlayers.forEach { it?.prepareToPlay() }" in helperBody,
            "prepareAllPlayers() must iterate the strong encouragement players.",
        )
        assertTrue(
            "encouragementDominatrixSoundPlayers.forEach { it?.prepareToPlay() }" in helperBody,
            "prepareAllPlayers() must iterate the dominatrix encouragement players.",
        )
    }

    // ---- Issue #1357: recovery must notice failure, rebuild and retry ----

    @Test
    fun iosHapticFeedbackEffect_readsSessionActivationResult() {
        val source = iosHapticSource.readText()

        // Kotlin/Native never throws for a failed NSError** call, so passing
        // null and ignoring the Boolean made failed reactivation invisible.
        assertTrue(
            !source.contains("setActive(true, null)"),
            "Audio session activation must pass an NSError pointer and read the result.",
        )
        assertTrue(
            source.contains("recoveryPolicy.onInitialActivation(setupAudioSession())") &&
                source.contains("override fun activateSession(): Boolean = setupAudioSession()"),
            "Activation results must reach the recovery policy so a failed activation stays flagged for the next cue.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_flagsRecoveryWhenInterruptionBegins() {
        val source = iosHapticSource.readText()

        val beganIndex = source.indexOf("typeValue == AVAudioSessionInterruptionTypeBegan")
        val flagIndex = source.indexOf("recoveryPolicy.onInterruptionBegan()", beganIndex)
        assertTrue(beganIndex >= 0, "iOS does not guarantee an ended interruption, so began must be handled.")
        assertTrue(flagIndex > beganIndex, "A began interruption must arm session recovery and a pool rebuild.")
    }

    @Test
    fun iosHapticFeedbackEffect_backgroundTransitionArmsPoolRebuild() {
        val source = iosHapticSource.readText()

        val observerIndex = source.indexOf("name = UIApplicationDidEnterBackgroundNotification")
        assertTrue(observerIndex >= 0, "DidEnterBackground needs its own observer, not only a diagnostic entry.")
        val armIndex = source.indexOf("if (!released) recoveryPolicy.onEnteredBackground()", observerIndex)
        assertTrue(
            armIndex > observerIndex,
            "Entering the background must arm a pool rebuild (short-circuited after release).",
        )
        assertTrue(
            source.contains("lifecycleObservers += backgroundObserver"),
            "The background observer must be tracked so release() removes it.",
        )
        assertTrue(
            !source.contains("UIApplicationDidEnterBackgroundNotification to \"app_background\""),
            "DidEnterBackground must not stay a diagnostic-only observer.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_recoversBeforeSelectingAndRetriesOnReselectedPlayer() {
        val source = iosHapticSource.readText()

        val playSoundIndex = source.indexOf("fun playSound(event: HapticEvent)")
        val selectIndex = source.indexOf("private fun selectPlayer(event: HapticEvent)", playSoundIndex)
        assertTrue(playSoundIndex >= 0 && selectIndex > playSoundIndex)
        val playSoundBody = source.substring(playSoundIndex, selectIndex)
        assertTrue(
            playSoundBody.contains("recoveryPolicy.playCue<AVAudioPlayer>("),
            "playSound() must route every cue through the recovery policy.",
        )
        assertTrue(
            playSoundBody.contains("selectPlayer = { selectPlayer(event) }"),
            "The policy must select the player itself so it can re-select after a rebuild.",
        )
        assertTrue(
            !playSoundBody.contains("val player = when (event)"),
            "playSound() must not capture a player before flagged recovery runs; a rebuild would discard it.",
        )
        assertTrue(
            playSoundBody.contains("val played = player.play()"),
            "playSound() must check play()'s result instead of assuming the cue played.",
        )

        val policy = iosRecoveryPolicySource.readText()
        val playCueIndex = policy.indexOf("fun <P : Any> playCue(")
        assertTrue(playCueIndex >= 0, "IosSoundRecoveryPolicy must define playCue().")
        val playCue = policy.substring(playCueIndex)
        val flaggedRecovery = playCue.indexOf("recover(appActive = true, trigger = \"flagged_cue\")")
        val firstSelect = playCue.indexOf("val player = selectPlayer()")
        val refusedRecovery = playCue.indexOf("recover(appActive = true, trigger = \"play_refused\")")
        val retrySelect = playCue.indexOf("val retryPlayer = selectPlayer()")
        assertTrue(
            flaggedRecovery in 0 until firstSelect,
            "Flagged recovery must run before the player is selected.",
        )
        assertTrue(
            refusedRecovery in firstSelect until retrySelect,
            "A refused play() must recover, then re-select from the rebuilt pool before its single retry.",
        )
        assertTrue(
            playCue.contains("play(retryPlayer, true)") && !playCue.contains("play(player, true)"),
            "The retry must play the re-selected instance, never the one that refused.",
        )
    }

    @Test
    fun iosSoundRecoveryPolicy_staysFreeOfUIKitAndAVFAudio() {
        val policy = iosRecoveryPolicySource.readText()

        assertTrue(
            !policy.contains("import platform."),
            "IosSoundRecoveryPolicy must stay free of UIKit/AVFAudio so iosTest can exercise it with fakes.",
        )
        assertTrue(policy.contains("internal class IosSoundRecoveryPolicy("))
    }

    @Test
    fun iosApp_declaresNoAudioBackgroundMode() {
        val plist = File(projectRoot, "iosApp/PhoenixApp/PhoenixApp/Info.plist").readText()

        val keyIndex = plist.indexOf("<key>UIBackgroundModes</key>")
        assertTrue(keyIndex >= 0, "Info.plist must declare UIBackgroundModes.")
        val modes = plist.substring(keyIndex, plist.indexOf("</array>", keyIndex))
        assertTrue("<string>bluetooth-central</string>" in modes)
        assertTrue(
            "<string>audio</string>" !in modes,
            "Issue #1357 recovers cues on return to the foreground; it must not add the audio background mode.",
        )
    }

    @Test
    fun iosHapticFeedbackEffect_rebuildsPlayersAfterMediaServicesReset() {
        val source = iosHapticSource.readText()

        assertTrue(
            source.contains("AVAudioSessionMediaServicesWereResetNotification"),
            "Players must be rebuilt after a media services reset; prepareToPlay() cannot revive them.",
        )
        val rebuildIndex = source.indexOf("private fun rebuildAfterMediaServicesReset()")
        assertTrue(rebuildIndex >= 0, "IosSoundManager must define rebuildAfterMediaServicesReset().")
        val rebuildBody = source.substring(rebuildIndex, source.indexOf("\n    }", rebuildIndex))
        assertTrue("stopAndClearPlayers()" in rebuildBody && "loadAllSounds()" in rebuildBody)
        assertTrue(
            "recoveryPolicy.onPoolRebuiltExternally(activated)" in rebuildBody,
            "A media-reset rebuild must consume any pending rebuild so the pool is not reloaded twice.",
        )
    }
}
