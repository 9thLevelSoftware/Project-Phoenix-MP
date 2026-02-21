package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import kotlin.math.max

/**
 * Card displaying a single rep's metrics and force sparkline.
 *
 * Layout:
 * +------------------------------------------+
 * | Rep 3                           [warmup] |  <- Title row: rep number, optional warmup badge
 * +------------------------------------------+
 * | [====== ForceSparkline ================] |  <- Full-width sparkline
 * +------------------------------------------+
 * | Peak Force    | Concentric | Eccentric   |  <- Metric labels
 * | 45.2 kg       | 1.8s       | 2.1s        |  <- Metric values
 * +------------------------------------------+
 *
 * @param repData The rep metric data to display
 * @param weightUnit User's preferred weight unit (kg or lb)
 * @param formatWeight Function to format weight values with units
 * @param modifier Modifier for layout customization
 */
@Composable
fun RepReplayCard(
    repData: RepMetricData,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    // Combine concentric + eccentric loads for continuous curve
    val combinedForceData = repData.concentricLoadsA + repData.eccentricLoadsA

    // Peak index is at the transition point (end of concentric phase)
    val peakIndex = if (repData.concentricLoadsA.isNotEmpty()) {
        repData.concentricLoadsA.size - 1
    } else {
        null
    }

    // Calculate peak force (max of A and B cables)
    val peakForce = max(repData.peakForceA, repData.peakForceB)

    // Format durations as seconds with 1 decimal
    val concentricSeconds = repData.concentricDurationMs / 1000f
    val eccentricSeconds = repData.eccentricDurationMs / 1000f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small)
        ) {
            // Title row: Rep number + warmup badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Rep ${repData.repNumber}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (repData.isWarmup) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "warmup",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Force sparkline
            ForceSparkline(
                forceData = combinedForceData,
                peakIndex = peakIndex,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Peak Force
                MetricColumn(
                    label = "Peak Force",
                    value = formatWeight(peakForce, weightUnit)
                )

                // Concentric duration
                MetricColumn(
                    label = "Concentric",
                    value = "${KmpUtils.formatFloat(concentricSeconds, 1)}s"
                )

                // Eccentric duration
                MetricColumn(
                    label = "Eccentric",
                    value = "${KmpUtils.formatFloat(eccentricSeconds, 1)}s"
                )
            }
        }
    }
}

/**
 * Helper composable for displaying a labeled metric value.
 */
@Composable
private fun MetricColumn(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
