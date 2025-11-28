package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WorkoutMetric

/**
 * Material 3 Expressive Workout Metrics Detail Chart
 * Visualizes time-series workout data: Load A & B, Position A & B, Power
 * Uses Vico for multi-line chart with Material 3 theming
 *
 * TODO: Requires Vico charts library (com.patrykandpatrick.vico)
 * Verify KMP compatibility before implementing
 */
@Composable
fun WorkoutMetricsDetailChart(
    metrics: List<WorkoutMetric>,
    modifier: Modifier = Modifier,
    showLoad: Boolean = true,
    showPosition: Boolean = true,
    showPower: Boolean = true
) {
    // Data validation
    if (metrics.isEmpty()) {
        EmptyChartState(
            message = "No workout metrics available",
            modifier = modifier
        )
        return
    }

    // TODO: Implement with Vico when KMP support confirmed
    EmptyChartState(
        message = "Workout metrics chart pending (Vico)",
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
            .height(320.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ShowChart,
                contentDescription = "No workout metrics available",
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
