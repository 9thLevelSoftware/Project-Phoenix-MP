package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ActiveRackSelection
import com.devil.phoenixproject.domain.model.RackItem
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface EquipmentRackRepository {
    val rackItems: StateFlow<List<RackItem>>

    suspend fun getItems(): List<RackItem>
    suspend fun saveItems(items: List<RackItem>)
    suspend fun upsert(item: RackItem)
    suspend fun delete(id: String)
    suspend fun resolveActiveItems(selection: ActiveRackSelection): List<RackItem>
}

class SettingsEquipmentRackRepository(
    private val settings: Settings,
) : EquipmentRackRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _rackItems = MutableStateFlow(loadItems())
    override val rackItems: StateFlow<List<RackItem>> = _rackItems.asStateFlow()

    override suspend fun getItems(): List<RackItem> = _rackItems.value

    override suspend fun saveItems(items: List<RackItem>) {
        settings.putString(KEY_RACK_ITEMS, json.encodeToString(items))
        _rackItems.value = items
    }

    override suspend fun upsert(item: RackItem) {
        val items = getItems().toMutableList()
        val existingIndex = items.indexOfFirst { it.id == item.id }
        if (existingIndex >= 0) {
            items[existingIndex] = item
        } else {
            items += item
        }
        saveItems(items)
    }

    override suspend fun delete(id: String) {
        saveItems(getItems().filterNot { it.id == id })
    }

    override suspend fun resolveActiveItems(selection: ActiveRackSelection): List<RackItem> {
        val byId = getItems()
            .filter { it.enabled }
            .associateBy { it.id }
        return selection.distinctItemIds.mapNotNull { byId[it] }
    }

    private fun loadItems(): List<RackItem> {
        val encoded = settings.getStringOrNull(KEY_RACK_ITEMS) ?: return emptyList()
        return try {
            json.decodeFromString<List<RackItem>>(encoded)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    companion object {
        const val KEY_RACK_ITEMS = "equipment_rack_items_v1"
    }
}
