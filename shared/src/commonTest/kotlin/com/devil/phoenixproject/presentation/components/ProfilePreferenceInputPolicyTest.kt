package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.util.UnitConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePreferenceInputPolicyTest {
    @Test
    fun `changing weight unit writes the explicit kilogram default`() {
        val current = CoreProfilePreferences(
            bodyWeightKg = 80f,
            weightUnit = WeightUnit.LB,
            weightIncrement = 5f,
        )

        val updated = coreAfterWeightUnitSelection(current, WeightUnit.KG)

        assertEquals(WeightUnit.KG, updated.weightUnit)
        assertEquals(0.5f, updated.weightIncrement)
        assertEquals(80f, updated.bodyWeightKg)
    }

    @Test
    fun `changing weight unit writes the explicit pound default`() {
        val current = CoreProfilePreferences(
            weightUnit = WeightUnit.KG,
            weightIncrement = 2.5f,
        )

        val updated = coreAfterWeightUnitSelection(current, WeightUnit.LB)

        assertEquals(WeightUnit.LB, updated.weightUnit)
        assertEquals(1f, updated.weightIncrement)
    }

    @Test
    fun `weight increment choices are unit specific and never automatic`() {
        assertEquals(
            listOf(0.5f, 1f, 2.5f, 5f),
            weightIncrementOptionsFor(WeightUnit.KG),
        )
        assertEquals(
            listOf(0.1f, 0.5f, 1f, 2.5f, 5f),
            weightIncrementOptionsFor(WeightUnit.LB),
        )
    }

    @Test
    fun `legacy automatic increment displays the unit default without changing storage`() {
        val kilograms = CoreProfilePreferences(
            weightUnit = WeightUnit.KG,
            weightIncrement = -1f,
        )
        val pounds = CoreProfilePreferences(
            weightUnit = WeightUnit.LB,
            weightIncrement = -1f,
        )

        assertEquals(0.5f, displayedWeightIncrement(kilograms))
        assertEquals(1f, displayedWeightIncrement(pounds))
        assertEquals(-1f, kilograms.weightIncrement)
        assertEquals(-1f, pounds.weightIncrement)
    }

    @Test
    fun `adult mode prerequisite changes deactivate dominatrix without relocking it`() {
        val unlockedAndActive = VbtPreferences(
            enabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            dominatrixModeUnlocked = true,
            dominatrixModeActive = true,
        )

        listOf(
            vbtAfterEnabledSelection(unlockedAndActive, false),
            vbtAfterVerbalEncouragementSelection(unlockedAndActive, false),
            vbtAfterVulgarModeSelection(unlockedAndActive, false),
        ).forEach { updated ->
            assertEquals(true, updated.dominatrixModeUnlocked)
            assertEquals(false, updated.dominatrixModeActive)
        }
    }

    @Test
    fun `reselecting the current weight unit preserves its increment`() {
        val current = CoreProfilePreferences(
            weightUnit = WeightUnit.LB,
            weightIncrement = 5f,
        )

        val updated = coreAfterWeightUnitSelection(current, WeightUnit.LB)

        assertEquals(5f, updated.weightIncrement)
    }

    @Test
    fun `untouched pounds draft preserves the exact authoritative kilograms`() {
        assertEquals(
            80f,
            bodyWeightKgForSave(
                authoritativeBodyWeightKg = 80f,
                draft = "176.4",
                weightUnit = WeightUnit.LB,
                draftEdited = false,
            ),
        )
    }

    @Test
    fun `untouched maximum body weight remains valid after pounds display rounding`() {
        assertEquals(
            300f,
            bodyWeightKgForSave(
                authoritativeBodyWeightKg = 300f,
                draft = "661.4",
                weightUnit = WeightUnit.LB,
                draftEdited = false,
            ),
        )
    }

    @Test
    fun `edited pounds draft is parsed back to kilograms`() {
        assertEquals(
            UnitConverter.lbToKg(220.5f),
            bodyWeightKgForSave(
                authoritativeBodyWeightKg = 80f,
                draft = "220.5",
                weightUnit = WeightUnit.LB,
                draftEdited = true,
            ),
        )
    }

    @Test
    fun `edited pounds draft above the canonical maximum is rejected`() {
        assertNull(
            bodyWeightKgForSave(
                authoritativeBodyWeightKg = 300f,
                draft = "661.4",
                weightUnit = WeightUnit.LB,
                draftEdited = true,
            ),
        )
    }
}
