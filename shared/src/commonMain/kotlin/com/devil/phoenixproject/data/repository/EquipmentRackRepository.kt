package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ActiveRackSelection
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.RackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface EquipmentRackRepository {
    val rackItems: StateFlow<List<RackItem>>

    suspend fun getItems(): List<RackItem>
    suspend fun saveItems(items: List<RackItem>)
    suspend fun upsert(item: RackItem)
    suspend fun delete(id: String)
    suspend fun resolveActiveItems(selection: ActiveRackSelection): List<RackItem>
}

class ProfileEquipmentRackRepository(
    private val profiles: UserProfileRepository,
    private val scope: CoroutineScope,
) : EquipmentRackRepository {
    private val mutations = Mutex()

    private fun items(context: ActiveProfileContext): List<RackItem> =
        (context as? ActiveProfileContext.Ready)?.preferences?.rack?.value?.items.orEmpty()

    override val rackItems: StateFlow<List<RackItem>> = profiles.activeProfileContext
        .map(::items)
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = items(profiles.activeProfileContext.value),
        )

    private fun ready(): ActiveProfileContext.Ready =
        profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()

    private fun readyFor(expectedId: String): ActiveProfileContext.Ready {
        val current = ready()
        if (current.profile.id != expectedId) {
            throw StaleProfileContextException(expectedId, current.profile.id)
        }
        return current
    }

    private suspend fun mutate(
        expectedId: String,
        transform: (RackPreferences) -> RackPreferences,
    ) = mutations.withLock {
        val current = readyFor(expectedId)
        profiles.updateRack(expectedId, transform(current.preferences.rack.value))
    }

    override suspend fun getItems(): List<RackItem> = ready().preferences.rack.value.items

    override suspend fun saveItems(items: List<RackItem>) {
        val expectedId = ready().profile.id
        mutate(expectedId) { it.copy(items = items) }
    }

    override suspend fun upsert(item: RackItem) {
        val expectedId = ready().profile.id
        mutate(expectedId) { current ->
            val items = current.items.toMutableList()
            val existingIndex = items.indexOfFirst { it.id == item.id }
            if (existingIndex >= 0) items[existingIndex] = item else items += item
            current.copy(items = items)
        }
    }

    override suspend fun delete(id: String) {
        val expectedId = ready().profile.id
        mutate(expectedId) { current ->
            current.copy(items = current.items.filterNot { it.id == id })
        }
    }

    override suspend fun resolveActiveItems(selection: ActiveRackSelection): List<RackItem> {
        val byId = getItems().filter { it.enabled }.associateBy { it.id }
        return selection.distinctItemIds.mapNotNull(byId::get)
    }

    fun close() = scope.cancel()
}
