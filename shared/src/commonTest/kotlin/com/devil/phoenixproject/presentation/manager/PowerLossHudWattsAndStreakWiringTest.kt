package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.ProtocolRepBounds
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #1392 — HUD watts/qualifying-streak wiring (ui-spec §4 SD6).
 *
 * The live HUD projection takes `Latest lifting power … W`, `Best: rep N · … W`
 * and `Qualifying attempts: 1 of 2` from the engine's [PowerLossHudState]; this
 * test pins the controller's hudState() to fill those facts from the reducer
 * state and attempt records — never fabricated, null when unavailable.
 */
class PowerLossHudWattsAndStreakWiringTest {

    private val config = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = 12,
    )

    private val plan: ExecutionTargetPlan = requireNotNull(
        ExecutionTargetPlan.derive(
            config = config,
            bounds = ProtocolRepBounds(
                maxRepresentableWorkingReps = 254,
                protocolIdentityFinite = true,
            ),
            warmupSetCount = 0,
            now = 0L,
        ),
    )

    private fun obs(ordinal: Int, mcp: Float?): PowerLossObservation = PowerLossObservation(
        workingConcentricOrdinal = ordinal,
        isWarmup = false,
        mcpWatts = mcp,
        romA = CableRom.trusted(100f),
        romB = CableRom.trusted(100f),
        activeCables = setOf(PowerCable.A, PowerCable.B),
    )

    @Test
    fun hudStateCarriesLatestAndBestWatts() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        controller.onObservation(config, plan, obs(2, mcp = 120f))
        controller.onObservation(config, plan, obs(3, mcp = null))

        val hud = controller.hudState(completedRepCount = 3, endingSet = false)
        assertEquals(120f, hud.lastMcpWatts, "latest valid sealed concentric MCP")
        assertEquals(120f, hud.bestMcpWatts, "reference (best) MCP at rep 2")
        assertEquals(2, hud.referenceWorkingOrdinal)
        assertNotNull(hud.invalidPowerReason, "attempt 3 is invalid power")
    }

    @Test
    fun hudStateKeepsWattsUnavailableWhenNoValidPower() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = null))

        val hud = controller.hudState(completedRepCount = 1, endingSet = false)
        assertNull(hud.lastMcpWatts, "no valid power yet — never fabricated")
        assertNull(hud.bestMcpWatts, "no reference yet — never fabricated")
    }

    @Test
    fun hudStateCarriesQualifyingStreakOnlyWhenEnabled() {
        val streakConfig = config.copy(requireTwoConsecutive = true)
        val streakPlan = requireNotNull(
            ExecutionTargetPlan.derive(
                config = streakConfig,
                bounds = ProtocolRepBounds(
                    maxRepresentableWorkingReps = 254,
                    protocolIdentityFinite = true,
                ),
                warmupSetCount = 0,
                now = 0L,
            ),
        )
        val controller = PowerLossSetController()
        controller.onObservation(streakConfig, streakPlan, obs(1, mcp = 100f))
        controller.onObservation(streakConfig, streakPlan, obs(2, mcp = 79f))

        val hud = controller.hudState(completedRepCount = 2, endingSet = false)
        assertEquals(1, hud.qualifyingCount, "21% loss qualifies at 1 of 2 without stopping")

        val controllerNoStreak = PowerLossSetController()
        controllerNoStreak.onObservation(config, plan, obs(1, mcp = 100f))
        controllerNoStreak.onObservation(config, plan, obs(2, mcp = 79f))
        val hudNoStreak = controllerNoStreak.hudState(completedRepCount = 2, endingSet = false)
        assertNull(hudNoStreak.qualifyingCount, "requirement off — no streak line")
    }
}
