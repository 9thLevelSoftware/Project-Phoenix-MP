package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ReadinessResult
import com.devil.phoenixproject.domain.model.ReadinessStatus
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.SmartInsightsWeightText
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.readiness_acwr_value
import projectphoenix.shared.generated.resources.readiness_acute_7d
import projectphoenix.shared.generated.resources.readiness_chronic_avg
import projectphoenix.shared.generated.resources.readiness_insufficient_data
import projectphoenix.shared.generated.resources.readiness_rec_green
import projectphoenix.shared.generated.resources.readiness_rec_red
import projectphoenix.shared.generated.resources.readiness_rec_yellow
import projectphoenix.shared.generated.resources.readiness_status_green
import projectphoenix.shared.generated.resources.readiness_status_red
import projectphoenix.shared.generated.resources.readiness_status_yellow
import projectphoenix.shared.generated.resources.smart_insights_readiness_title

/**
 * Traffic-light readiness card showing ACWR-based training readiness.
 *
 * Displays:
 * - Score (0-100) with circular progress indicator
 * - Status badge (GREEN/YELLOW/RED) with color
 * - ACWR ratio
 * - Acute vs Chronic volume comparison
 * - "Insufficient data" state for new users (<28 days history)
 */
@Composable
fun ReadinessBriefingCard(
    readinessResult: ReadinessResult,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                stringResource(Res.string.smart_insights_readiness_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (readinessResult) {
                is ReadinessResult.InsufficientData -> InsufficientDataContent()
                is ReadinessResult.Ready -> ReadyContent(readinessResult, weightUnit)
            }
        }
    }
}

@Composable
private fun InsufficientDataContent() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(Res.string.readiness_insufficient_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyContent(result: ReadinessResult.Ready, weightUnit: WeightUnit) {
    val unitLabel = SmartInsightsWeightText.unitLabel(weightUnit)
    val readinessColors = AccessibilityTheme.colors
    val statusColor = when (result.status) {
        ReadinessStatus.GREEN -> readinessColors.statusGreen
        ReadinessStatus.YELLOW -> readinessColors.statusYellow
        ReadinessStatus.RED -> readinessColors.statusRed
    }

    val statusLabel = stringResource(
        when (result.status) {
            ReadinessStatus.GREEN -> Res.string.readiness_status_green
            ReadinessStatus.YELLOW -> Res.string.readiness_status_yellow
            ReadinessStatus.RED -> Res.string.readiness_status_red
        },
    )

    val recommendation = stringResource(
        when (result.status) {
            ReadinessStatus.GREEN -> Res.string.readiness_rec_green
            ReadinessStatus.YELLOW -> Res.string.readiness_rec_yellow
            ReadinessStatus.RED -> Res.string.readiness_rec_red
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Circular score indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp),
        ) {
            CircularProgressIndicator(
                progress = { result.score / 100f },
                modifier = Modifier.fillMaxSize(),
                color = statusColor,
                trackColor = statusColor.copy(alpha = 0.15f),
                strokeWidth = 6.dp,
            )
            Text(
                text = "${result.score}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Status and ACWR details
        Column(modifier = Modifier.weight(1f)) {
            // Status badge
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ACWR ratio
            Text(
                text = stringResource(Res.string.readiness_acwr_value, KmpUtils.formatFloat(result.acwr, 2)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Acute vs Chronic volume comparison
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VolumeColumn(
            label = stringResource(Res.string.readiness_acute_7d),
            valueText = SmartInsightsWeightText.volumeWithUnit(result.acuteVolumeKg, weightUnit, unitLabel),
            modifier = Modifier.weight(1f),
        )
        VolumeColumn(
            label = stringResource(Res.string.readiness_chronic_avg),
            valueText = SmartInsightsWeightText.volumeWithUnit(result.chronicWeeklyAvgKg, weightUnit, unitLabel),
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Recommendation
    Text(
        text = recommendation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VolumeColumn(label: String, valueText: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
