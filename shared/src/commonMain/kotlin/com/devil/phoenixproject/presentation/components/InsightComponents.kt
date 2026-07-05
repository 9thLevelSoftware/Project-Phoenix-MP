package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Shared insight UI atoms used by both InsightsTab and SmartInsightsTab.
 * Extracted to eliminate the identical TimeframeBadge / section-header / context-block
 * duplicates that lived in each tab independently (audit finding analytics-history-5).
 */

/**
 * A pill badge displaying a timeframe label (e.g. "Last 4 weeks", "All-time PRs").
 * Uses secondaryContainer colour pair from the design-system palette.
 */
@Composable
fun TimeframeBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        )
    }
}

/**
 * Two-line section header used to separate insight sections (Snapshot / Trends /
 * Diagnostics / Actions). Unified from [InsightSectionHeader] (InsightsTab) and
 * [InsightHierarchyHeader] (SmartInsightsTab) which were structurally identical.
 */
@Composable
fun InsightSectionHeader(title: String, subtitle: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Metadata block describing an insight metric: definition, timeframe badge, and a
 * "So what?" action line. When [title] is non-null an additional bold title Text is
 * prepended, unifying [InsightMetadata] (3-arg, InsightsTab) and the 4-arg
 * [InsightContextBlock] (SmartInsightsTab) into a single composable.
 */
@Composable
fun InsightContextBlock(
    definition: String,
    timeframe: String,
    soWhat: String,
    title: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = definition,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeframeBadge(timeframe)
        Text(
            text = "So what? $soWhat",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}
