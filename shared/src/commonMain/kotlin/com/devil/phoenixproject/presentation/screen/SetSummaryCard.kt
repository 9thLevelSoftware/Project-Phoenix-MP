package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.RpeIndicator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Enhanced Set Summary Card - matches official Vitruvian app design
 * Shows detailed metrics: reps, volume, mode, peak/avg forces, duration, energy
 */
@Composable
fun SetSummaryCard(
    summary: WorkoutState.SetSummary,
    workoutMode: String,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
    onContinue: () -> Unit,
    autoplayEnabled: Boolean,
    summaryCountdownSeconds: Int,  // Configurable countdown duration (0 = Off, no auto-continue)
    onRpeLogged: ((Int) -> Unit)? = null,  // Optional RPE callback
    isHistoryView: Boolean = false,  // Hide interactive elements when viewing from history
    savedRpe: Int? = null,  // Show saved RPE value in history view
    buttonLabel: String = "Done"  // Contextual label: "Next Set", "Next Exercise", "Complete Routine"
) {
    // State for RPE tracking
    var loggedRpe by remember { mutableStateOf<Int?>(null) }

    // Issue #142: Use a unique key derived from the summary to ensure countdown resets for each new set.
    // Using durationMs and repCount as a composite identifier since these are unique per set completion.
    val summaryKey = remember(summary) { "${summary.durationMs}_${summary.repCount}_${summary.totalVolumeKg}" }

    // Auto-continue countdown - reset when summary changes
    var autoCountdown by remember(summaryKey) {
        mutableStateOf(if (autoplayEnabled && summaryCountdownSeconds > 0) summaryCountdownSeconds else -1)
    }

    // Issue #142: Auto-advance countdown for routine progression.
    // The summaryKey ensures this effect restarts for each unique set completion.
    // Note: LaunchedEffect is automatically cancelled when composable leaves composition,
    // so we don't need explicit isActive checks - delay() will throw CancellationException.
    LaunchedEffect(summaryKey, autoplayEnabled, summaryCountdownSeconds) {
        if (autoplayEnabled && summaryCountdownSeconds > 0 && !isHistoryView) {
            autoCountdown = summaryCountdownSeconds
            while (autoCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                autoCountdown--
            }
            // Countdown completed - advance to next set/exercise
            if (autoCountdown == 0) {
                onContinue()
            }
        }
    }

    // Calculate display values
    val displayReps = summary.repCount
    val totalVolumeDisplay = kgToDisplay(summary.totalVolumeKg, weightUnit)
    val heaviestLiftDisplay = kgToDisplay(summary.heaviestLiftKgPerCable, weightUnit)
    val durationSeconds = (summary.durationMs / 1000).toInt()
    val durationFormatted = "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}"

    // Peak/Avg forces - take max of both cables for display
    val peakConcentric = kgToDisplay(maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB), weightUnit)
    val peakEccentric = kgToDisplay(maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB), weightUnit)
    val avgConcentric = kgToDisplay(maxOf(summary.avgForceConcentricA, summary.avgForceConcentricB), weightUnit)
    val avgEccentric = kgToDisplay(maxOf(summary.avgForceEccentricA, summary.avgForceEccentricB), weightUnit)

    val unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)  // Reduced from 12dp to fit more content
    ) {
        // Gradient header with Total Reps and Total Volume
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .padding(16.dp)  // Reduced from 20dp to fit more content
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Total reps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        )
                        Text(
                            "$displayReps",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Total volume ($unitLabel)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        )
                        Text(
                            "${totalVolumeDisplay.roundToInt()}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Stats Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)  // Reduced from 8dp to fit more content
        ) {
            // Row 1: Mode and Heaviest Lift
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    label = "Mode",
                    value = workoutMode,
                    icon = Icons.Default.GridView,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    label = "Set Weight",
                    value = "${heaviestLiftDisplay.roundToInt()}",
                    unit = "($unitLabel/cable)",
                    icon = Icons.Default.FitnessCenter,
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: Peak Force (concentric/eccentric)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryForceCard(
                    label = "Peak Dynamic ($unitLabel)",
                    concentricValue = peakConcentric,
                    eccentricValue = peakEccentric,
                    modifier = Modifier.weight(1f)
                )
                SummaryForceCard(
                    label = "Avg Active ($unitLabel)",
                    concentricValue = avgConcentric,
                    eccentricValue = avgEccentric,
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Duration and Energy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    label = "Duration",
                    value = durationFormatted,
                    unit = "sec",
                    icon = Icons.Default.Timer,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    label = "Energy",
                    value = "${summary.estimatedCalories.roundToInt()}",
                    unit = "(kCal)",
                    icon = Icons.Default.LocalFireDepartment,
                    modifier = Modifier.weight(1f)
                )
            }

            // Echo Mode Phase Breakdown
            if (summary.isEchoMode && (summary.warmupAvgWeightKg > 0 || summary.workingAvgWeightKg > 0)) {
                EchoPhaseBreakdownCard(
                    warmupReps = summary.warmupReps,
                    workingReps = summary.workingReps,
                    burnoutReps = summary.burnoutReps,
                    warmupAvgWeight = kgToDisplay(summary.warmupAvgWeightKg, weightUnit),
                    workingAvgWeight = kgToDisplay(summary.workingAvgWeightKg, weightUnit),
                    burnoutAvgWeight = kgToDisplay(summary.burnoutAvgWeightKg, weightUnit),
                    peakWeight = kgToDisplay(summary.peakWeightKg, weightUnit),
                    unitLabel = unitLabel
                )
            }

            // Rep Quality section (only shown for Phoenix+ tier when quality data is available)
            summary.qualitySummary?.let { quality ->
                QualityStatsSection(quality = quality)
            }

            // RPE section - show read-only in history view, interactive in live view
            if (isHistoryView && savedRpe != null) {
                // Show saved RPE as read-only
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),  // Reduced from 16dp to fit more content
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "RPE",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "$savedRpe/10",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (!isHistoryView && onRpeLogged != null) {
                // RPE Capture (optional) - shown if callback is provided in live view
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),  // Reduced from 16dp to fit more content
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "How hard was that?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Log your perceived exertion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RpeIndicator(
                            currentRpe = loggedRpe,
                            onRpeChanged = { rpe ->
                                loggedRpe = rpe
                                onRpeLogged(rpe)
                            }
                        )
                    }
                }
            }
        }

        // Done/Continue button - only show in live view
        if (!isHistoryView) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (autoplayEnabled && summaryCountdownSeconds > 0 && autoCountdown > 0) {
                        "$buttonLabel ($autoCountdown)"
                    } else {
                        buttonLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * Individual stat card for the summary grid
 */
@Composable
private fun SummaryStatCard(
    label: String,
    value: String,
    unit: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)  // Reduced from 16dp to fit more content
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))  // Reduced from 8dp to fit more content
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                unit?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Force card showing concentric (up arrow) and eccentric (down arrow) values
 */
@Composable
private fun SummaryForceCard(
    label: String,
    concentricValue: Float,
    eccentricValue: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)  // Reduced from 16dp to fit more content
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))  // Reduced from 8dp to fit more content
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Concentric (lifting) with up arrow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${concentricValue.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " \u2191", // Up arrow
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Eccentric (lowering) with down arrow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${eccentricValue.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " \u2193", // Down arrow
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * Echo Mode Phase Breakdown Card
 * Shows average weight per phase (warmup, working, burnout) with rep counts
 */
@Composable
private fun EchoPhaseBreakdownCard(
    warmupReps: Int,
    workingReps: Int,
    burnoutReps: Int,
    warmupAvgWeight: Float,
    workingAvgWeight: Float,
    burnoutAvgWeight: Float,
    peakWeight: Float,
    unitLabel: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Echo Phase Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Peak: ${peakWeight.roundToInt()} $unitLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Phase breakdown row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Warmup Phase
                if (warmupReps > 0 || warmupAvgWeight > 0) {
                    PhaseStatColumn(
                        phaseName = "Warmup",
                        reps = warmupReps,
                        avgWeight = warmupAvgWeight,
                        unitLabel = unitLabel,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // Working Phase
                PhaseStatColumn(
                    phaseName = "Working",
                    reps = workingReps,
                    avgWeight = workingAvgWeight,
                    unitLabel = unitLabel,
                    color = MaterialTheme.colorScheme.primary,
                    isPrimary = true
                )

                // Burnout Phase
                if (burnoutReps > 0 || burnoutAvgWeight > 0) {
                    PhaseStatColumn(
                        phaseName = "Burnout",
                        reps = burnoutReps,
                        avgWeight = burnoutAvgWeight,
                        unitLabel = unitLabel,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * Individual phase stat column for Echo breakdown
 */
@Composable
private fun PhaseStatColumn(
    phaseName: String,
    reps: Int,
    avgWeight: Float,
    unitLabel: String,
    color: Color,
    isPrimary: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            phaseName,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            "${avgWeight.roundToInt()}",
            style = if (isPrimary) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "$unitLabel/cable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (reps > 0) {
            Text(
                "$reps reps",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

// ===== Rep Quality Section =====

/**
 * Quality color based on score (0-100)
 */
private fun qualityColor(score: Int): Color = when {
    score >= 95 -> Color(0xFF00E676)  // Bright green (excellent)
    score >= 80 -> Color(0xFF43A047)  // Green (good)
    score >= 60 -> Color(0xFFFDD835)  // Yellow (fair)
    score >= 40 -> Color(0xFFFF9800)  // Orange (needs work)
    else -> Color(0xFFE53935)         // Red (poor)
}

/**
 * Rep Quality stats section with sparkline, swipeable radar chart, trend, and improvement tip.
 * Shown after set completion for Phoenix+ tier users.
 */
@Composable
private fun QualityStatsSection(quality: SetQualitySummary) {
    var showRadar by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with trend indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Rep Quality",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val trendIcon = when (quality.trend) {
                        QualityTrend.IMPROVING -> Icons.Default.TrendingUp
                        QualityTrend.STABLE -> Icons.Default.TrendingFlat
                        QualityTrend.DECLINING -> Icons.Default.TrendingDown
                    }
                    val trendColor = when (quality.trend) {
                        QualityTrend.IMPROVING -> Color(0xFF43A047)
                        QualityTrend.STABLE -> Color.Gray
                        QualityTrend.DECLINING -> Color(0xFFE53935)
                    }
                    Icon(
                        trendIcon,
                        contentDescription = quality.trend.name,
                        modifier = Modifier.size(18.dp),
                        tint = trendColor
                    )
                }
                // Trend label
                val trendLabel = when (quality.trend) {
                    QualityTrend.IMPROVING -> "Improving"
                    QualityTrend.STABLE -> "Stable"
                    QualityTrend.DECLINING -> "Declining"
                }
                val trendLabelColor = when (quality.trend) {
                    QualityTrend.IMPROVING -> Color(0xFF43A047)
                    QualityTrend.STABLE -> Color.Gray
                    QualityTrend.DECLINING -> Color(0xFFE53935)
                }
                Text(
                    trendLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = trendLabelColor
                )
            }

            // Stats row: Average (large), Best, Worst
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Average score (large, color-coded)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Average",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${quality.averageScore}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = qualityColor(quality.averageScore)
                    )
                }
                // Best rep
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Best",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "#${quality.bestRepNumber}: ${quality.bestScore}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = qualityColor(quality.bestScore)
                    )
                }
                // Worst rep
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Worst",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "#${quality.worstRepNumber}: ${quality.worstScore}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = qualityColor(quality.worstScore)
                    )
                }
            }

            // Swipeable content: sparkline vs radar chart
            AnimatedContent(
                targetState = showRadar,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRadar = !showRadar }
            ) { isRadar ->
                if (isRadar) {
                    RadarChart(quality = quality)
                } else {
                    QualitySparkline(quality = quality)
                }
            }

            // Swipe hint
            Text(
                text = if (showRadar) "Tap for sparkline" else "Tap for component radar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            // Weakest component improvement tip
            WeakestComponentTip(quality = quality)
        }
    }
}

/**
 * Mini sparkline chart showing per-rep quality scores.
 */
@Composable
private fun QualitySparkline(quality: SetQualitySummary) {
    val scores = quality.repScores.map { it.composite }
    if (scores.isEmpty()) return

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val padding = 4.dp.toPx()

        val effectiveWidth = canvasWidth - padding * 2
        val effectiveHeight = canvasHeight - padding * 2

        val xStep = if (scores.size > 1) effectiveWidth / (scores.size - 1) else effectiveWidth / 2
        val minScore = 0f
        val maxScore = 100f

        val points = scores.mapIndexed { index, score ->
            val x = padding + if (scores.size > 1) index * xStep else effectiveWidth / 2
            val y = padding + effectiveHeight * (1f - (score - minScore) / (maxScore - minScore))
            Offset(x, y)
        }

        // Draw line connecting points
        for (i in 0 until points.size - 1) {
            val startColor = qualityColor(scores[i])
            val endColor = qualityColor(scores[i + 1])
            // Use average color for the segment
            val segColor = Color(
                red = (startColor.red + endColor.red) / 2f,
                green = (startColor.green + endColor.green) / 2f,
                blue = (startColor.blue + endColor.blue) / 2f,
                alpha = 1f
            )
            drawLine(
                color = segColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 2.dp.toPx()
            )
        }

        // Draw dots at each point
        points.forEachIndexed { index, point ->
            drawCircle(
                color = qualityColor(scores[index]),
                radius = 3.dp.toPx(),
                center = point
            )
        }
    }
}

/**
 * Radar/spider chart showing average component scores across all reps in the set.
 * Four axes: ROM (max 30), Velocity (max 25), Eccentric (max 25), Smoothness (max 20).
 * Uses a Box overlay approach to place Compose Text labels around the Canvas chart.
 */
@Composable
private fun RadarChart(quality: SetQualitySummary) {
    if (quality.repScores.isEmpty()) return

    // Average component scores across all reps
    val avgRom = quality.repScores.map { it.romScore }.average().toFloat()
    val avgVelocity = quality.repScores.map { it.velocityScore }.average().toFloat()
    val avgEccentric = quality.repScores.map { it.eccentricControlScore }.average().toFloat()
    val avgSmoothness = quality.repScores.map { it.smoothnessScore }.average().toFloat()

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val fillColor = Color(0xFF43A047).copy(alpha = 0.25f)
    val strokeColor = Color(0xFF43A047)

    // Axes: ROM (top), Velocity (right), Eccentric (bottom), Smoothness (left)
    val axisValues = listOf(avgRom / 30f, avgVelocity / 25f, avgEccentric / 25f, avgSmoothness / 20f)
    val numAxes = 4

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top label: ROM
        Text(
            "ROM: ${formatFloat(avgRom, 1)}/30",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left label: Smoothness
            Text(
                "${formatFloat(avgSmoothness, 1)}/20\nSmooth",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp)
            )

            // Canvas for radar chart (no text)
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
            ) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val radius = minOf(centerX, centerY) - 4.dp.toPx()

                val angleStep = (2.0 * kotlin.math.PI / numAxes).toFloat()
                val startAngle = (-kotlin.math.PI / 2.0).toFloat()

                // Draw grid polygons (25%, 50%, 75%, 100%)
                for (level in listOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                    val gridPath = Path()
                    for (i in 0 until numAxes) {
                        val angle = startAngle + i * angleStep
                        val x = centerX + radius * level * cos(angle)
                        val y = centerY + radius * level * sin(angle)
                        if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                    }
                    gridPath.close()
                    drawPath(gridPath, color = gridColor, style = Stroke(width = 1.dp.toPx()))
                }

                // Draw axis lines
                for (i in 0 until numAxes) {
                    val angle = startAngle + i * angleStep
                    val endX = centerX + radius * cos(angle)
                    val endY = centerY + radius * sin(angle)
                    drawLine(
                        color = gridColor,
                        start = Offset(centerX, centerY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Draw data polygon
                val dataPath = Path()
                for (i in 0 until numAxes) {
                    val angle = startAngle + i * angleStep
                    val normalizedValue = axisValues[i].coerceIn(0f, 1f)
                    val x = centerX + radius * normalizedValue * cos(angle)
                    val y = centerY + radius * normalizedValue * sin(angle)
                    if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
                }
                dataPath.close()
                drawPath(dataPath, color = fillColor)
                drawPath(dataPath, color = strokeColor, style = Stroke(width = 2.dp.toPx()))

                // Draw data points
                for (i in 0 until numAxes) {
                    val angle = startAngle + i * angleStep
                    val normalizedValue = axisValues[i].coerceIn(0f, 1f)
                    val x = centerX + radius * normalizedValue * cos(angle)
                    val y = centerY + radius * normalizedValue * sin(angle)
                    drawCircle(color = strokeColor, radius = 3.dp.toPx(), center = Offset(x, y))
                }
            }

            // Right label: Velocity
            Text(
                "${formatFloat(avgVelocity, 1)}/25\nVelocity",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp)
            )
        }

        // Bottom label: Eccentric
        Text(
            "Eccentric: ${formatFloat(avgEccentric, 1)}/25",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Shows a brief improvement tip for the weakest component.
 */
@Composable
private fun WeakestComponentTip(quality: SetQualitySummary) {
    if (quality.repScores.isEmpty()) return

    // Calculate average component scores as percentage of max
    val avgRom = quality.repScores.map { it.romScore }.average().toFloat()
    val avgVelocity = quality.repScores.map { it.velocityScore }.average().toFloat()
    val avgEccentric = quality.repScores.map { it.eccentricControlScore }.average().toFloat()
    val avgSmoothness = quality.repScores.map { it.smoothnessScore }.average().toFloat()

    data class Component(val name: String, val percentage: Float, val tip: String)

    val components = listOf(
        Component("ROM", avgRom / 30f, "Try to maintain consistent range of motion each rep"),
        Component("Velocity", avgVelocity / 25f, "Keep a steady tempo throughout the set"),
        Component("Eccentric", avgEccentric / 25f, "Focus on a controlled 2-second lowering phase"),
        Component("Smoothness", avgSmoothness / 20f, "Avoid jerky movements -- smooth and steady wins")
    )

    val weakest = components.minByOrNull { it.percentage } ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = "Tip",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                "${weakest.name}: ${weakest.tip}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * KMP-compatible float formatting helper
 * @param value The float value to format
 * @param decimals Number of decimal places
 * @return Formatted string
 */
internal fun formatFloat(value: Float, decimals: Int): String {
    val factor = 10f.pow(decimals)
    val rounded = (value * factor).roundToInt() / factor
    return if (decimals == 0) {
        rounded.roundToInt().toString()
    } else {
        val intPart = rounded.toInt()
        val decPart = ((rounded - intPart) * factor).roundToInt()
        "$intPart.${"$decPart".padStart(decimals, '0')}"
    }
}

internal fun Float.pow(n: Int): Float {
    var result = 1f
    repeat(n) { result *= this }
    return result
}
