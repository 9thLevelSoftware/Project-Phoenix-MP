package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — config draft/commit/cancel semantics, presets, validation copy,
 * mode-confirmation dialog, dormant/inert config and profile-default boundaries
 * (ui-spec §3 with SD6 corrections).
 */
class PowerLossConfigDraftTest {

    private fun draft(
        config: PowerLossConfig = PowerLossConfig.DEFAULT,
        maxAllowed: Int = 254,
        family: PowerLossProtocolFamily = PowerLossProtocolFamily.OLD_SCHOOL,
        validated: Boolean = true,
    ) = PowerLossConfigDraftState.seededFrom(
        config = config,
        maxAllowedWorkingReps = maxAllowed,
        protocolFamily = family,
        pairedSamplesValidated = validated,
    )

    @Test
    fun `presets carry nonbreaking numeric-unit labels - no 10 percent or Custom splits`() {
        val labels = ThresholdPreset.entries.map { it.nonbreakingLabel() }
        assertEquals(listOf("10\u2060%", "20\u2060%", "30\u2060%", "Cus\u2060tom"), labels)
        // The label glyphs stay `10%` / `Custom` while the word joiner blocks breaks.
        labels.forEach { label ->
            assertTrue(label.contains(PowerLossUiCopy.WJ), "Preset $label must block line breaks")
        }
        assertEquals("10%", ThresholdPreset.P10.nonbreakingLabel().replace(PowerLossUiCopy.WJ, ""))
        assertEquals("Custom", ThresholdPreset.CUSTOM.nonbreakingLabel().replace(PowerLossUiCopy.WJ, ""))
    }

    @Test
    fun `custom threshold validation keeps the draft and shows the exact error`() {
        val state = draft().selectPreset(ThresholdPreset.CUSTOM)
        for (bad in listOf("", "4", "41", "12.5", "abc")) {
            val next = state.updateCustomThreshold(bad)
            assertEquals(PowerLossUiCopy.CUSTOM_THRESHOLD_ERROR, next.customThresholdError, "input=$bad")
            // Draft (last valid) is kept while invalid.
            assertEquals(20, next.draft.thresholdPercent)
        }
        val ok = state.updateCustomThreshold("35")
        assertNull(ok.customThresholdError)
        assertEquals(35, ok.draft.thresholdPercent)
    }

    @Test
    fun `preset tap replaces an invalid custom draft and clears the error`() {
        val state = draft().selectPreset(ThresholdPreset.CUSTOM).updateCustomThreshold("99")
        assertNotNull(state.customThresholdError)
        val next = state.selectPreset(ThresholdPreset.P30)
        assertNull(next.customThresholdError)
        assertEquals(30, next.draft.thresholdPercent)
    }

    @Test
    fun `persistent caution at 36-40 - none at 35`() {
        val base = draft().selectPreset(ThresholdPreset.CUSTOM)
        assertTrue(base.updateCustomThreshold("36").highThresholdCautionVisible)
        assertTrue(base.updateCustomThreshold("40").highThresholdCautionVisible)
        assertFalse(base.updateCustomThreshold("35").highThresholdCautionVisible)
        assertEquals(PowerLossUiCopy.HIGH_THRESHOLD_TITLE, PowerLossUiCopy.HIGH_THRESHOLD_TITLE)
        // Caution never disables Apply.
        assertTrue(base.updateCustomThreshold("38").canApply)
    }

    @Test
    fun `max working reps uses the dynamic bound helper copy`() {
        val state = draft(maxAllowed = 30)
        assertNull(state.maxRepsError)
        assertEquals("Enter 2–30 working reps.", PowerLossUiCopy.maxWorkingRepsError(30))
        val invalid = state.updateMaxReps("31")
        assertEquals("Enter 2–30 working reps.", invalid.maxRepsError)
        val tooSmall = state.updateMaxReps("1")
        assertEquals("Enter 2–30 working reps.", tooSmall.maxRepsError)
        assertNull(state.updateMaxReps("2").maxRepsError)
        assertNull(state.updateMaxReps("30").maxRepsError)
        // Impossible plan (bound below the minimum) still validates against the bound.
        val impossible = draft(maxAllowed = 1).updateMaxReps("2")
        assertEquals("Enter 2–1 working reps.", impossible.maxRepsError)
    }

    @Test
    fun `two-consecutive helper copy is SD1-corrected`() {
        assertEquals(
            "One qualifying working rep can end the set, starting with the second working attempt.",
            PowerLossUiCopy.TWO_CONSECUTIVE_OFF_HELPER,
        )
        val on = PowerLossUiCopy.TWO_CONSECUTIVE_ON_HELPER
        assertTrue(on.contains("neither qualifying reason"), "SD1: unavailable attempt = neither qualifying reason")
        assertFalse(on.contains("null-MCP"), "SD1: never 'any null-MCP observation'")
        assertTrue(on.contains("resets the count"))
    }

    @Test
    fun `near-limit cue readback floors at first positive power loss`() {
        assertEquals("Near-limit cue: first positive power loss", PowerLossUiCopy.nearLimitCueReadback(5))
        assertEquals("Near-limit cue: 15% loss", PowerLossUiCopy.nearLimitCueReadback(20))
        assertEquals("Near-limit cue: 25% loss", PowerLossUiCopy.nearLimitCueReadback(30))
    }

    @Test
    fun `apply commits atomically - cancel restores the original`() {
        val original = PowerLossConfig(
            targetMode = TargetMode.POWER,
            thresholdPercent = 20,
            maxWorkingReps = 20,
            requireTwoConsecutive = false,
            savedNominalReps = 12,
        )
        val edited = draft(original)
            .selectPreset(ThresholdPreset.P30)
            .setRequireTwoConsecutive(true)
        assertEquals(original, edited.committed, "Nothing commits before Apply")
        val applied = edited.apply()
        assertEquals(30, applied.committed.thresholdPercent)
        assertTrue(applied.committed.requireTwoConsecutive)
        assertEquals(12, applied.committed.savedNominalReps, "Saved nominal reps survive edits")

        val cancelled = edited.cancel()
        assertEquals(original, cancelled.draft, "Cancel restores the original target/settings")
    }

    @Test
    fun `apply refuses while a field is invalid`() {
        val state = draft().selectPreset(ThresholdPreset.CUSTOM).updateCustomThreshold("41")
        val applied = state.apply()
        assertEquals(PowerLossConfig.DEFAULT.thresholdPercent, applied.committed.thresholdPercent)
        assertFalse(applied.canApply)
    }

    @Test
    fun `toggling REP to POWER preserves dormant values and nominal reps`() {
        val state = draft(
            PowerLossConfig(
                targetMode = TargetMode.REP_TARGET,
                thresholdPercent = 30,
                maxWorkingReps = 15,
                savedNominalReps = 12,
            ),
        ).selectTargetMode(TargetMode.POWER)
        assertEquals(TargetMode.POWER, state.draft.targetMode)
        assertEquals(30, state.draft.thresholdPercent)
        assertEquals(15, state.draft.maxWorkingReps)
        assertEquals(12, state.draft.savedNominalReps)
    }

    @Test
    fun `unsupported-mode switch demands confirmation and cancellation preserves state`() {
        val state = draft(family = PowerLossProtocolFamily.UNSUPPORTED)
        val confirmed = state.selectTargetMode(TargetMode.POWER)
        assertNotNull(confirmed.pendingModeConfirmation, "POWER is not available in excluded modes")
        assertEquals(PowerLossUiCopy.MODE_CONFIRMATION_TITLE, confirmed.pendingModeConfirmation.title)
        assertEquals(PowerLossUiCopy.MODE_CONFIRMATION_CANCEL, confirmed.pendingModeConfirmation.cancelAction)
        assertEquals(PowerLossUiCopy.MODE_CONFIRMATION_CONFIRM, confirmed.pendingModeConfirmation.confirmAction)
        assertTrue(
            confirmed.pendingModeConfirmation.body.startsWith("Power-loss target is not available for "),
        )

        // Keep current mode: mode and target preserved.
        val kept = confirmed.dismissModeConfirmation()
        assertNull(kept.pendingModeConfirmation)
        assertEquals(TargetMode.REP_TARGET, kept.draft.targetMode)

        // Use Rep target: effective REP with settings kept.
        val rep = confirmed.confirmUseRepTarget()
        assertEquals(TargetMode.REP_TARGET, rep.draft.targetMode)
        assertTrue(rep.incompatibleSavedPowerConfig, "Saved POWER config is retained but inert")
    }

    @Test
    fun `capability unavailable state is dormant and inert`() {
        val state = draft(validated = false)
        val power = state.selectTargetMode(TargetMode.POWER)
        assertEquals(TargetMode.REP_TARGET, power.effectiveTargetMode, "POWER never arms without capability")
        assertTrue(power.dormantPowerConfig, "Retained-but-inert POWER config")
        assertFalse(power.powerSectionExpanded)
        assertEquals(PowerLossUiCopy.CAPABILITY_UNAVAILABLE, PowerLossUiCopy.CAPABILITY_UNAVAILABLE)
        assertEquals(PowerLossUiCopy.CAPABILITY_UNAVAILABLE_SECONDARY, PowerLossUiCopy.CAPABILITY_UNAVAILABLE_SECONDARY)
    }

    @Test
    fun `unsupported family keeps saved power config inert with the banner`() {
        val state = draft(
            config = PowerLossConfig(targetMode = TargetMode.POWER, savedNominalReps = 12),
            family = PowerLossProtocolFamily.UNSUPPORTED,
        )
        assertEquals(TargetMode.REP_TARGET, state.effectiveTargetMode)
        assertTrue(state.dormantPowerConfig)
        assertTrue(state.incompatibleSavedPowerConfig)
        assertEquals(
            "Power-loss target is not active in this mode.",
            PowerLossUiCopy.INCOMPATIBLE_SAVED_BANNER,
        )
    }

    @Test
    fun `profile defaults are scoped per profile - no cross-profile reuse`() {
        val scope = PowerLossExecutionScope()
        val configA = PowerLossConfig(targetMode = TargetMode.POWER, thresholdPercent = 30)
        scope.rememberFor("profile-a", configA)
        assertEquals(configA, scope.freezeForExecution("profile-a"))
        assertEquals(
            PowerLossConfig.DEFAULT,
            scope.freezeForExecution("profile-b"),
            "Ad-hoc choices apply to the current profile only (ui-spec §3)",
        )
    }

    @Test
    fun `saved reps header and readback copy match SD6`() {
        assertEquals("Saved reps: 12", PowerLossUiCopy.savedRepsHeader(12))
        assertEquals("Power-loss target active", PowerLossUiCopy.POWER_ACTIVE_BADGE)
        assertEquals(
            "Saved rep target: 12 (not used for this set)",
            PowerLossUiCopy.savedRepReadback(12),
        )
    }

    @Test
    fun `echo note and short-rom safeguard copy are fixed and non-toggleable`() {
        assertEquals(
            "Stopping uses lifting power only. Lowering power is logged separately when available.",
            PowerLossUiCopy.ECHO_NOTE,
        )
        assertEquals(
            "Short range of motion also counts: below 85% of the best rep\u2019s movement on either active cable.",
            PowerLossUiCopy.SHORT_ROM_SAFEGUARD,
        )
    }

    @Test
    fun `local-data disclosure ends with the portal deferral`() {
        assertTrue(PowerLossUiCopy.LOCAL_DATA_DISCLOSURE.endsWith("they are not synced to the portal."))
    }
}
