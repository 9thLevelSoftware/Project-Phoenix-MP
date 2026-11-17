package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossHudState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — HUD pending-vs-completed identity, near-limit once-only state,
 * trusted short-ROM, invalid-power reasons, `Ending set…`, and SD6 readback
 * (`Power-loss target · 20%` / `Max 20 working reps` — never `9 / 12`).
 */
class PowerLossHudUiStateTest {

    private fun hud(
        attempt: Int = 9,
        completed: Int = 8,
        lastLoss: Float? = 16f,
        invalidReason: String? = null,
        lastValidAttempt: Int? = null,
        endingSet: Boolean = false,
        referenceEstablished: Boolean = true,
        nearLimitCueEmitted: Boolean = false,
        cableRom: Map<PowerCable, Float> = emptyMap(),
    ) = PowerLossHudState(
        powerActive = true,
        thresholdPercent = 20,
        nearLimitLossPercent = 15,
        maxWorkingReps = 20,
        savedNominalReps = 12,
        currentAttemptOrdinal = attempt,
        completedRepCount = completed,
        referenceEstablished = referenceEstablished,
        referenceWorkingOrdinal = 2,
        lastLossPercent = lastLoss,
        lastValidAttemptOrdinal = lastValidAttempt,
        invalidPowerReason = invalidReason,
        nearLimitCueEmitted = nearLimitCueEmitted,
        cableRomPercentOfReference = cableRom,
        endingSet = endingSet,
    )

    @Test
    fun `readback is the target and cap - never a nominal rep fraction`() {
        val ui = powerLossHudUi(hud(), latestPowerWatts = 252f, bestWatts = 300f)
        assertEquals("Power-loss target · 20%", ui.targetReadback)
        assertEquals("Max 20 working reps", ui.capReadback)
        assertTrue(ui.nominalRepFractionHidden, "Never `9 / 12` from nominal reps")
        assertEquals("Target 20% · cue from 15%", ui.thresholdLine)
    }

    @Test
    fun `pending attempt is never promoted to completed`() {
        val ui = powerLossHudUi(hud(attempt = 9, completed = 8))
        assertEquals("8 completed reps · attempt 9 not confirmed complete", ui.completedLine)
        assertTrue(ui.completedLine.startsWith("8 completed"), "Completed count stays authoritative (actualReps)")
        assertFalse(ui.completedLine.startsWith("9 completed"))
    }

    @Test
    fun `confirmed completed attempt renders the lifting-phase-complete line`() {
        val ui = powerLossHudUi(hud(attempt = 9, completed = 9))
        assertEquals("Working attempt 9 · lifting phase complete", ui.completedLine)
    }

    @Test
    fun `near limit state is once-only`() {
        val first = powerLossHudUi(hud(lastLoss = 16f, nearLimitCueEmitted = false))
        assertTrue(first.nearLimit)
        assertEquals("Near power-loss target", first.statusLine)
        assertFalse(first.nearLimitCueEmitted)

        val afterCue = powerLossHudUi(hud(lastLoss = 16f, nearLimitCueEmitted = true))
        assertTrue(afterCue.nearLimit, "Status may remain while the condition holds")
        assertTrue(afterCue.nearLimitCueEmitted, "No repeated cue")

        val below = powerLossHudUi(hud(lastLoss = 14f))
        assertFalse(below.nearLimit)
        assertEquals("Below target", below.statusLine)
    }

    @Test
    fun `warmup and no-baseline states show dashes and separate copy`() {
        val warmup = powerLossHudUi(hud(lastLoss = null), warmupActive = true)
        assertEquals("Warm-up — building movement reference", warmup.statusLine)
        assertEquals("Warm-ups do not set best power.", warmup.warmupNote)
        assertEquals("—", warmup.lossDisplay)

        val noBaseline = powerLossHudUi(hud(lastLoss = null, referenceEstablished = false))
        assertEquals("Waiting for valid lifting power", noBaseline.statusLine)
        assertEquals("—", noBaseline.bestLine.substringAfter("Best: rep "))
    }

    @Test
    fun `trusted short rom renders the cable fragment with missing power`() {
        val ui = powerLossHudUi(
            hud(
                lastLoss = null,
                invalidReason = "sample gap",
                cableRom = mapOf(PowerCable.A to 82f, PowerCable.B to 100f),
            ),
        )
        assertEquals("Short range of motion · Cable A 82% of reference", ui.shortRomLine)
        // Missing power is `—`, never a converted ROM deficit.
        assertEquals("—", ui.lossDisplay)
    }

    @Test
    fun `invalid power shows the reason and the last valid attempt separately`() {
        val ui = powerLossHudUi(
            hud(lastLoss = null, invalidReason = "stale data", lastValidAttempt = 6),
        )
        assertEquals("Power unavailable — stale data", ui.invalidPowerLine)
        assertEquals("Last valid · attempt 6", ui.lastValidLine)
        assertEquals("Max-reps limit and manual stop remain active.", ui.capAndStopActive)
        assertEquals("Power unavailable — stale data", ui.statusLine)
    }

    @Test
    fun `committed ending set is distinct from the completed transition`() {
        val ui = powerLossHudUi(hud(endingSet = true))
        assertEquals("Ending set…", ui.endingLine)
        assertEquals("Ending set…", ui.statusLine)
    }

    @Test
    fun `first valid attempt carries the monitoring note`() {
        val ui = powerLossHudUi(hud(attempt = 1, completed = 1, lastLoss = 0f))
        assertEquals("Monitoring starts with the second working attempt.", ui.statusLine)
        assertEquals("0%", ui.lossDisplay)
    }

    @Test
    fun `rep panel lines show watts and dashes for missing data`() {
        val ui = powerLossHudUi(hud(), latestPowerWatts = null, bestWatts = null)
        assertEquals("Latest lifting power —", ui.latestPowerLine)
        assertEquals("Best: rep —", ui.bestLine)

        val filled = powerLossHudUi(hud(), latestPowerWatts = 252f, bestWatts = 300f)
        assertEquals("Latest lifting power 252 W", filled.latestPowerLine)
        assertEquals("Best: rep 2 · 300 W", filled.bestLine)
    }

    @Test
    fun `rep set with power inactive renders nothing`() {
        val ui = powerLossHudUi(PowerLossHudState(powerActive = false))
        assertFalse(ui.visible)
    }

    @Test
    fun `locked note states the target is not editable from active-set UI`() {
        val ui = powerLossHudUi(hud())
        assertEquals("Target locked for this set. Edit before the next set.", ui.lockedNote)
        assertEquals("Target locked for this set.", PowerLossUiCopy.TARGET_LOCKED_SHORT)
    }
}
