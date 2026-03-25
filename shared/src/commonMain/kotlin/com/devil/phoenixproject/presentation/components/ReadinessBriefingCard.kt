package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ReadinessResult
import com.devil.phoenixproject.domain.model.ReadinessStatus
import com.devil.phoenixproject.util.KmpUtils

// Phoenix palette readiness colors
private val ForgeGreen = Color(0xFF10B981)
private val GoldYellow = Color(0xFFF59E0B)
private val FlameRed = Color(0xFFDC2626)

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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Training Readiness",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (readinessResult) {
                is ReadinessResult.InsufficientData -> InsufficientDataContent()
                is ReadinessResult.Ready -> ReadyContent(readinessResult)
            }
        }
    }
}

@Composable
private fun InsufficientDataContent() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Train for 28+ days to unlock readiness insights",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadyContent(result: ReadinessResult.Ready) {
    val statusColor = when (result.status) {
        ReadinessStatus.GREEN -> ForgeGreen
        ReadinessStatus.YELLOW -> GoldYellow
        ReadinessStatus.RED -> FlameRed
    }

    val statusLabel = when (result.status) {
        ReadinessStatus.GREEN -> "Ready to Train"
        ReadinessStatus.YELLOW -> "Train with Caution"
        ReadinessStatus.RED -> "Consider Recovery"
    }

    val recommendation = when (result.status) {
        ReadinessStatus.GREEN -> "Your training load is well balanced. Push for progress today."
        ReadinessStatus.YELLOW -> "Your recent volume is slightly off balance. Consider a moderate session."
        ReadinessStatus.RED -> "Your acute load is high relative to your baseline. A lighter session or rest day is recommended."
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular score indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp)
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
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Status and ACWR details
        Column(modifier = Modifier.weight(1f)) {
            // Status badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ACWR ratio
            Text(
                text = "ACWR: ${KmpUtils.formatFloat(result.acwr, 2)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Acute vs Chronic volume comparison
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        VolumeColumn(
            label = "Acute (7d)",
            valueKg = result.acuteVolumeKg
        )
        VolumeColumn(
            label = "Chronic (avg/wk)",
            valueKg = result.chronicWeeklyAvgKg
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Recommendation
    Text(
        text = recommendation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VolumeColumn(label: String, valueKg: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${valueKg.toInt()} kg",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
