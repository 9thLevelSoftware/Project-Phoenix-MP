package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.util.Constants
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplyEquipmentRackLoadUseCaseTest {
    private val useCase = ApplyEquipmentRackLoadUseCase()

    @Test
    fun `added resistance changes display load but not machine weight`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 20f,
            physicalCableCount = 1,
            selectedItems = listOf(rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE)),
            isEchoMode = false,
        )

        assertEquals(10f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
        assertEquals(30f, result.displayLoadKg)
        assertEquals(20f, result.adjustedMachineWeightPerCableKg)
        assertEquals(1, result.loadContributions.size)
        assertEquals("vest", result.loadContributions.single().itemId)
        assertEquals("vest", result.loadContributions.single().itemName)
        assertEquals(RackItemBehavior.ADDED_RESISTANCE, result.loadContributions.single().behavior)
        assertEquals(10f, result.loadContributions.single().weightKg)
    }

    @Test
    fun `display only does not affect display or machine load math`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 20f,
            physicalCableCount = 1,
            selectedItems = listOf(rackItem("plate carrier", 30f, RackItemBehavior.DISPLAY_ONLY)),
            isEchoMode = false,
        )

        assertEquals(0f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
        assertEquals(20f, result.displayLoadKg)
        assertEquals(20f, result.adjustedMachineWeightPerCableKg)
        assertEquals(0, result.loadContributions.size)
    }

    @Test
    fun `counterweight subtracts from display and non echo machine command`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 40f,
            physicalCableCount = 2,
            selectedItems = listOf(rackItem("assist", 12f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = false,
            validatorMinimumPerCableKg = 1f,
        )

        assertEquals(12f, result.counterweightKg)
        assertEquals(68f, result.displayLoadKg)
        assertEquals(34f, result.adjustedMachineWeightPerCableKg)
        assertEquals(1, result.loadContributions.size)
        assertEquals("assist", result.loadContributions.single().itemId)
        assertEquals(RackItemBehavior.COUNTERWEIGHT, result.loadContributions.single().behavior)
        assertEquals(12f, result.loadContributions.single().weightKg)
    }

    @Test
    fun `two cable counterweight splits across physical cables`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 40f,
            physicalCableCount = 2,
            selectedItems = listOf(rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = false,
            validatorMinimumPerCableKg = 1f,
        )

        assertEquals(10f, result.counterweightKg)
        assertEquals(70f, result.displayLoadKg)
        assertEquals(35f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `counterweight machine command clamps to validator minimum and max`() {
        val minimum = useCase.calculate(
            programmedWeightPerCableKg = 3f,
            physicalCableCount = 1,
            selectedItems = listOf(rackItem("assist", 20f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = false,
            validatorMinimumPerCableKg = 1f,
        )
        val maximum = useCase.calculate(
            programmedWeightPerCableKg = 120f,
            physicalCableCount = 1,
            selectedItems = emptyList(),
            isEchoMode = false,
        )

        assertEquals(1f, minimum.adjustedMachineWeightPerCableKg)
        assertEquals(Constants.MAX_WEIGHT_PER_CABLE_KG, maximum.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `multiple items aggregate and duplicate ids are ignored`() {
        val vest = rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE)
        val chain = rackItem("chain", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val assist = rackItem("assist", 8f, RackItemBehavior.COUNTERWEIGHT)

        val result = useCase.calculate(
            programmedWeightPerCableKg = 30f,
            physicalCableCount = 1,
            selectedItems = listOf(vest, chain, vest, assist),
            isEchoMode = false,
        )

        assertEquals(15f, result.externalAddedLoadKg)
        assertEquals(8f, result.counterweightKg)
        assertEquals(37f, result.displayLoadKg)
        assertEquals(22f, result.adjustedMachineWeightPerCableKg)
        assertEquals(listOf("vest", "chain", "assist"), result.loadContributions.map { it.itemId })
        assertEquals(
            listOf(
                RackItemBehavior.ADDED_RESISTANCE,
                RackItemBehavior.ADDED_RESISTANCE,
                RackItemBehavior.COUNTERWEIGHT,
            ),
            result.loadContributions.map { it.behavior },
        )
    }

    @Test
    fun `echo keeps machine weight unchanged`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 40f,
            physicalCableCount = 1,
            selectedItems = listOf(rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = true,
        )

        assertEquals(30f, result.displayLoadKg)
        assertEquals(40f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `bodyweight effective load includes added load and counterweight`() {
        val result = useCase.calculateBodyweightEffectiveLoadKg(
            bodyWeightKg = 100f,
            percentage = 0.95f,
            selectedItems = listOf(
                rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE),
                rackItem("assist", 25f, RackItemBehavior.COUNTERWEIGHT),
                rackItem("note", 100f, RackItemBehavior.DISPLAY_ONLY),
            ),
        )

        assertEquals(80f, result)
    }

    // ===================== Behavior Override Tests (Issues #521/#526) =====================

    @Test
    fun `no overrides - uses global behavior`() {
        val vest = rackItem("item-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val result = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest),
            isEchoMode = false,
        )
        assertEquals(5f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
    }

    @Test
    fun `override flips ADDED_RESISTANCE to COUNTERWEIGHT`() {
        val vest = rackItem("vest-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val overrides = mapOf("vest-1" to RackItemBehavior.COUNTERWEIGHT)
        val result = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest),
            isEchoMode = false,
            behaviorOverrides = overrides,
        )
        assertEquals(0f, result.externalAddedLoadKg)
        assertEquals(5f, result.counterweightKg)
        assertEquals(1, result.loadContributions.size)
        assertEquals("vest-1", result.loadContributions.single().itemId)
        assertEquals(RackItemBehavior.COUNTERWEIGHT, result.loadContributions.single().behavior)
    }

    @Test
    fun `override to DISPLAY_ONLY excludes from load math`() {
        val vest = rackItem("vest-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val overrides = mapOf("vest-1" to RackItemBehavior.DISPLAY_ONLY)
        val result = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest),
            isEchoMode = false,
            behaviorOverrides = overrides,
        )
        assertEquals(0f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
        // displayLoadKg = (50 * 2 + 0 - 0).coerceAtLeast(0) = 100
        assertEquals(100f, result.displayLoadKg)
        assertEquals(0, result.loadContributions.size)
    }

    @Test
    fun `override only applies to matching item ID`() {
        val vest = rackItem("vest-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val ankle = rackItem("ankle-1", 2f, RackItemBehavior.ADDED_RESISTANCE)
        // Override vest to COUNTERWEIGHT, ankle stays ADDED_RESISTANCE
        val overrides = mapOf("vest-1" to RackItemBehavior.COUNTERWEIGHT)
        val result = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest, ankle),
            isEchoMode = false,
            behaviorOverrides = overrides,
        )
        assertEquals(2f, result.externalAddedLoadKg) // ankle only
        assertEquals(5f, result.counterweightKg) // vest only
        assertEquals(listOf("vest-1", "ankle-1"), result.loadContributions.map { it.itemId })
        assertEquals(
            listOf(RackItemBehavior.COUNTERWEIGHT, RackItemBehavior.ADDED_RESISTANCE),
            result.loadContributions.map { it.behavior },
        )
    }

    @Test
    fun `empty overrides map has no effect`() {
        val vest = rackItem("vest-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        val withOverrides = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest),
            isEchoMode = false,
            behaviorOverrides = emptyMap(),
        )
        val without = useCase.calculate(
            programmedWeightPerCableKg = 50f,
            physicalCableCount = 2,
            selectedItems = listOf(vest),
            isEchoMode = false,
        )
        assertEquals(without.externalAddedLoadKg, withOverrides.externalAddedLoadKg)
        assertEquals(without.counterweightKg, withOverrides.counterweightKg)
    }

    @Test
    fun `bodyweight effective load respects overrides`() {
        val vest = rackItem("vest-1", 5f, RackItemBehavior.ADDED_RESISTANCE)
        // Override vest to COUNTERWEIGHT for bodyweight
        val overrides = mapOf("vest-1" to RackItemBehavior.COUNTERWEIGHT)
        val result = useCase.calculateBodyweightEffectiveLoadKg(
            bodyWeightKg = 80f,
            percentage = 1.0f,
            selectedItems = listOf(vest),
            behaviorOverrides = overrides,
        )
        // 80 * 1.0 + 0 (no added resistance) - 5 (counterweight via override) = 75
        assertEquals(75f, result)
    }

    private fun rackItem(id: String, weightKg: Float, behavior: RackItemBehavior): RackItem = RackItem(
        id = id,
        name = id,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = behavior,
    )
}
