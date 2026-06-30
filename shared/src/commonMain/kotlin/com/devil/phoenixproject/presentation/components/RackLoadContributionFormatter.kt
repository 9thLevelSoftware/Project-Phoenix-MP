package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadContribution
import com.devil.phoenixproject.domain.model.WeightUnit

fun formatRackLoadContributionSummary(
    contributions: List<RackLoadContribution>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    moreTemplate: String = "+%d more",
    maxVisibleItems: Int = 2,
): String? {
    val loadAffectingContributions = contributions.filter { contribution ->
        contribution.behavior == RackItemBehavior.ADDED_RESISTANCE ||
            contribution.behavior == RackItemBehavior.COUNTERWEIGHT
    }
    if (loadAffectingContributions.isEmpty() || maxVisibleItems <= 0) return null

    val visibleItems = loadAffectingContributions.take(maxVisibleItems).mapNotNull { contribution ->
        val sign = when (contribution.behavior) {
            RackItemBehavior.ADDED_RESISTANCE -> "+"
            RackItemBehavior.COUNTERWEIGHT -> "-"
            RackItemBehavior.DISPLAY_ONLY -> return@mapNotNull null
        }
        "${contribution.itemName} $sign${formatWeight(contribution.weightKg, weightUnit)}"
    }

    if (visibleItems.isEmpty()) return null

    val hiddenCount = loadAffectingContributions.size - visibleItems.size
    val parts = if (hiddenCount > 0) {
        val localizedMore = moreTemplate
            .replace("%1\$d", hiddenCount.toString())
            .replace("%d", hiddenCount.toString())
        visibleItems + localizedMore
    } else {
        visibleItems
    }

    return parts.joinToString(separator = ", ")
}
