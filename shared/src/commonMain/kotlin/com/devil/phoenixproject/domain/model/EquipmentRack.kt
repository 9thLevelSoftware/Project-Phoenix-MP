package com.devil.phoenixproject.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RackItemCategory {
    WEIGHTED_VEST,
    DIP_BELT,
    CHAINS,
    BAND,
    ASSISTANCE,
    ATTACHMENT,
    OTHER,
}

@Serializable
enum class RackItemBehavior {
    ADDED_RESISTANCE,
    COUNTERWEIGHT,
    DISPLAY_ONLY,
}

@Serializable
data class RackItem(
    val id: String = generateUUID(),
    val name: String,
    val category: RackItemCategory = RackItemCategory.OTHER,
    val weightKg: Float,
    val behavior: RackItemBehavior = RackItemBehavior.ADDED_RESISTANCE,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    init {
        require(name.isNotBlank()) { "Rack item name cannot be blank" }
        require(weightKg.isFinite()) { "Rack item weight must be finite" }
        require(weightKg >= 0f) { "Rack item weight cannot be negative" }
    }
}

@Serializable
data class ActiveRackSelection(
    val itemIds: List<String> = emptyList(),
) {
    val distinctItemIds: List<String>
        get() = itemIds.distinct()

    val isEmpty: Boolean
        get() = distinctItemIds.isEmpty()
}

@Serializable
data class RackLoadContribution(
    val itemId: String,
    val itemName: String,
    val behavior: RackItemBehavior,
    val weightKg: Float,
)

@Serializable
data class RackLoadAdjustment(
    val selectedItems: List<RackItem> = emptyList(),
    val externalAddedLoadKg: Float = 0f,
    val counterweightKg: Float = 0f,
    val displayLoadKg: Float = 0f,
    val adjustedMachineWeightPerCableKg: Float = 0f,
    val loadContributions: List<RackLoadContribution> = emptyList(),
) {
    val hasLoadAdjustment: Boolean
        get() = externalAddedLoadKg > 0f || counterweightKg > 0f
}
