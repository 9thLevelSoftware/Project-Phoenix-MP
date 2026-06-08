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
    fun iosHapticFeedbackEffect_recoveryPreparesAllCachedPlayers() {
        val source = iosHapticSource.readText()

        // The lifecycle-recovery path must prepare every cached player list
        // (event map, badge sounds, PR sounds, rep count sounds, and the
        // countdown tick player). This guards against a regression where only
        // a subset of players is re-primed.
        val recoverIndex = source.indexOf("private fun recoverAudioSession()")
        val prepareHelperIndex = source.indexOf("prepareAllPlayers()", recoverIndex)

        assertTrue(
            recoverIndex >= 0,
            "IosSoundManager must define a recoverAudioSession() helper for lifecycle recovery.",
        )
        assertTrue(
            prepareHelperIndex >= 0 && prepareHelperIndex > recoverIndex,
            "recoverAudioSession() must call prepareAllPlayers() so every cached player is re-primed.",
        )

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
    }
}
