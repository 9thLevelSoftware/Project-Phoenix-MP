package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.RpeIndicator
import kotlin.math.roundToInt

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
    val setWeightDisplay = kgToDisplay(summary.configuredWeightKgPerCable, weightUnit)

    // Debug logging for Issue #5 investigation
    co.touchlab.kermit.Logger.i { "WEIGHT_DEBUG[Summary]: configuredWeightKgPerCable=${summary.configuredWeightKgPerCable} kg → kgToDisplay → $setWeightDisplay ($weightUnit)" }
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
                    value = "${setWeightDisplay.roundToInt()}",
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
