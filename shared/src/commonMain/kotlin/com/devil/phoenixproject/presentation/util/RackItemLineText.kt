package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.WeightUnit

private const val VISIBLE_RACK_ITEM_COUNT = 2

/**
 * Rack and accessory line shared by the workout HUD and history.
 *
 * The first two items are shown. Further items append ` +N` with no comma
 * and no "more". Added resistance is `Name +weight`, counterweight is
 * `Name -weight`, and a display-only item is the name alone.
 *
 * Returns null when [items] is empty. History still has a separate fallback
 * for sessions saved before rack snapshots: aggregate `+load / -counterweight`
 * with no item names. That path stays in HistoryTab.
 */
fun formatRackItemLine(
    items: List<RackItem>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String? {
    if (items.isEmpty()) return null
    val visibleItems = items.take(VISIBLE_RACK_ITEM_COUNT).map { item ->
        when (item.behavior) {
            RackItemBehavior.ADDED_RESISTANCE -> "${item.name} +${formatWeight(item.weightKg, weightUnit)}"
            RackItemBehavior.COUNTERWEIGHT -> "${item.name} -${formatWeight(item.weightKg, weightUnit)}"
            RackItemBehavior.DISPLAY_ONLY -> item.name
        }
    }
    val hiddenCount = items.size - visibleItems.size
    return if (hiddenCount > 0) {
        visibleItems.joinToString(", ") + " +$hiddenCount"
    } else {
        visibleItems.joinToString(", ")
    }
}
