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
            displayMultiplier = 1,
            selectedItems = listOf(rackItem("vest", 10f, RackItemBehavior.ADDED_RESISTANCE)),
            isEchoMode = false,
        )

        assertEquals(10f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
        assertEquals(30f, result.displayLoadKg)
        assertEquals(20f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `display only does not affect display or machine load math`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 20f,
            displayMultiplier = 1,
            selectedItems = listOf(rackItem("plate carrier", 30f, RackItemBehavior.DISPLAY_ONLY)),
            isEchoMode = false,
        )

        assertEquals(0f, result.externalAddedLoadKg)
        assertEquals(0f, result.counterweightKg)
        assertEquals(20f, result.displayLoadKg)
        assertEquals(20f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `counterweight subtracts from display and non echo machine command`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 40f,
            displayMultiplier = 2,
            selectedItems = listOf(rackItem("assist", 12f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = false,
            validatorMinimumPerCableKg = 1f,
        )

        assertEquals(12f, result.counterweightKg)
        assertEquals(68f, result.displayLoadKg)
        assertEquals(34f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `counterweight machine command clamps to validator minimum and max`() {
        val minimum = useCase.calculate(
            programmedWeightPerCableKg = 3f,
            displayMultiplier = 1,
            selectedItems = listOf(rackItem("assist", 20f, RackItemBehavior.COUNTERWEIGHT)),
            isEchoMode = false,
            validatorMinimumPerCableKg = 1f,
        )
        val maximum = useCase.calculate(
            programmedWeightPerCableKg = 120f,
            displayMultiplier = 1,
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
            displayMultiplier = 1,
            selectedItems = listOf(vest, chain, vest, assist),
            isEchoMode = false,
        )

        assertEquals(15f, result.externalAddedLoadKg)
        assertEquals(8f, result.counterweightKg)
        assertEquals(37f, result.displayLoadKg)
        assertEquals(22f, result.adjustedMachineWeightPerCableKg)
    }

    @Test
    fun `echo keeps machine weight unchanged`() {
        val result = useCase.calculate(
            programmedWeightPerCableKg = 40f,
            displayMultiplier = 1,
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

    private fun rackItem(id: String, weightKg: Float, behavior: RackItemBehavior): RackItem = RackItem(
        id = id,
        name = id,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = behavior,
    )
}
