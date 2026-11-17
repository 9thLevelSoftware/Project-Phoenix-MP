package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #1357: AC1-AC4a for iOS cue recovery after focus loss, exercised
 * against the pure policy with a fake pool whose instances carry the
 * generation (number of builds) that produced them.
 */
class IosSoundRecoveryPolicyTest {

    private class FakePlayer(val generation: Int)

    private class FakeHost : IosSoundRecoveryHost {
        var activationResults = ArrayDeque<Boolean>()
        var activations = 0
        var rebuilds = 0
        var reprepares = 0
        val rebuildReasons = mutableListOf<String>()
        val recoveries = mutableListOf<Pair<String, IosSoundRecoveryOutcome>>()
        var generation = 0

        override fun activateSession(): Boolean {
            activations++
            return activationResults.removeFirstOrNull() ?: true
        }

        override fun rebuildPlayers(reason: String) {
            rebuilds++
            generation++
            rebuildReasons += reason
        }

        override fun reprepareCachedPlayers() {
            reprepares++
        }

        override fun recordRecovery(trigger: String, outcome: IosSoundRecoveryOutcome) {
            recoveries += trigger to outcome
        }
    }

    private val host = FakeHost()
    private val policy = IosSoundRecoveryPolicy(host).apply { onInitialActivation(true) }
    private val playedOn = mutableListOf<Pair<FakePlayer, Boolean>>()

    private fun cue(
        appActive: Boolean = true,
        results: List<Boolean> = listOf(true),
    ): IosCuePlaybackOutcome {
        val queue = ArrayDeque(results)
        return policy.playCue(
            appActive = { appActive },
            selectPlayer = { FakePlayer(host.generation) },
            play = { player, isRetry ->
                playedOn += player to isRetry
                queue.removeFirstOrNull() ?: true
            },
        )
    }

    @Test
    fun ac1_backgroundThenActive_nextCuePlaysOnNewlyBuiltPlayer() {
        assertEquals(IosCuePlaybackOutcome.PLAYED, cue())
        val before = playedOn.last().first.generation

        policy.onEnteredBackground()
        assertEquals(IosSoundRecoveryOutcome.REBUILT, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(IosCuePlaybackOutcome.PLAYED, cue())

        assertEquals(1, host.rebuilds)
        assertEquals(listOf("app_background"), host.rebuildReasons)
        assertNotEquals(before, playedOn.last().first.generation)
        assertFalse(policy.needsRecovery())
    }

    @Test
    fun ac2_interruptionBeganWithoutEnded_nextForegroundCueActivatesAndRebuildsBeforePlaying() {
        policy.onInterruptionBegan()
        assertTrue(policy.sessionNeedsRecovery)
        assertTrue(policy.playersNeedRebuild)

        assertEquals(IosCuePlaybackOutcome.PLAYED, cue())

        assertEquals(1, host.activations)
        assertEquals(1, host.rebuilds)
        assertEquals(listOf("flagged_cue" to IosSoundRecoveryOutcome.REBUILT), host.recoveries)
        // Player selected after the rebuild, never one captured before it.
        assertEquals(1, playedOn.single().first.generation)
    }

    @Test
    fun ac3_refusedPlay_oneRecoveryAndOneRetryOnRebuiltPlayer() {
        assertEquals(IosCuePlaybackOutcome.RETRY_PLAYED, cue(results = listOf(false, true)))

        assertEquals(1, host.rebuilds)
        assertEquals(listOf("play_refused"), host.rebuildReasons)
        assertEquals(2, playedOn.size)
        assertEquals(0 to false, playedOn[0].first.generation to playedOn[0].second)
        // The retry uses an instance selected from the rebuilt pool.
        assertEquals(1 to true, playedOn[1].first.generation to playedOn[1].second)
        assertFalse(policy.needsRecovery())
    }

    @Test
    fun ac3_secondFailureLeavesFlagsForNextCueWithoutLooping() {
        assertEquals(IosCuePlaybackOutcome.RETRY_FAILED, cue(results = listOf(false, false)))

        assertEquals(2, playedOn.size)
        assertEquals(1, host.rebuilds)
        assertTrue(policy.sessionNeedsRecovery)
        assertFalse(policy.playersNeedRebuild)
    }

    @Test
    fun ac4_whileNotActive_noActivationRebuildOrRetry_andFlagHandledOnForeground() {
        assertEquals(IosCuePlaybackOutcome.PLAYED, cue(appActive = false))
        assertEquals(IosCuePlaybackOutcome.FAILED_NO_RETRY, cue(appActive = false, results = listOf(false)))
        assertEquals(IosSoundRecoveryOutcome.SKIPPED_INACTIVE, policy.recover(appActive = false, trigger = "interruption_ended"))

        assertEquals(0, host.activations)
        assertEquals(0, host.rebuilds)
        assertEquals(2, playedOn.size)
        assertTrue(playedOn.none { it.second })
        assertTrue(policy.playersNeedRebuild)

        assertEquals(IosSoundRecoveryOutcome.REBUILT, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(1, host.rebuilds)
    }

    @Test
    fun ac4a_consecutiveRefusedCues_rebuildAtMostOncePerArmingEvent() {
        repeat(5) { cue(results = listOf(false, false)) }

        assertEquals(1, host.rebuilds)
        // Later failures still reactivate the session and retry once per cue.
        assertEquals(10, playedOn.size)
        assertEquals(5, playedOn.count { it.second })

        // A successful cue ends the failure run; the next refusal is a new arming event.
        cue(results = listOf(true))
        cue(results = listOf(false, true))
        assertEquals(2, host.rebuilds)
    }

    @Test
    fun ac4a_lifecycleArmingStillRebuildsDuringAFailureRun() {
        cue(results = listOf(false, false))
        assertEquals(1, host.rebuilds)

        policy.onEnteredBackground()
        policy.recover(appActive = true, trigger = "app_active")
        assertEquals(2, host.rebuilds)
        assertEquals(listOf("play_refused", "app_background"), host.rebuildReasons)
    }

    @Test
    fun failedActivation_keepsBothFlagsAndDoesNotRebuild() {
        policy.onEnteredBackground()
        host.activationResults = ArrayDeque(listOf(false))

        assertEquals(IosSoundRecoveryOutcome.ACTIVATION_FAILED, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(0, host.rebuilds)
        assertTrue(policy.sessionNeedsRecovery)
        assertTrue(policy.playersNeedRebuild)

        // The next foreground cue retries activation, then rebuilds.
        assertEquals(IosCuePlaybackOutcome.PLAYED, cue())
        assertEquals(1, host.rebuilds)
        assertFalse(policy.needsRecovery())
    }

    @Test
    fun unflaggedRecovery_reprimesCachedPoolWithoutRebuilding() {
        assertEquals(IosSoundRecoveryOutcome.REPREPARED, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(1, host.reprepares)
        assertEquals(0, host.rebuilds)
    }

    @Test
    fun unflaggedForegroundCue_doesNotTouchTheSession() {
        cue()
        assertEquals(0, host.activations)
        assertTrue(host.recoveries.isEmpty())
    }

    @Test
    fun ac5_nothingRebuildsOrArmsAfterRelease() {
        policy.release()
        policy.onEnteredBackground()
        policy.onInterruptionBegan()

        assertEquals(IosSoundRecoveryOutcome.SKIPPED_RELEASED, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(IosCuePlaybackOutcome.FAILED_NO_RETRY, cue(results = listOf(false)))
        assertEquals(0, host.activations)
        assertEquals(0, host.rebuilds)
        assertEquals(1, playedOn.size)
    }

    @Test
    fun foregroundMediaResetRebuild_consumesPendingRebuild() {
        policy.onEnteredBackground()
        policy.onPoolRebuiltExternally(activated = true, appActive = true)

        assertFalse(policy.needsRecovery())
        assertEquals(IosSoundRecoveryOutcome.REPREPARED, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(0, host.rebuilds)
    }

    @Test
    fun backgroundMediaResetRebuild_keepsPendingRebuild() {
        policy.onEnteredBackground()
        policy.onPoolRebuiltExternally(activated = true, appActive = false)

        assertTrue(policy.playersNeedRebuild)
        assertTrue(policy.sessionNeedsRecovery)
        assertEquals(IosSoundRecoveryOutcome.REBUILT, policy.recover(appActive = true, trigger = "app_active"))
    }

    @Test
    fun mediaResetRebuildWithFailedActivation_keepsPendingRebuild() {
        policy.onEnteredBackground()
        policy.onPoolRebuiltExternally(activated = false, appActive = true)

        assertTrue(policy.playersNeedRebuild)
        assertTrue(policy.sessionNeedsRecovery)
        assertEquals(IosSoundRecoveryOutcome.REBUILT, policy.recover(appActive = true, trigger = "app_active"))
    }

    @Test
    fun backgroundRefusal_doesNotConsumeForegroundFailureArming() {
        assertEquals(IosCuePlaybackOutcome.FAILED_NO_RETRY, cue(appActive = false, results = listOf(false)))
        assertEquals(IosSoundRecoveryOutcome.REBUILT, policy.recover(appActive = true, trigger = "app_active"))
        assertEquals(1, host.rebuilds)

        // First foreground refusal after returning still arms its own rebuild (AC3).
        assertEquals(IosCuePlaybackOutcome.RETRY_PLAYED, cue(results = listOf(false, true)))
        assertEquals(2, host.rebuilds)
        assertEquals(listOf("background_cue", "play_refused"), host.rebuildReasons)
    }

    @Test
    fun rebuildReason_keepsFirstArmingEventSinceLastRebuild() {
        policy.onEnteredBackground()
        policy.onInterruptionBegan()
        cue(appActive = false)
        policy.recover(appActive = true, trigger = "app_active")

        assertEquals(listOf("app_background"), host.rebuildReasons)
    }
}
