package com.devil.phoenixproject.presentation.components

/**
 * Issue #1357: native side of iOS workout-sound recovery. [IosSoundManager]
 * implements this and keeps every UIKit/AVFAudio call.
 */
internal interface IosSoundRecoveryHost {
    /** Re-applies the category guard and activates the shared session. True when active. */
    fun activateSession(): Boolean

    /**
     * Full pool rebuild through the existing `stopAndClearPlayers()` +
     * `loadAllSounds()` path (the same path the media-services-reset rebuild
     * uses). [reason] names the arming event for diagnostics.
     */
    fun rebuildPlayers(reason: String)

    /** Re-primes the cached pool in place (`prepareToPlay()` on every player). */
    fun reprepareCachedPlayers()

    /** Diagnostic hook for every recovery pass. */
    fun recordRecovery(trigger: String, outcome: IosSoundRecoveryOutcome)
}

internal enum class IosSoundRecoveryOutcome {
    /** Session activated and the player pool was rebuilt. */
    REBUILT,

    /** Session activated and the cached pool was re-primed in place. */
    REPREPARED,

    /** Activation failed; flags stay set so the next foreground cue retries. */
    ACTIVATION_FAILED,

    /** App not active: nothing runs while backgrounded (no audio background mode). */
    SKIPPED_INACTIVE,

    /** The manager was released; nothing runs after release. */
    SKIPPED_RELEASED,
}

internal enum class IosCuePlaybackOutcome {
    PLAYED,
    NO_PLAYER,
    FAILED_NO_RETRY,
    RETRY_PLAYED,
    RETRY_FAILED,
    RETRY_NO_PLAYER,
}

/**
 * Issue #1357: pure (UIKit/AVFAudio-free) decision logic for iOS cue
 * recovery after the app loses focus.
 *
 * Focus-loss recovery used to reactivate the session and re-prepare the
 * cached `AVAudioPlayer` pool, but never rebuilt it, so a pool invalidated
 * while the app was backgrounded stayed silent until a process restart.
 *
 * Arming events set [playersNeedRebuild]: a background transition, an
 * interruption Began, a cue that arrives while the app is not active, and the
 * first `play() == false` of a failure run. The next foreground recovery
 * activates the session first and rebuilds the pool only if activation
 * succeeded.
 *
 * Rebuild cap: one rebuild per arming event. Consecutive `play() == false`
 * results do not re-arm until a cue plays successfully, so a persistent
 * failure only reactivates the session and retries; it never reloads the
 * ~124-player pool on every cue.
 */
internal class IosSoundRecoveryPolicy(private val host: IosSoundRecoveryHost) {
    var sessionNeedsRecovery: Boolean = false
        private set

    var playersNeedRebuild: Boolean = false
        private set

    var released: Boolean = false
        private set

    private var rebuildReason: String? = null

    // True from the first refused play() until a cue plays again.
    private var playFailureRunArmed = false

    fun onInitialActivation(activated: Boolean) {
        sessionNeedsRecovery = !activated
    }

    fun onEnteredBackground() = arm("app_background")

    fun onInterruptionBegan() = arm("interruption_began")

    fun onCueWhileNotActive() = arm("background_cue")

    fun needsRecovery(): Boolean = sessionNeedsRecovery || playersNeedRebuild

    /**
     * One recovery pass (DidBecomeActive, interruption Ended, or a flagged
     * foreground cue). Activation first; rebuild only after it succeeded.
     */
    fun recover(appActive: Boolean, trigger: String): IosSoundRecoveryOutcome {
        val outcome = when {
            released -> IosSoundRecoveryOutcome.SKIPPED_RELEASED
            !appActive -> IosSoundRecoveryOutcome.SKIPPED_INACTIVE
            !host.activateSession() -> {
                sessionNeedsRecovery = true
                IosSoundRecoveryOutcome.ACTIVATION_FAILED
            }
            playersNeedRebuild -> {
                // Clear before rebuilding so a throwing loader cannot cause a rebuild loop.
                val reason = rebuildReason ?: "unspecified"
                sessionNeedsRecovery = false
                playersNeedRebuild = false
                rebuildReason = null
                host.rebuildPlayers(reason)
                IosSoundRecoveryOutcome.REBUILT
            }
            else -> {
                sessionNeedsRecovery = false
                host.reprepareCachedPlayers()
                IosSoundRecoveryOutcome.REPREPARED
            }
        }
        host.recordRecovery(trigger, outcome)
        return outcome
    }

    /**
     * Plays one cue. Flagged recovery runs BEFORE the player is selected,
     * because a rebuild discards the cached instances. A refused play() gets
     * at most one recovery and one retry, on a player re-selected after that
     * recovery, so an instance obtained before a rebuild is never played.
     */
    fun <P : Any> playCue(
        appActive: () -> Boolean,
        selectPlayer: () -> P?,
        play: (player: P, isRetry: Boolean) -> Boolean,
    ): IosCuePlaybackOutcome {
        if (!appActive()) {
            onCueWhileNotActive()
        } else if (needsRecovery()) {
            recover(appActive = true, trigger = "flagged_cue")
        }

        val player = selectPlayer() ?: return IosCuePlaybackOutcome.NO_PLAYER
        if (play(player, false)) {
            onPlaySucceeded()
            return IosCuePlaybackOutcome.PLAYED
        }

        if (released || !appActive()) {
            // A background refusal is expected (no audio background mode) and
            // the cue already armed a rebuild; it must not consume the
            // foreground failure-run arming.
            sessionNeedsRecovery = true
            return IosCuePlaybackOutcome.FAILED_NO_RETRY
        }
        onForegroundPlayFailed()

        recover(appActive = true, trigger = "play_refused")
        val retryPlayer = selectPlayer() ?: return IosCuePlaybackOutcome.RETRY_NO_PLAYER
        return if (play(retryPlayer, true)) {
            onPlaySucceeded()
            IosCuePlaybackOutcome.RETRY_PLAYED
        } else {
            onForegroundPlayFailed()
            IosCuePlaybackOutcome.RETRY_FAILED
        }
    }

    /**
     * The pool was rebuilt outside [recover] (media-services reset). It only
     * satisfies a pending rebuild when it was built in the foreground on an
     * activated session; a pool built in the background or on a refused
     * activation is as suspect as the one it replaced.
     */
    fun onPoolRebuiltExternally(activated: Boolean, appActive: Boolean) {
        if (activated && appActive) {
            playersNeedRebuild = false
            rebuildReason = null
        }
        sessionNeedsRecovery = !activated || playersNeedRebuild
    }

    fun release() {
        released = true
    }

    private fun onForegroundPlayFailed() {
        sessionNeedsRecovery = true
        if (!playFailureRunArmed) {
            playFailureRunArmed = true
            arm("play_refused")
        }
    }

    private fun onPlaySucceeded() {
        playFailureRunArmed = false
    }

    private fun arm(reason: String) {
        if (released) return
        sessionNeedsRecovery = true
        playersNeedRebuild = true
        // Keep the first arming reason since the last rebuild for diagnostics.
        rebuildReason = rebuildReason ?: reason
    }
}
