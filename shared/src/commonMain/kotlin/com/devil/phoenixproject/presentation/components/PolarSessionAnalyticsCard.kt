package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.ble.PolarH10BleManager
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.ui.theme.Spacing
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Enhanced Polar H10 Session Analytics Card.
 *
 * Displays post-set and post-workout cardiac performance metrics (Avg/Max/Min HR, RMSSD, RR),
 * 3D Torso Biomechanics (max hip-hinge pitch & lateral roll asymmetry), and the 5-minute
 * Heart Rate Recovery (HRR) cooldown protocol with real-time countdown, drop tracking, and control.
 */
@Composable
fun PolarSessionAnalyticsCard(
    sessionId: String? = null,
    modifier: Modifier = Modifier,
    polarBleManager: PolarH10BleManager = koinInject(),
) {
    val connectionState by polarBleManager.connectionState.collectAsState()
    val isCooldownActive by polarBleManager.isCooldownActive.collectAsState()
    val cooldownSecondsRemaining by polarBleManager.cooldownSecondsRemaining.collectAsState()
    val cooldownHrr60 by polarBleManager.cooldownHrr60.collectAsState()
    val cooldownHrr300 by polarBleManager.cooldownHrr300.collectAsState()
    val cooldownStartHr by polarBleManager.cooldownStartHeartRate.collectAsState()
    val latestSummary by polarBleManager.latestSessionSummary.collectAsState()
    val hrMeasurement by polarBleManager.hrMeasurement.collectAsState()

    val summary = latestSummary ?: remember(sessionId) {
        sessionId?.let { polarBleManager.getSessionSummary(it) }
    }

    val isConnected = connectionState is ConnectionState.Connected
    val hasCardiacData = summary?.avgHeartRateBpm != null || summary?.maxHeartRateBpm != null || isCooldownActive
    val hasBiomechanicsData = summary?.maxTorsoPitchDeg != null || summary?.maxTorsoRollDeg != null

    // If no Polar data and not connected or in cooldown, hide the card
    if (!hasCardiacData && !hasBiomechanicsData && !isConnected && !isCooldownActive) {
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCooldownActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFE91E63), Color(0xFFFF5252))
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Polar H10",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "POLAR H10 ANALYTICS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = if (isCooldownActive) "Recovery Cooldown Active" else "Cardiac & Biomechanics Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = if (isConnected) "Connected" else "Logged",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Section 1: Heart Rate Stats
            if (summary?.avgHeartRateBpm != null || summary?.maxHeartRateBpm != null || summary?.minHeartRateBpm != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.avgHeartRateBpm?.let { avg ->
                        MetricTile(
                            label = "Average HR",
                            value = "$avg",
                            unit = "bpm",
                            icon = Icons.Default.Favorite,
                            tint = Color(0xFFE91E63),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.maxHeartRateBpm?.let { max ->
                        MetricTile(
                            label = "Peak HR",
                            value = "$max",
                            unit = "bpm",
                            icon = Icons.Default.TrendingUp,
                            tint = Color(0xFFFF5722),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.minHeartRateBpm?.let { min ->
                        MetricTile(
                            label = "Min HR",
                            value = "$min",
                            unit = "bpm",
                            icon = Icons.Default.TrendingDown,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 2: HRV & Recovery Metrics
            if (summary?.rmssdMs != null || summary?.avgRrIntervalMs != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.rmssdMs?.let { rmssd ->
                        MetricTile(
                            label = "HRV (RMSSD)",
                            value = "${rmssd.roundToInt()}",
                            unit = "ms",
                            subtext = if (rmssd >= 50f) "High Vagal Tone" else if (rmssd >= 25f) "Moderate" else "High Strain",
                            icon = Icons.Default.Speed,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.avgRrIntervalMs?.let { rr ->
                        MetricTile(
                            label = "Avg RR Beat",
                            value = "${rr.roundToInt()}",
                            unit = "ms",
                            icon = Icons.Default.Timer,
                            tint = Color(0xFF9C27B0),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 3: Torso Biomechanics Posture & Asymmetry
            if (summary?.maxTorsoPitchDeg != null || summary?.maxTorsoRollDeg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.maxTorsoPitchDeg?.let { pitch ->
                        MetricTile(
                            label = "Max Hip Hinge",
                            value = "${pitch.roundToInt()}°",
                            unit = "pitch",
                            subtext = "Sagittal forward bend",
                            icon = Icons.Default.Accessibility,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.maxTorsoRollDeg?.let { roll ->
                        MetricTile(
                            label = "Max Lateral Tilt",
                            value = "${roll.roundToInt()}°",
                            unit = "roll",
                            subtext = "Coronal asymmetry",
                            icon = Icons.Default.CompareArrows,
                            tint = Color(0xFF00BCD4),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 4: 5-Minute Heart Rate Recovery (HRR) Protocol Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCooldownActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                ),
                border = BorderStroke(
                    1.dp,
                    if (isCooldownActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "5-MIN POST-WORKOUT COOLDOWN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (isCooldownActive) {
                            val mins = cooldownSecondsRemaining / 60
                            val secs = cooldownSecondsRemaining % 60
                            val formattedTime = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (isCooldownActive) {
                        val progress = (300 - cooldownSecondsRemaining) / 300f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )

                        val currentHr = hrMeasurement?.hr
                        val startHr = cooldownStartHr
                        if (currentHr != null && startHr != null) {
                            val drop = (startHr - currentHr).coerceAtLeast(0)
                            Text(
                                text = "Peak: $startHr bpm  ➔  Current: $currentHr bpm  (Drop: -$drop bpm)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1-min HRR status
                            if (cooldownHrr60 != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "1m HRR: -${cooldownHrr60} bpm",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4CAF50),
                                        )
                                    }
                                }
                            } else {
                                val secTo60 = (60 - (300 - cooldownSecondsRemaining)).coerceAtLeast(0)
                                Text(
                                    text = "Measuring 1m HRR in ${secTo60}s...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            OutlinedButton(
                                onClick = { polarBleManager.stopCooldown() },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "Finish Cooldown",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    } else {
                        // Cooldown is NOT active
                        val recordedHrr60 = cooldownHrr60 ?: summary?.hrr60Bpm
                        val recordedHrr300 = cooldownHrr300 ?: summary?.hrr300Bpm

                        if (recordedHrr60 != null || recordedHrr300 != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                recordedHrr60?.let { hrr ->
                                    val rating = if (hrr >= 25) "Excellent" else if (hrr >= 15) "Good" else "Moderate"
                                    RecoveryChip(
                                        label = "1-Min Recovery",
                                        drop = hrr,
                                        rating = rating,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                recordedHrr300?.let { hrr ->
                                    val rating = if (hrr >= 45) "Excellent" else if (hrr >= 30) "Good" else "Moderate"
                                    RecoveryChip(
                                        label = "5-Min Recovery",
                                        drop = hrr,
                                        rating = rating,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else if (isConnected) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Track your autonomic cardiac recovery for 5 minutes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Button(
                                    onClick = { polarBleManager.triggerCooldown(sessionId) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Start Cooldown",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    tint: Color,
    subtext: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (subtext != null) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RecoveryChip(
    label: String,
    drop: Int,
    rating: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF4CAF50).copy(alpha = 0.1f),
        border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "-$drop bpm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50),
                )
                Text(
                    text = "($rating)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                )
            }
        }
    }
}
