package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — export entry/legacy omission disclosure and the copy-content
 * guard (no physiological/RPE/efficacy claims, no verified-audible-playback
 * claims), plus the strict persisted-enum reader (unknown target enum NEVER
 * arms POWER).
 */
class PowerLossExportAndCopyGuardTest {

    @Test
    fun `export entry and legacy omission disclosure are exact`() {
        val export = PowerLossExportUi()
        assertEquals("Export power data", export.entryLabel)
        assertEquals(
            "This format does not include power-loss settings or per-attempt details. Use power-data export to keep them.",
            export.legacyOmissionDisclosure,
        )
        assertEquals("Choose power-data export", export.choosePowerDataExportAction)
        assertEquals("Export without power details", export.exportWithoutPowerDetailsAction)
    }

    @Test
    fun `legacy export confirmation surfaces the disclosure before running`() {
        val confirmation = legacyExportConfirmation()
        assertTrue(confirmation.visible)
        assertEquals(PowerLossUiCopy.LEGACY_EXPORT_DISCLOSURE, confirmation.disclosure)
    }

    @Test
    fun `copy contains no physiological rpe or efficacy claims`() {
        val banned = listOf("RPE", "rpe", "efficacy", "effective for", "muscle", "hypertrophy", "strength gain", "research-backed", "burns", "fat loss")
        allCopy().forEach { copy ->
            banned.forEach { term ->
                assertFalse(copy.contains(term), "Copy must not contain '$term': $copy")
            }
        }
    }

    @Test
    fun `copy contains no verified audible playback claims`() {
        val banned = listOf("guaranteed", "verified audible", "you will hear", "always audible", "audible playback")
        allCopy().forEach { copy ->
            banned.forEach { term ->
                assertFalse(copy.contains(term), "Copy must not claim playback: $copy")
            }
        }
        assertTrue(
            PowerLossUiCopy.NEAR_LIMIT_CUE_HELP.contains("if sound is enabled"),
            "Cue copy stays conditional on sound settings",
        )
    }

    @Test
    fun `unknown persisted target enum never arms POWER`() {
        assertEquals(TargetMode.REP_TARGET, PowerLossCapabilityBridge.effectiveTargetMode(null))
        assertEquals(TargetMode.REP_TARGET, PowerLossCapabilityBridge.effectiveTargetMode("SUPER_SET"))
        assertEquals(TargetMode.REP_TARGET, PowerLossCapabilityBridge.effectiveTargetMode("power"))
        assertEquals(TargetMode.POWER, PowerLossCapabilityBridge.effectiveTargetMode("POWER"))

        val unknown = powerLossConfigFromPersisted(
            persistedTargetMode = "TOTALLY_UNKNOWN",
            thresholdPercent = 25,
            savedNominalReps = 12,
        )
        assertEquals(TargetMode.REP_TARGET, unknown.targetMode)
        assertEquals(25, unknown.thresholdPercent, "Remaining persisted values are preserved")
    }

    @Test
    fun `propagation keeps POWER inert for unsupported protocol families`() {
        val config = PowerLossConfig(targetMode = TargetMode.POWER, thresholdPercent = 25, maxWorkingReps = 15)
        for (mode in listOf(ProgramMode.TUT, ProgramMode.TUTBeast, ProgramMode.EccentricOnly)) {
            val settings = powerLossRuntimeSettingsFor(
                config = config,
                mode = mode,
                pairedSamplesValidated = true,
            )
            assertEquals(TargetMode.REP_TARGET, settings.targetMode, "mode=$mode must stay inert")
        }
        val supported = powerLossRuntimeSettingsFor(
            config = config,
            mode = ProgramMode.OldSchool,
            savedNominalReps = 12,
            pairedSamplesValidated = true,
        )
        assertEquals(TargetMode.POWER, supported.targetMode)
        assertEquals(12, supported.savedNominalReps)
        assertTrue(supported.powerActive)
    }

    @Test
    fun `propagation respects the capability gate`() {
        val settings = powerLossRuntimeSettingsFor(
            config = PowerLossConfig(targetMode = TargetMode.POWER),
            mode = ProgramMode.Echo,
            pairedSamplesValidated = false,
        )
        assertEquals(TargetMode.POWER, settings.targetMode, "Config intent is retained")
        assertFalse(settings.powerActive, "POWER never arms without validated paired samples")
    }

    private fun allCopy(): List<String> = listOf(
        PowerLossUiCopy.SECTION_HEADING,
        PowerLossUiCopy.REP_HELPER,
        PowerLossUiCopy.POWER_LEAD,
        PowerLossUiCopy.POWER_HELP,
        PowerLossUiCopy.CUSTOM_THRESHOLD_HELPER,
        PowerLossUiCopy.CUSTOM_THRESHOLD_ERROR,
        PowerLossUiCopy.HIGH_THRESHOLD_TITLE,
        PowerLossUiCopy.HIGH_THRESHOLD_BODY,
        PowerLossUiCopy.MAX_WORKING_REPS_HELPER,
        PowerLossUiCopy.TWO_CONSECUTIVE_OFF_HELPER,
        PowerLossUiCopy.TWO_CONSECUTIVE_ON_HELPER,
        PowerLossUiCopy.SHORT_ROM_SAFEGUARD,
        PowerLossUiCopy.SHORT_ROM_SAFEGUARD_HELP,
        PowerLossUiCopy.ECHO_NOTE,
        PowerLossUiCopy.NEAR_LIMIT_CUE_HELP,
        PowerLossUiCopy.LOCAL_DATA_DISCLOSURE,
        PowerLossUiCopy.INCOMPATIBLE_SAVED_BANNER,
        PowerLossUiCopy.CAPABILITY_UNAVAILABLE,
        PowerLossUiCopy.CAPABILITY_UNAVAILABLE_SECONDARY,
        PowerLossUiCopy.SUMMARY_HELP,
        PowerLossUiCopy.SUMMARY_UNAVAILABLE,
        PowerLossUiCopy.SUMMARY_DISCLOSURE,
        PowerLossUiCopy.LEGACY_EXPORT_DISCLOSURE,
    )
}
