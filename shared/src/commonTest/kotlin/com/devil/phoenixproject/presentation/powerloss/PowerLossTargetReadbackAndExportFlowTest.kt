package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — Just Lift / set-ready target readback facts (ui-spec §2–§3)
 * and the export-flow state machine (ui-spec §6): `Export power data` entry plus
 * the legacy-export omission disclosure before any legacy run.
 */
class PowerLossTargetReadbackAndExportFlowTest {

    private val powerConfig = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = 12,
    )

    // ── Readback facts (JustLiftScreen / SetReadyScreen) ───────────────────────

    @Test
    fun `readback shows SD6 saved facts and target lines for an active power target`() {
        val readback = powerLossTargetReadback(
            config = powerConfig,
            supportedExecution = true,
            savedNominalReps = 12,
        )
        assertTrue(readback.visible)
        assertEquals(
            listOf(
                "Power-loss target · 20%",
                "Max 20 working reps",
                "Saved reps: 12",
                "Power-loss target active",
            ),
            readback.lines,
        )
        assertEquals("Saved reps: 12", readback.savedRepsLine)
        assertEquals("Power-loss target active", readback.powerActiveBadge)
        assertNull(readback.dormantBanner)
        assertFalse(readback.targetLocked)
    }

    @Test
    fun `during-set readback is locked - active-set UI never edits the target`() {
        val readback = powerLossTargetReadback(
            config = powerConfig,
            supportedExecution = true,
            savedNominalReps = 12,
            locked = true,
        )
        assertEquals("Target locked for this set.", readback.targetLockedLine)
        assertTrue(readback.targetLocked)
        assertEquals("Target locked for this set.", readback.lines.first())
    }

    @Test
    fun `dormant power config shows the not-active banner and keeps the saved facts`() {
        val readback = powerLossTargetReadback(
            config = powerConfig,
            supportedExecution = false,
            savedNominalReps = 12,
        )
        assertTrue(readback.visible)
        assertEquals("Power-loss target is not active in this mode.", readback.dormantBanner)
        assertEquals(
            listOf(
                "Power-loss target is not active in this mode.",
                "Saved reps: 12",
            ),
            readback.lines,
        )
        assertNull(readback.powerActiveBadge)
    }

    @Test
    fun `plain rep sets render no readback card - REP regression unchanged`() {
        val readback = powerLossTargetReadback(
            config = PowerLossConfig.DEFAULT,
            supportedExecution = true,
            savedNominalReps = 12,
        )
        assertFalse(readback.visible)
        assertEquals(emptyList(), readback.lines)
    }

    @Test
    fun `missing saved nominal reps shows no saved reps line - never fabricated`() {
        val readback = powerLossTargetReadback(
            config = powerConfig.copy(savedNominalReps = null),
            supportedExecution = true,
            savedNominalReps = null,
        )
        assertTrue(readback.visible)
        assertNull(readback.savedRepsLine)
        assertFalse(readback.lines.any { it.startsWith("Saved reps:") })
    }

    // ── Execution support (inert on excluded paths) ───────────────────────────

    @Test
    fun `timed and bodyweight executions never support the power target`() {
        assertFalse(
            powerLossExecutionSupported(
                config = powerConfig,
                mode = ProgramMode.OldSchool,
                isTimed = true,
            ),
        )
        assertFalse(
            powerLossExecutionSupported(
                config = powerConfig,
                mode = ProgramMode.OldSchool,
                isBodyweight = true,
            ),
        )
    }

    @Test
    fun `just lift is a supported style for v1 modes only`() {
        assertTrue(
            powerLossExecutionSupported(
                config = powerConfig,
                mode = ProgramMode.OldSchool,
                isJustLiftExecutionStyle = true,
            ),
        )
        // Excluded modes stay excluded even in Just Lift style (spec §1/§7).
        assertFalse(
            powerLossExecutionSupported(
                config = powerConfig,
                mode = ProgramMode.TUT,
                isJustLiftExecutionStyle = true,
            ),
        )
        assertEquals(
            PowerLossProtocolFamily.UNSUPPORTED,
            PowerLossProtocolFamily.of(ProgramMode.TUT, isJustLiftExecutionStyle = true),
        )
        assertEquals(
            PowerLossProtocolFamily.JUST_LIFT,
            PowerLossProtocolFamily.of(ProgramMode.Echo, isJustLiftExecutionStyle = true),
        )
    }

    @Test
    fun `timed and bodyweight paths resolve the execution config to inert rep target`() {
        val inert = powerLossExecutionConfig(powerConfig, isTimed = true, isBodyweight = false)
        assertEquals(TargetMode.REP_TARGET, inert.targetMode)
        val inertBodyweight = powerLossExecutionConfig(powerConfig, isTimed = false, isBodyweight = true)
        assertEquals(TargetMode.REP_TARGET, inertBodyweight.targetMode)
        val carried = powerLossExecutionConfig(powerConfig, isTimed = false, isBodyweight = false)
        assertEquals(TargetMode.POWER, carried.targetMode)
    }

    // ── Export flow (ui-spec §6) ──────────────────────────────────────────────

    @Test
    fun `legacy export always surfaces the omission disclosure first`() {
        val flow = PowerLossExportFlowState.INITIAL
            .reduce(PowerLossExportIntent.RequestLegacyExport)
        assertTrue(flow.legacyConfirmationVisible)
        assertFalse(flow.legacyExportApproved)
        assertFalse(flow.powerExportRequested)
    }

    @Test
    fun `choose power-data export routes to the versioned power export`() {
        val flow = PowerLossExportFlowState.INITIAL
            .reduce(PowerLossExportIntent.RequestLegacyExport)
            .reduce(PowerLossExportIntent.ChoosePowerDataExport)
        assertFalse(flow.legacyConfirmationVisible)
        assertFalse(flow.legacyExportApproved)
        assertTrue(flow.powerExportRequested)
    }

    @Test
    fun `export without power details approves the disclosed legacy run`() {
        val flow = PowerLossExportFlowState.INITIAL
            .reduce(PowerLossExportIntent.RequestLegacyExport)
            .reduce(PowerLossExportIntent.ExportWithoutPowerDetails)
        assertFalse(flow.legacyConfirmationVisible)
        assertTrue(flow.legacyExportApproved)
        assertFalse(flow.powerExportRequested)
    }

    @Test
    fun `dismissing the disclosure runs nothing`() {
        val flow = PowerLossExportFlowState.INITIAL
            .reduce(PowerLossExportIntent.RequestLegacyExport)
            .reduce(PowerLossExportIntent.DismissLegacyConfirmation)
        assertFalse(flow.legacyConfirmationVisible)
        assertFalse(flow.legacyExportApproved)
        assertFalse(flow.powerExportRequested)
    }

    @Test
    fun `export entry and disclosure are the exact ui-spec strings`() {
        val export = PowerLossExportUi()
        assertEquals("Export power data", export.entryLabel)
        assertEquals(
            "This format does not include power-loss settings or per-attempt details. " +
                "Use power-data export to keep them.",
            export.legacyOmissionDisclosure,
        )
        assertEquals("Choose power-data export", export.choosePowerDataExportAction)
        assertEquals("Export without power details", export.exportWithoutPowerDetailsAction)
        assertTrue(
            export.localDataDisclosure.endsWith("they are not synced to the portal."),
            "Local-data disclosure must end with the portal deferral",
        )
    }
}
