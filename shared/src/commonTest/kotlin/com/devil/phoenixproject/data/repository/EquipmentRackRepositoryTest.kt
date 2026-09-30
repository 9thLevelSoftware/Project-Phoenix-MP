package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EquipmentRackRepositoryTest {
    @Test
    fun `equipment rack follows active profile`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-a")
        }
        val repository = ProfileEquipmentRackRepository(profiles, backgroundScope)
        runCurrent()

        repository.saveItems(listOf(rackItem("a-item", "A item", 10f)))
        profiles.setActiveProfileForTest(id = "profile-b")
        repository.saveItems(listOf(rackItem("b-item", "B item", 20f)))
        runCurrent()

        assertEquals(listOf("b-item"), repository.getItems().map { it.id })
        assertEquals(listOf("b-item"), repository.rackItems.value.map { it.id })
        profiles.setActiveProfileForTest(id = "profile-a")
        runCurrent()
        assertEquals(listOf("a-item"), repository.getItems().map { it.id })
        assertEquals(listOf("a-item"), repository.rackItems.value.map { it.id })
    }

    private fun rackItem(
        id: String,
        name: String,
        weightKg: Float,
    ): RackItem = RackItem(
        id = id,
        name = name,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
    )
}
