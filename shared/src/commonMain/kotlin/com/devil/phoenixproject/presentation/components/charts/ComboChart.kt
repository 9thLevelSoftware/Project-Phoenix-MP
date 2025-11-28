package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Combo Chart
 * Combines column and line charts for multi-metric comparisons
 * Perfect for comparing volume (columns) with weight progression (line)
 *
 * TODO: This component requires Vico chart library for KMP.
 * Verify: com.patrykandpatrick.vico:compose-m3 supports Kotlin Multiplatform
 * Original uses Vico with Material 3 theming.
 */
@Composable
fun ComboChart(
    columnData: List<Pair<String, Float>>, // Label to value pairs for columns
    lineData: List<Pair<String, Float>>, // Label to value pairs for line
    modifier: Modifier = Modifier,
    columnLabel: String = "Volume",
    lineLabel: String = "Weight"
) {
    // Data validation
    if (columnData.isEmpty() && lineData.isEmpty()) {
        EmptyChartState(
            message = "No data available",
            modifier = modifier
        )
        return
    }

    // TODO: Implement with Vico charts when KMP support confirmed
    EmptyChartState(
        message = "Combo chart implementation pending (Vico)",
        modifier = modifier
    )
}

@Composable
private fun EmptyChartState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = "No data available",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
