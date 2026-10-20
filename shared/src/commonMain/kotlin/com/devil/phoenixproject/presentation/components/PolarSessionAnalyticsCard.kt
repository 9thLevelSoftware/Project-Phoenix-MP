package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.ble.PolarH10BleManager
import com.devil.phoenixproject.data.ble.PolarSessionSummary
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.MetricSample
import com.devil.phoenixproject.database.PolarBeatSample
import com.devil.phoenixproject.database.PolarEcgSegment
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.ui.theme.Spacing
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Enhanced Polar H10 Session Analytics Card.
 *
 * Displays post-set and post-workout cardiac performance metrics (Avg/Max/Min HR, RMSSD, RR),
 * 3D Torso Biomechanics (max hip-hinge pitch & lateral roll asymmetry), the 5-minute
 * Heart Rate Recovery (HRR) cooldown protocol with real-time countdown, drop tracking, and control,
 * plus 3 rich interactive Canvas visualizations:
 * 1. Continuous Heart Rate & Recovery Timeline with color-coded phase bands
 * 2. 3D Torso Biomechanics vs. Rep Cable Position
 * 3. Saved 130 Hz ECG Waveform Scrubber
 */
@Composable
fun PolarSessionAnalyticsCard(
    sessionId: String? = null,
    session: WorkoutSession? = null,
    modifier: Modifier = Modifier,
    polarBleManager: PolarH10BleManager = koinInject(),
    workoutRepository: WorkoutRepository = koinInject(),
) {
    val connectionState by polarBleManager.connectionState.collectAsState()
    val isCooldownActive by polarBleManager.isCooldownActive.collectAsState()
    val cooldownSecondsRemaining by polarBleManager.cooldownSecondsRemaining.collectAsState()
    val cooldownHrr60 by polarBleManager.cooldownHrr60.collectAsState()
    val cooldownHrr300 by polarBleManager.cooldownHrr300.collectAsState()
    val cooldownStartHr by polarBleManager.cooldownStartHeartRate.collectAsState()
    val latestSummary by polarBleManager.latestSessionSummary.collectAsState()
    val hrMeasurement by polarBleManager.hrMeasurement.collectAsState()

    val resolvedSessionId = sessionId ?: session?.id
    val fallbackSummary = remember(session, resolvedSessionId) {
        session?.let { s ->
            if (s.avgHeartRateBpm != null || s.maxHeartRateBpm != null || s.minHeartRateBpm != null ||
                s.avgRrIntervalMs != null || s.rmssdMs != null || s.hrr60Bpm != null ||
                s.hrr300Bpm != null || s.maxTorsoPitchDeg != null || s.maxTorsoRollDeg != null) {
                PolarSessionSummary(
                    avgHeartRateBpm = s.avgHeartRateBpm,
                    maxHeartRateBpm = s.maxHeartRateBpm,
                    minHeartRateBpm = s.minHeartRateBpm,
                    avgRrIntervalMs = s.avgRrIntervalMs,
                    rmssdMs = s.rmssdMs,
                    hrr60Bpm = s.hrr60Bpm,
                    hrr300Bpm = s.hrr300Bpm,
                    maxTorsoPitchDeg = s.maxTorsoPitchDeg,
                    maxTorsoRollDeg = s.maxTorsoRollDeg,
                )
            } else null
        } ?: resolvedSessionId?.let { polarBleManager.getSessionSummary(it) }
    }

    val summary = latestSummary ?: fallbackSummary

    // Historical samples queries for visualizations
    var beatSamples by remember(resolvedSessionId) { mutableStateOf<List<PolarBeatSample>>(emptyList()) }
    var ecgSegments by remember(resolvedSessionId) { mutableStateOf<List<PolarEcgSegment>>(emptyList()) }
    var metricSamples by remember(resolvedSessionId) { mutableStateOf<List<MetricSample>>(emptyList()) }

    LaunchedEffect(resolvedSessionId) {
        if (resolvedSessionId != null) {
            try {
                beatSamples = workoutRepository.getPolarBeatSamplesBySession(resolvedSessionId)
            } catch (e: Exception) {
                Logger.w { "Failed to load PolarBeatSamples: ${e.message}" }
            }
            try {
                ecgSegments = workoutRepository.getPolarEcgSegmentsBySession(resolvedSessionId)
            } catch (e: Exception) {
                Logger.w { "Failed to load PolarEcgSegments: ${e.message}" }
            }
            try {
                metricSamples = workoutRepository.getMetricSamplesBySession(resolvedSessionId)
            } catch (e: Exception) {
                Logger.w { "Failed to load MetricSamples: ${e.message}" }
            }
        }
    }

    val isConnected = connectionState is ConnectionState.Connected
    val hasCardiacData = summary?.avgHeartRateBpm != null || summary?.maxHeartRateBpm != null || isCooldownActive || beatSamples.isNotEmpty() || ecgSegments.isNotEmpty()
    val hasBiomechanicsData = summary?.maxTorsoPitchDeg != null || summary?.maxTorsoRollDeg != null || metricSamples.any { it.torsoPitchDeg != null || it.torsoRollDeg != null }

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
            val hasRecoveryData = summary?.hrr60Bpm != null || summary?.hrr300Bpm != null || isCooldownActive
            if (hasRecoveryData || isConnected) {
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
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        text = "${mins}:${secs.toString().padStart(2, '0')}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        if (isCooldownActive) {
                            // Active Cooldown Display
                            val currentHr = hrMeasurement?.hr
                            val dropSoFar = if (currentHr != null && cooldownStartHr != null) {
                                (cooldownStartHr!! - currentHr).coerceAtLeast(0)
                            } else null

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = "Starting HR: ${cooldownStartHr ?: "—"} bpm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Current HR: ${currentHr ?: "—"} bpm",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                dropSoFar?.let { drop ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = "-$drop bpm",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }

                            // Active progress bar (300s total)
                            val progress = 1f - (cooldownSecondsRemaining / 300f).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )

                            // Cancel Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { polarBleManager.stopCooldown() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cancel Cooldown")
                                }
                            }
                        } else {
                            // Historical or Post-Cooldown State
                            val hrr60 = summary?.hrr60Bpm ?: cooldownHrr60
                            val hrr300 = summary?.hrr300Bpm ?: cooldownHrr300

                            if (hrr60 != null || hrr300 != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    hrr60?.let { drop60 ->
                                        RecoveryChip(
                                            label = "1-Min Recovery (HRR-60)",
                                            drop = drop60,
                                            rating = when {
                                                drop60 >= 25 -> "Elite"
                                                drop60 >= 18 -> "Good"
                                                drop60 >= 12 -> "Fair"
                                                else -> "Elevated Risk"
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    hrr300?.let { drop300 ->
                                        RecoveryChip(
                                            label = "5-Min Recovery (HRR-300)",
                                            drop = drop300,
                                            rating = when {
                                                drop300 >= 50 -> "Excellent"
                                                drop300 >= 35 -> "Optimal"
                                                else -> "Slow"
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            } else if (isConnected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Track vagal tone reactivation & recovery slope.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            resolvedSessionId?.let { polarBleManager.triggerCooldown(it) }
                                        },
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

            // Section 5: Interactive Visualizations
            if (beatSamples.isNotEmpty()) {
                ContinuousHeartRateTimelineChart(samples = beatSamples)
            }

            if (metricSamples.any { it.torsoPitchDeg != null || it.torsoRollDeg != null }) {
                TorsoBiomechanicsVsCableChart(samples = metricSamples)
            }

            if (ecgSegments.isNotEmpty()) {
                SavedEcgWaveformScrubber(segments = ecgSegments)
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

/**
 * Chart 1 — Continuous HR & Recovery Timeline (`PolarBeatSample`).
 * Plots BPM and RMSSD over time with color-coded phase bands and interactive scrubber.
 */
@Composable
private fun ContinuousHeartRateTimelineChart(
    samples: List<PolarBeatSample>,
    modifier: Modifier = Modifier,
) {
    val validSamples = remember(samples) {
        samples.filter { it.hrBpm != null && it.hrBpm > 0 }
    }
    if (validSamples.size < 2) return

    val minTs = validSamples.first().timestampMs
    val maxTs = validSamples.last().timestampMs
    val durationMs = (maxTs - minTs).coerceAtLeast(1000L)

    val minHr = (validSamples.minOfOrNull { it.hrBpm!!.toInt() } ?: 60).coerceAtMost(50)
    val maxHr = ((validSamples.maxOfOrNull { it.hrBpm!!.toInt() } ?: 180) + 10).coerceAtLeast(minHr + 40)
    val hrRange = (maxHr - minHr).toFloat()

    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    val selectedSample = remember(scrubFraction, validSamples) {
        scrubFraction?.let { fraction ->
            val targetTs = minTs + (fraction * durationMs).toLong()
            validSamples.minByOrNull { abs(it.timestampMs - targetTs) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "CONTINUOUS HR & RECOVERY TIMELINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Text(
                    text = "${(durationMs / 60000)}m ${(durationMs % 60000) / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            // Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhaseLegendDot(color = Color(0xFFFFA726), label = "Warmup")
                PhaseLegendDot(color = Color(0xFFEF5350), label = "Working")
                PhaseLegendDot(color = Color(0xFF42A5F5), label = "Rest")
                PhaseLegendDot(color = Color(0xFF26A69A), label = "Cooldown")
                Spacer(Modifier.weight(1f))
                PhaseLegendDot(color = Color(0xFF00E676), label = "HRV", isLine = true)
            }

            // Scrub Info Bar
            if (selectedSample != null) {
                val elapsedSec = ((selectedSample.timestampMs - minTs) / 1000).coerceAtLeast(0)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${elapsedSec / 60}m ${elapsedSec % 60}s [${selectedSample.phase}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "HR: ${selectedSample.hrBpm} bpm",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5252),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                            selectedSample.rmssdMs?.let { rmssd ->
                                Text(
                                    text = "RMSSD: ${rmssd.roundToInt()} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            // Canvas Line Chart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                tryAwaitRelease()
                                scrubFraction = null
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { scrubFraction = null },
                            onDragCancel = { scrubFraction = null },
                            onHorizontalDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. Draw Phase Bands
                    var currentPhase = validSamples.first().phase
                    var spanStartTs = validSamples.first().timestampMs
                    val spans = mutableListOf<Triple<String, Long, Long>>()
                    for (s in validSamples) {
                        if (s.phase != currentPhase) {
                            spans.add(Triple(currentPhase, spanStartTs, s.timestampMs))
                            currentPhase = s.phase
                            spanStartTs = s.timestampMs
                        }
                    }
                    spans.add(Triple(currentPhase, spanStartTs, maxTs))

                    for (span in spans) {
                        val x0 = ((span.second - minTs).toFloat() / durationMs) * w
                        val x1 = ((span.third - minTs).toFloat() / durationMs) * w
                        val bandColor = when (span.first) {
                            "WARMUP" -> Color(0xFFFFA726).copy(alpha = 0.14f)
                            "WORKING_SET" -> Color(0xFFEF5350).copy(alpha = 0.20f)
                            "REST" -> Color(0xFF42A5F5).copy(alpha = 0.14f)
                            "POST_WORKOUT_COOLDOWN" -> Color(0xFF26A69A).copy(alpha = 0.22f)
                            else -> Color.Transparent
                        }
                        drawRect(color = bandColor, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), h))
                    }

                    // 2. Draw Horizontal Grid Baseline Lines
                    listOf(80, 120, 160).forEach { bpm ->
                        if (bpm in minHr..maxHr) {
                            val y = h - ((bpm - minHr) / hrRange) * h
                            drawLine(
                                color = Color(0xFF334155).copy(alpha = 0.6f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f,
                            )
                        }
                    }

                    // 3. RMSSD Curve (Secondary)
                    val rmssdPath = Path()
                    var rmssdStarted = false
                    val maxRmssd = 120f
                    for (s in validSamples) {
                        if (s.rmssdMs != null) {
                            val x = ((s.timestampMs - minTs).toFloat() / durationMs) * w
                            val y = h - (s.rmssdMs.toFloat().coerceIn(0f, maxRmssd) / maxRmssd) * (h * 0.7f)
                            if (!rmssdStarted) {
                                rmssdPath.moveTo(x, y)
                                rmssdStarted = true
                            } else {
                                rmssdPath.lineTo(x, y)
                            }
                        }
                    }
                    if (rmssdStarted) {
                        drawPath(
                            path = rmssdPath,
                            color = Color(0xFF00E676).copy(alpha = 0.5f),
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    }

                    // 4. Heart Rate Curve (Primary)
                    val hrPath = Path()
                    val hrFillPath = Path()
                    var hrStarted = false
                    for (s in validSamples) {
                        val hr = s.hrBpm!!.toInt()
                        val x = ((s.timestampMs - minTs).toFloat() / durationMs) * w
                        val y = h - ((hr - minHr) / hrRange) * h
                        if (!hrStarted) {
                            hrPath.moveTo(x, y)
                            hrFillPath.moveTo(x, h)
                            hrFillPath.lineTo(x, y)
                            hrStarted = true
                        } else {
                            hrPath.lineTo(x, y)
                            hrFillPath.lineTo(x, y)
                        }
                    }
                    if (hrStarted) {
                        hrFillPath.lineTo(w, h)
                        hrFillPath.close()

                        drawPath(
                            path = hrFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF5252).copy(alpha = 0.25f), Color.Transparent),
                                startY = 0f,
                                endY = h,
                            ),
                        )

                        drawPath(
                            path = hrPath,
                            color = Color(0xFFFF5252),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 5. Scrubber Indicator
                    scrubFraction?.let { fraction ->
                        val scrubX = fraction * w
                        drawLine(
                            color = Color.White.copy(alpha = 0.8f),
                            start = Offset(scrubX, 0f),
                            end = Offset(scrubX, h),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        selectedSample?.hrBpm?.let { hr ->
                            val dotY = h - ((hr.toInt() - minHr) / hrRange) * h
                            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(scrubX, dotY))
                            drawCircle(color = Color(0xFFFF5252), radius = 2.5.dp.toPx(), center = Offset(scrubX, dotY))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chart 2 — 3D Torso Biomechanics vs. Rep Position (`MetricSample`).
 * Plots intra-set torso pitch (hinge) and roll (tilt) synchronized with cable position/force.
 */
@Composable
private fun TorsoBiomechanicsVsCableChart(
    samples: List<MetricSample>,
    modifier: Modifier = Modifier,
) {
    val biomechSamples = remember(samples) {
        samples.filter { it.torsoPitchDeg != null || it.torsoRollDeg != null }
    }
    if (biomechSamples.isEmpty()) return

    val sampleCount = biomechSamples.size
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    val selectedSample = remember(scrubFraction, biomechSamples) {
        scrubFraction?.let { fraction ->
            val index = (fraction * (sampleCount - 1)).roundToInt().coerceIn(0, sampleCount - 1)
            biomechSamples[index]
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        imageVector = Icons.Default.Accessibility,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "3D TORSO BIOMECHANICS & CABLE POSITION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Text(
                    text = "$sampleCount reps/pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            // Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhaseLegendDot(color = Color(0xFFFF9800), label = "Hinge (Pitch°)")
                PhaseLegendDot(color = Color(0xFF00BCD4), label = "Tilt (Roll°)")
                PhaseLegendDot(color = Color(0xFFAB47BC), label = "Cable Pos", isLine = true)
            }

            // Scrub Info Bar
            if (selectedSample != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Hinge: ${(selectedSample.torsoPitchDeg?.let { (it * 10).roundToInt() / 10.0 } ?: 0.0)}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800),
                        )
                        Text(
                            text = "Tilt: ${(selectedSample.torsoRollDeg?.let { (it * 10).roundToInt() / 10.0 } ?: 0.0)}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00BCD4),
                        )
                        selectedSample.position?.let { pos ->
                            Text(
                                text = "Cable: ${(pos * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFAB47BC),
                            )
                        }
                    }
                }
            }

            // Canvas Plot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D1B2A))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                tryAwaitRelease()
                                scrubFraction = null
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { scrubFraction = null },
                            onDragCancel = { scrubFraction = null },
                            onHorizontalDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Center 0° baseline for roll/pitch reference
                    val midY = h * 0.65f
                    drawLine(
                        color = Color(0xFF1E3A5F),
                        start = Offset(0f, midY),
                        end = Offset(w, midY),
                        strokeWidth = 1f,
                    )

                    val maxPitch = 90f // 0° to 90° forward bend
                    val maxRoll = 30f  // ±30° lateral tilt

                    val stepX = w / (sampleCount - 1).coerceAtLeast(1).toFloat()

                    // 1. Cable Position (Dashed / fine purple line at top half)
                    val cablePath = Path()
                    var cableStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        s.position?.let { pos ->
                            val x = i * stepX
                            val y = (h * 0.45f) - (pos.toFloat().coerceIn(0f, 1f) * (h * 0.4f))
                            if (!cableStarted) {
                                cablePath.moveTo(x, y)
                                cableStarted = true
                            } else {
                                cablePath.lineTo(x, y)
                            }
                        }
                    }
                    if (cableStarted) {
                        drawPath(
                            path = cablePath,
                            color = Color(0xFFAB47BC).copy(alpha = 0.6f),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }

                    // 2. Torso Pitch (Hip Hinge, orange line)
                    val pitchPath = Path()
                    var pitchStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        s.torsoPitchDeg?.let { pitch ->
                            val x = i * stepX
                            val y = midY - (pitch.toFloat().coerceIn(0f, maxPitch) / maxPitch) * (midY - 10f)
                            if (!pitchStarted) {
                                pitchPath.moveTo(x, y)
                                pitchStarted = true
                            } else {
                                pitchPath.lineTo(x, y)
                            }
                        }
                    }
                    if (pitchStarted) {
                        drawPath(
                            path = pitchPath,
                            color = Color(0xFFFF9800),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 3. Torso Roll (Lateral Tilt, cyan line)
                    val rollPath = Path()
                    var rollStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        s.torsoRollDeg?.let { roll ->
                            val x = i * stepX
                            val y = midY - (roll.toFloat().coerceIn(-maxRoll, maxRoll) / maxRoll) * (h * 0.25f)
                            if (!rollStarted) {
                                rollPath.moveTo(x, y)
                                rollStarted = true
                            } else {
                                rollPath.lineTo(x, y)
                            }
                        }
                    }
                    if (rollStarted) {
                        drawPath(
                            path = rollPath,
                            color = Color(0xFF00BCD4),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 4. Scrubber Line
                    scrubFraction?.let { fraction ->
                        val scrubX = fraction * w
                        drawLine(
                            color = Color.White.copy(alpha = 0.8f),
                            start = Offset(scrubX, 0f),
                            end = Offset(scrubX, h),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chart 3 — Saved 130 Hz ECG Waveform Scrubber (`PolarEcgSegment`).
 * Renders parsed microvolt samples across clinical ECG grid with interactive window scrubber.
 */
@Composable
private fun SavedEcgWaveformScrubber(
    segments: List<PolarEcgSegment>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    val allSamples = remember(segments) {
        val list = mutableListOf<Int>()
        for (seg in segments) {
            val parts = seg.samplesUvCsv.split(',')
            for (p in parts) {
                p.trim().toIntOrNull()?.let { list.add(it) }
            }
        }
        list
    }
    if (allSamples.size < 10) return

    val windowSize = 260 // 2.0 seconds at 130 Hz
    var windowStart by remember { mutableStateOf(0) }
    val maxStart = (allSamples.size - windowSize).coerceAtLeast(0)

    val currentPhase = remember(windowStart, segments) {
        var accum = 0
        var phase = segments.first().phase
        for (seg in segments) {
            val count = seg.samplesUvCsv.count { it == ',' } + 1
            if (windowStart in accum until (accum + count)) {
                phase = seg.phase
                break
            }
            accum += count
        }
        phase
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "SAVED 130 HZ ECG WAVEFORM SCRUBBER",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                ) {
                    Text(
                        text = currentPhase,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Window details and slider
            val startSec = (windowStart / 130.0 * 10).roundToInt() / 10.0
            val endSec = (((windowStart + windowSize).coerceAtMost(allSamples.size)) / 130.0 * 10).roundToInt() / 10.0
            val totalSec = (allSamples.size / 130.0 * 10).roundToInt() / 10.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Time: ${startSec}s - ${endSec}s / ${totalSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text(
                    text = "${allSamples.size} samples @ 130 Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            if (maxStart > 0) {
                Slider(
                    value = windowStart.toFloat(),
                    onValueChange = { windowStart = it.roundToInt().coerceIn(0, maxStart) },
                    valueRange = 0f..maxStart.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
            }

            // ECG Grid & Trace Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF081422))
                    .border(0.5.dp, Color(0xFF1B3A5A), RoundedCornerShape(8.dp)),
            ) {
                val windowSlice = remember(windowStart, allSamples) {
                    allSamples.subList(windowStart, (windowStart + windowSize).coerceAtMost(allSamples.size))
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val midY = h / 2f

                    // 1. Draw Clinical ECG Grid
                    val minorStep = 10.dp.toPx()
                    var gx = 0f
                    while (gx < w) {
                        drawLine(color = Color(0xFF0E2238), start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 0.5f)
                        gx += minorStep
                    }
                    var gy = 0f
                    while (gy < h) {
                        drawLine(color = Color(0xFF0E2238), start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 0.5f)
                        gy += minorStep
                    }
                    val majorStep = 40.dp.toPx()
                    gx = 0f
                    while (gx < w) {
                        drawLine(color = Color(0xFF1B3A5A), start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 1f)
                        gx += majorStep
                    }
                    gy = 0f
                    while (gy < h) {
                        drawLine(color = Color(0xFF1B3A5A), start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 1f)
                        gy += majorStep
                    }

                    // Zero baseline center line
                    drawLine(color = Color(0xFF2A5580), start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1.5f)

                    if (windowSlice.size < 2) return@Canvas

                    // 2. Autoscale µV
                    val maxAbsUv = windowSlice.maxOfOrNull { abs(it) }?.toFloat()?.coerceAtLeast(800f) ?: 1500f
                    val scaleY = (h * 0.42f) / maxAbsUv
                    val stepX = w / (windowSlice.size - 1).toFloat()

                    // 3. Draw ECG Trace
                    val path = Path()
                    windowSlice.forEachIndexed { i, uv ->
                        val x = i * stepX
                        val y = (midY - (uv * scaleY)).coerceIn(1f, h - 1f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = Color(0xFF00E5FF),
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseLegendDot(
    color: Color,
    label: String,
    isLine: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isLine) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(2.dp)
                    .background(color),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
