package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ActiveRackSelection
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EquipmentRackRepositoryTest {
    @Test
    fun `empty settings loads empty rack`() = runTest {
        val repository = SettingsEquipmentRackRepository(MapSettings())

        assertTrue(repository.getItems().isEmpty())
        assertTrue(repository.rackItems.value.isEmpty())
    }

    @Test
    fun `corrupt json falls back to empty rack`() = runTest {
        val settings = MapSettings().apply {
            putString("equipment_rack_items_v1", "{not valid json")
        }
        val repository = SettingsEquipmentRackRepository(settings)

        assertTrue(repository.getItems().isEmpty())
        assertTrue(repository.rackItems.value.isEmpty())
    }

    @Test
    fun `save load and order rack items`() = runTest {
        val settings = MapSettings()
        val repository = SettingsEquipmentRackRepository(settings)
        val first = rackItem("first", "Weighted vest", 10f, sortOrder = 2)
        val second = rackItem("second", "Dip belt", 15f, sortOrder = 1)

        repository.saveItems(listOf(first, second))
        val reloaded = SettingsEquipmentRackRepository(settings)

        assertEquals(listOf(first, second), reloaded.getItems())
    }

    @Test
    fun `add update and delete rack item`() = runTest {
        val repository = SettingsEquipmentRackRepository(MapSettings())
        val item = rackItem("vest", "Weighted vest", 10f)

        repository.upsert(item)
        repository.upsert(item.copy(name = "Heavy vest", weightKg = 12f))
        repository.delete("missing")
        repository.delete("vest")

        assertTrue(repository.getItems().isEmpty())
    }

    @Test
    fun `resolve active selection de duplicates ids and ignores disabled items`() = runTest {
        val repository = SettingsEquipmentRackRepository(MapSettings())
        val enabled = rackItem("vest", "Weighted vest", 10f)
        val disabled = rackItem("belt", "Dip belt", 15f, enabled = false)
        repository.saveItems(listOf(enabled, disabled))

        val resolved = repository.resolveActiveItems(
            ActiveRackSelection(listOf("vest", "belt", "vest", "missing")),
        )

        assertEquals(listOf(enabled), resolved)
    }

    private fun rackItem(
        id: String,
        name: String,
        weightKg: Float,
        behavior: RackItemBehavior = RackItemBehavior.ADDED_RESISTANCE,
        enabled: Boolean = true,
        sortOrder: Int = 0,
    ): RackItem = RackItem(
        id = id,
        name = name,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = behavior,
        enabled = enabled,
        sortOrder = sortOrder,
    )
}
