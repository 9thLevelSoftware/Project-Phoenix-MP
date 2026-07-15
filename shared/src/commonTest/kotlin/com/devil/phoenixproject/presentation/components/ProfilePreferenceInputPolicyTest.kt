package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.UnitConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePreferenceInputPolicyTest {
    @Test
    fun `changing weight unit resets the display-unit increment to automatic`() {
        val current = CoreProfilePreferences(
            bodyWeightKg = 80f,
            weightUnit = WeightUnit.LB,
            weightIncrement = 5f,
        )

        val updated = coreAfterWeightUnitSelection(current, WeightUnit.KG)

        assertEquals(WeightUnit.KG, updated.weightUnit)
        assertEquals(-1f, updated.weightIncrement)
        assertEquals(80f, updated.bodyWeightKg)
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
