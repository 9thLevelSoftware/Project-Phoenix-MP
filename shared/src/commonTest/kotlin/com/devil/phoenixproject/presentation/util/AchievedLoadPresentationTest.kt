package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #1182 rendered-output acceptance (merge-gate R5c).
 *
 * The gate rejected source-pattern guards as evidence: "Source wiring inspection is not
 * rendered acceptance." This is the extracted-presentation alternative it asked for - the four
 * reporting surfaces now render straight from [AchievedLoadPresentation], so testing that
 * object tests the exact strings the user sees, across kg/lb and measured/unavailable.
 *
 * A companion source guard (WeightDisplaySourceGuardTest) binds each composable to these
 * helpers, so the tested code is provably the code that renders.
 */
class AchievedLoadPresentationTest {

    // ===== fixtures =====

    /** Reporter's row: configured 5 kg seed, measured peak 80 kg/cable, 9 working reps. */
    private fun achievedEchoSession() = WorkoutSession(
        id = "echo-achieved",
        timestamp = 1_790_000_000_000L,
        mode = "Echo",
        weightPerCableKg = 5f,
        totalReps = 9,
        workingReps = 9,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = 80f,
        peakForceConcentricA = 640f,
    )

    /** Echo set with no accepted working telemetry -> the 0 sentinel. */
    private fun unavailableEchoSession() = WorkoutSession(
        id = "echo-unavailable",
        timestamp = 1_790_000_000_000L,
        mode = "Echo",
        weightPerCableKg = 5f,
        totalReps = 3,
        workingReps = 3,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = 0f,
    )

    /** A fixed-load (non-Echo) set: the configured weight IS the achieved load. */
    private fun fixedLoadSession() = WorkoutSession(
        id = "fixed-load",
        timestamp = 1_790_000_000_000L,
        mode = "OldSchool",
        weightPerCableKg = 40f,
        totalReps = 10,
        workingReps = 10,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = 40f,
    )

    private fun echoSummary(
        measuredPeakKgPerCable: Float?,
        configuredKg: Float = 5f,
    ) = WorkoutState.SetSummary(
        metrics = emptyList<WorkoutMetric>(),
        peakLoadKgPerCable = measuredPeakKgPerCable ?: configuredKg,
        avgLoadKgPerCable = measuredPeakKgPerCable ?: configuredKg,
        repCount = 9,
        isEchoMode = true,
        configuredWeightKgPerCable = configuredKg,
        measuredWorkingPeakKgPerCable = measuredPeakKgPerCable,
    )

    private fun fixedLoadSummary(configuredKg: Float = 40f) = WorkoutState.SetSummary(
        metrics = emptyList<WorkoutMetric>(),
        peakLoadKgPerCable = configuredKg,
        avgLoadKgPerCable = configuredKg,
        repCount = 10,
        isEchoMode = false,
        configuredWeightKgPerCable = configuredKg,
        measuredWorkingPeakKgPerCable = null,
    )

    private fun completedSet(reps: Int = 9, weightKg: Float = 80f) = CompletedSet.create(
        id = "set-1",
        sessionId = "echo-achieved",
        setNumber = 1,
        actualReps = reps,
        actualWeightKg = weightKg,
        setEndReason = SetEndReason.CABLE_RELEASED,
    )

    /** Mirrors the host formatter used by the Recent Sessions card. */
    private val hostFormatWeight: (Float, WeightUnit) -> String = { kg, unit ->
        WeightDisplayFormatter.formatDisplayWeight(kg, unit)
    }

    // ===== Recent Sessions / quick-history row =====

    @Test
    fun `recent sessions renders the achieved peak and never the configured seed`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val text = AchievedLoadPresentation.sessionLoadText(achievedEchoSession(), unit, hostFormatWeight)
            assertEquals(WeightDisplayFormatter.formatDisplayWeight(80f, unit), text, "Echo renders the achieved 80 kg peak ($unit)")
            assertFalse(text.contains("11.02"), "the configured 5 kg seed (11.02 lb) must never render ($unit)")
            assertFalse(text.contains("Load unavailable"), "a measured set has a load ($unit)")
        }
    }

    @Test
    fun `recent sessions honours the unit and differs between kg and lb`() {
        val kg = AchievedLoadPresentation.sessionLoadText(achievedEchoSession(), WeightUnit.KG, hostFormatWeight)
        val lb = AchievedLoadPresentation.sessionLoadText(achievedEchoSession(), WeightUnit.LB, hostFormatWeight)
        assertNotEquals(kg, lb, "kg and lb must render different text")
    }

    @Test
    fun `recent sessions shows Load unavailable and not zero when nothing was measured`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val text = AchievedLoadPresentation.sessionLoadText(unavailableEchoSession(), unit, hostFormatWeight)
            assertEquals(AchievedLoadPresentation.LOAD_UNAVAILABLE, text, "no measurement renders Load unavailable ($unit)")
            assertFalse(text.contains("0"), "the 0 sentinel must never render as 0 lifted ($unit)")
        }
    }

    @Test
    fun `recent sessions keeps the configured weight for a fixed load set`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val text = AchievedLoadPresentation.sessionLoadText(fixedLoadSession(), unit, hostFormatWeight)
            assertEquals(WeightDisplayFormatter.formatDisplayWeight(40f, unit), text, "a fixed-load set keeps its set weight ($unit)")
        }
    }

    // ===== Home recent-activity line =====

    @Test
    fun `home renders reps plus the achieved peak with the unit label`() {
        assertEquals("9 reps • ${WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.KG)} kg",
            AchievedLoadPresentation.homeRecentActivityLine(achievedEchoSession(), WeightUnit.KG))
        assertEquals("9 reps • ${WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.LB)} lbs",
            AchievedLoadPresentation.homeRecentActivityLine(achievedEchoSession(), WeightUnit.LB))
    }

    @Test
    fun `home renders Load unavailable without a unit when nothing was measured`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val line = AchievedLoadPresentation.homeRecentActivityLine(unavailableEchoSession(), unit)
            assertEquals("3 reps • ${AchievedLoadPresentation.LOAD_UNAVAILABLE}", line, "($unit)")
            assertFalse(line.contains("kg") || line.contains("lbs"), "an unavailable load carries no unit ($unit)")
            assertFalse(line.contains("11.02"), "never the configured seed ($unit)")
        }
    }

    // ===== Set Summary primary stat =====

    @Test
    fun `set summary labels an echo primary as Peak load and stays per cable`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val primary = AchievedLoadPresentation.setSummaryPrimary(echoSummary(measuredPeakKgPerCable = 80f), unit)
            assertEquals("Peak load", primary.label, "Echo is labelled Peak load ($unit)")
            assertEquals(
                WeightDisplayFormatter.toDisplayWeight(80f, unit).toInt().toString(),
                primary.valueText,
                "the value is the achieved peak ($unit)",
            )
            assertEquals("(${if (unit == WeightUnit.LB) "lbs" else "kg"}/cable)", primary.unitText, "per-cable label ($unit)")
        }
    }

    @Test
    fun `set summary shows Load unavailable with no unit when echo has no measurement`() {
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val primary = AchievedLoadPresentation.setSummaryPrimary(echoSummary(measuredPeakKgPerCable = null), unit)
            assertEquals("Peak load", primary.label)
            assertEquals(AchievedLoadPresentation.LOAD_UNAVAILABLE, primary.valueText, "($unit)")
            assertEquals("", primary.unitText, "no stray per-cable unit beside an unavailable value ($unit)")
        }
    }

    @Test
    fun `set summary keeps Set Weight for a fixed load set`() {
        val primary = AchievedLoadPresentation.setSummaryPrimary(fixedLoadSummary(), WeightUnit.LB)
        assertEquals("Set Weight", primary.label, "a fixed-load set is not a Peak load")
        assertEquals("(${WeightDisplayFormatter.toDisplayWeight(40f, WeightUnit.LB).toInt()})", "(${primary.valueText})")
        assertEquals("(lbs/cable)", primary.unitText)
    }

    @Test
    fun `set summary renders the seed as achieved value only for fixed load, never for echo`() {
        val echoPrimary = AchievedLoadPresentation.setSummaryPrimary(echoSummary(measuredPeakKgPerCable = 80f), WeightUnit.LB)
        assertNotEquals(
            WeightDisplayFormatter.toDisplayWeight(5f, WeightUnit.LB).toInt().toString(),
            echoPrimary.valueText,
            "the configured 5 kg seed must never be the Echo primary value",
        )
    }

    // ===== History completed-set row =====

    @Test
    fun `history renders reps x achieved load with the unit`() {
        val set = completedSet()
        assertEquals(
            "9 x ${WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.KG)} kg",
            AchievedLoadPresentation.historySetText(set, achievedEchoSession(), WeightUnit.KG),
        )
        // History's unit suffix is weightUnit.name.lowercase() ("lb"), as this surface has
        // always rendered it - preserved unchanged by #1182.
        assertEquals(
            "9 x ${WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.LB)} lb",
            AchievedLoadPresentation.historySetText(set, achievedEchoSession(), WeightUnit.LB),
        )
    }

    @Test
    fun `history renders Load unavailable for an echo set with no measurement`() {
        val set = completedSet(reps = 3, weightKg = 0f)
        for (unit in listOf(WeightUnit.KG, WeightUnit.LB)) {
            val text = AchievedLoadPresentation.historySetText(set, unavailableEchoSession(), unit)
            assertEquals("3 x ${AchievedLoadPresentation.LOAD_UNAVAILABLE}", text, "($unit)")
            assertFalse(text.contains("11.02"), "never the configured seed ($unit)")
        }
    }

    @Test
    fun `history keeps the recorded set weight for a fixed load set`() {
        val set = completedSet(reps = 10, weightKg = 40f)
        assertEquals(
            "10 x ${WeightDisplayFormatter.formatDisplayWeight(40f, WeightUnit.KG)} kg",
            AchievedLoadPresentation.historySetText(set, fixedLoadSession(), WeightUnit.KG),
        )
    }

    // ===== wording is identical on every surface =====

    @Test
    fun `every surface uses one unavailable wording`() {
        // A single constant means the four surfaces can never drift apart.
        val session = unavailableEchoSession()
        val set = completedSet(reps = 3, weightKg = 0f)
        assertTrue(
            AchievedLoadPresentation.sessionLoadText(session, WeightUnit.KG, hostFormatWeight)
                .contains(AchievedLoadPresentation.LOAD_UNAVAILABLE),
        )
        assertTrue(
            AchievedLoadPresentation.homeRecentActivityLine(session, WeightUnit.KG)
                .contains(AchievedLoadPresentation.LOAD_UNAVAILABLE),
        )
        assertTrue(
            AchievedLoadPresentation.setSummaryPrimary(echoSummary(null), WeightUnit.KG).valueText
                .contains(AchievedLoadPresentation.LOAD_UNAVAILABLE),
        )
        assertTrue(
            AchievedLoadPresentation.historySetText(set, session, WeightUnit.KG)
                .contains(AchievedLoadPresentation.LOAD_UNAVAILABLE),
        )
    }
}
