package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadContribution
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RackLoadContributionFormatterTest {

    @Test
    fun `weighted vest added resistance uses plus sign`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(contribution("vest", "Weighted Vest", RackItemBehavior.ADDED_RESISTANCE, 3.62874f)),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +3.6 kg", result)
    }

    @Test
    fun `counterweight uses minus sign`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(contribution("band", "Assistance Band", RackItemBehavior.COUNTERWEIGHT, 10f)),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Assistance Band -10.0 kg", result)
    }

    @Test
    fun `two contributors are shown in order`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(
                contribution("vest", "Weighted Vest", RackItemBehavior.ADDED_RESISTANCE, 3.62874f),
                contribution("band", "Assistance Band", RackItemBehavior.COUNTERWEIGHT, 10f),
            ),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +3.6 kg, Assistance Band -10.0 kg", result)
    }

    @Test
    fun `third contributor collapses to more count`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(
                contribution("vest", "Weighted Vest", RackItemBehavior.ADDED_RESISTANCE, 3.62874f),
                contribution("band", "Assistance Band", RackItemBehavior.COUNTERWEIGHT, 10f),
                contribution("chains", "Chains", RackItemBehavior.ADDED_RESISTANCE, 2f),
            ),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +3.6 kg, Assistance Band -10.0 kg, +1 more", result)
    }

    @Test
    fun `empty contribution list returns null`() {
        val result = formatRackLoadContributionSummary(
            contributions = emptyList(),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertNull(result)
    }

    @Test
    fun `display only contributions are defensively omitted`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(contribution("note", "Phone", RackItemBehavior.DISPLAY_ONLY, 1f)),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertNull(result)
    }

    @Test
    fun `display only contributions do not count toward more count`() {
        val result = formatRackLoadContributionSummary(
            contributions = listOf(
                contribution("vest", "Weighted Vest", RackItemBehavior.ADDED_RESISTANCE, 3.62874f),
                contribution("phone", "Phone", RackItemBehavior.DISPLAY_ONLY, 1f),
                contribution("band", "Assistance Band", RackItemBehavior.COUNTERWEIGHT, 10f),
                contribution("chains", "Chains", RackItemBehavior.ADDED_RESISTANCE, 2f),
            ),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +3.6 kg, Assistance Band -10.0 kg, +1 more", result)
    }

    private fun contribution(
        id: String,
        name: String,
        behavior: RackItemBehavior,
        weightKg: Float,
    ): RackLoadContribution = RackLoadContribution(
        itemId = id,
        itemName = name,
        behavior = behavior,
        weightKg = weightKg,
    )

    private fun formatWeight(weight: Float, unit: WeightUnit): String {
        val unitLabel = if (unit == WeightUnit.KG) "kg" else "lb"
        val rounded = round(weight * 10f) / 10f
        val valueText = if (rounded == rounded.toInt().toFloat()) {
            "${rounded.toInt()}.0"
        } else {
            rounded.toString()
        }
        return "$valueText $unitLabel"
    }
}
