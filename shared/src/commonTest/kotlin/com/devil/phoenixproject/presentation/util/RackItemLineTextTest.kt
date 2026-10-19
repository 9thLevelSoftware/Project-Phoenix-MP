package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RackItemLineTextTest {

    @Test
    fun `empty selection returns null`() {
        assertNull(
            formatRackItemLine(
                items = emptyList(),
                weightUnit = WeightUnit.KG,
                formatWeight = ::formatWeight,
            ),
        )
    }

    @Test
    fun `added resistance prefixes the formatted weight with plus`() {
        val line = formatRackItemLine(
            items = listOf(item("Weighted Vest", 10f, RackItemBehavior.ADDED_RESISTANCE)),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +10 KG", line)
    }

    @Test
    fun `counterweight prefixes the formatted weight with minus`() {
        val line = formatRackItemLine(
            items = listOf(item("Assist Band", 5.5f, RackItemBehavior.COUNTERWEIGHT)),
            weightUnit = WeightUnit.LB,
            formatWeight = ::formatWeight,
        )

        assertEquals("Assist Band -5.5 LB", line)
    }

    @Test
    fun `display only item is the name with no weight`() {
        val line = formatRackItemLine(
            items = listOf(item("Phone", 1f, RackItemBehavior.DISPLAY_ONLY)),
            weightUnit = WeightUnit.KG,
            formatWeight = { _, _ -> error("display-only items do not format a weight") },
        )

        assertEquals("Phone", line)
    }

    @Test
    fun `two items stay in order and join with a comma`() {
        val line = formatRackItemLine(
            items = listOf(
                item("Weighted Vest", 10f, RackItemBehavior.ADDED_RESISTANCE),
                item("Assist Band", 5f, RackItemBehavior.COUNTERWEIGHT),
            ),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +10 KG, Assist Band -5 KG", line)
    }

    @Test
    fun `overflow count is appended without a comma or the word more`() {
        val line = formatRackItemLine(
            items = listOf(
                item("Weighted Vest", 10f, RackItemBehavior.ADDED_RESISTANCE),
                item("Phone", 1f, RackItemBehavior.DISPLAY_ONLY),
                item("Chains", 2f, RackItemBehavior.ADDED_RESISTANCE),
                item("Assist Band", 5f, RackItemBehavior.COUNTERWEIGHT),
            ),
            weightUnit = WeightUnit.KG,
            formatWeight = ::formatWeight,
        )

        assertEquals("Weighted Vest +10 KG, Phone +2", line)
    }

    @Test
    fun `formatter receives the item weight and the requested unit`() {
        val seen = mutableListOf<Pair<Float, WeightUnit>>()
        formatRackItemLine(
            items = listOf(item("Dip Belt", 7.25f, RackItemBehavior.ADDED_RESISTANCE)),
            weightUnit = WeightUnit.LB,
            formatWeight = { weight, unit ->
                seen += weight to unit
                "seen"
            },
        )

        assertEquals(listOf(7.25f to WeightUnit.LB), seen)
    }

    private fun item(name: String, weightKg: Float, behavior: RackItemBehavior): RackItem = RackItem(
        id = name,
        name = name,
        weightKg = weightKg,
        behavior = behavior,
    )

    private fun formatWeight(weightKg: Float, unit: WeightUnit): String {
        val text = if (weightKg == weightKg.toInt().toFloat()) {
            weightKg.toInt().toString()
        } else {
            weightKg.toString()
        }
        return "$text ${unit.name}"
    }
}
