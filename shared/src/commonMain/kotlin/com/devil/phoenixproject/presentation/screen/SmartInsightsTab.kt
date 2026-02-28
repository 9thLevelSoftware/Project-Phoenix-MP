package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.SmartSuggestionsRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.SmartSuggestionsEngine
import com.devil.phoenixproject.domain.subscription.SubscriptionManager
import com.devil.phoenixproject.presentation.components.LockedFeatureOverlay
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Smart Insights Tab - Elite-only training insights powered by SmartSuggestionsEngine.
 *
 * Displays 5 insight sections:
 * 1. Weekly Volume per muscle group (SUGG-01)
 * 2. Push/Pull/Legs Balance Analysis (SUGG-02)
 * 3. Neglected Exercises (SUGG-03)
 * 4. Plateau Detection (SUGG-04)
 * 5. Time-of-Day Optimal Training Window (SUGG-05)
 *
 * Gated behind Elite tier subscription (SUGG-06 / GATE-03).
 */
@Composable
fun SmartInsightsTab(
    modifier: Modifier = Modifier
) {
    val subscriptionManager: SubscriptionManager = koinInject()
    val hasEliteAccess by subscriptionManager.hasEliteAccess.collectAsState()

    if (hasEliteAccess) {
        SmartInsightsContent(modifier = modifier)
    } else {
        LockedFeatureOverlay(
            featureName = "Smart Insights",
            featureDescription = "Unlock with Elite tier to get personalized training insights",
            onUpgradeClick = { /* Paywall not yet wired */ },
            modifier = modifier.padding(24.dp)
        )
    }
}

@Composable
private fun SmartInsightsContent(
    modifier: Modifier = Modifier
) {
    val repository: SmartSuggestionsRepository = koinInject()

    val nowMs = remember { currentTimeMillis() }
    val twentyEightDaysMs = 28L * 24 * 60 * 60 * 1000

    var isLoading by remember { mutableStateOf(true) }
    var sessionSummaries by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var exerciseLastPerformed by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var weightHistory by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sessionSummaries = repository.getSessionSummariesSince(nowMs - twentyEightDaysMs)
            exerciseLastPerformed = repository.getExerciseLastPerformed()
            weightHistory = repository.getExerciseWeightHistory()
        }
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // Compute all insights
    val weeklyVolume = remember(sessionSummaries) {
        SmartSuggestionsEngine.computeWeeklyVolume(sessionSummaries, nowMs)
    }
    val balanceAnalysis = remember(sessionSummaries) {
        SmartSuggestionsEngine.analyzeBalance(sessionSummaries, nowMs)
    }
    val neglectedExercises = remember(exerciseLastPerformed) {
        SmartSuggestionsEngine.findNeglectedExercises(exerciseLastPerformed, nowMs)
    }
    val plateaus = remember(weightHistory) {
        SmartSuggestionsEngine.detectPlateaus(weightHistory)
    }
    val timeOfDay = remember(sessionSummaries) {
        SmartSuggestionsEngine.analyzeTimeOfDay(sessionSummaries)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Smart Insights",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Personalized training recommendations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Section A: Weekly Volume (SUGG-01)
        item {
            WeeklyVolumeCard(weeklyVolume)
        }

        // Section B: Balance Analysis (SUGG-02)
        item {
            BalanceAnalysisCard(balanceAnalysis)
        }

        // Section C: Neglected Exercises (SUGG-03)
        item {
            NeglectedExercisesCard(neglectedExercises)
        }

        // Section D: Plateau Detection (SUGG-04)
        item {
            PlateauDetectionCard(plateaus)
        }

        // Section E: Optimal Training Time (SUGG-05)
        item {
            TimeOfDayCard(timeOfDay)
        }
    }
}

// ---- Section A: Weekly Volume ----

@Composable
private fun WeeklyVolumeCard(report: WeeklyVolumeReport) {
    InsightCard(title = "This Week's Volume") {
        if (report.volumes.isEmpty()) {
            PlaceholderText("No workouts this week")
        } else {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Muscle Group",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.4f)
                )
                Text(
                    "Sets",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.6f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Reps",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.6f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Total kg",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.End
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            report.volumes.forEach { vol ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        vol.muscleGroup.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.4f)
                    )
                    Text(
                        "${vol.sets}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        "${vol.reps}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        "${vol.totalKg.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.8f),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

// ---- Section B: Balance Analysis ----

@Composable
private fun BalanceAnalysisCard(analysis: BalanceAnalysis) {
    InsightCard(title = "Training Balance") {
        val total = analysis.pushVolume + analysis.pullVolume + analysis.legsVolume

        if (total <= 0f) {
            PlaceholderText("Not enough data for balance analysis")
        } else {
            val pushPct = (analysis.pushVolume / total * 100).toInt()
            val pullPct = (analysis.pullVolume / total * 100).toInt()
            val legsPct = (analysis.legsVolume / total * 100).toInt()

            BalanceBar(label = "Push", percentage = pushPct, fraction = analysis.pushVolume / total)
            Spacer(modifier = Modifier.height(8.dp))
            BalanceBar(label = "Pull", percentage = pullPct, fraction = analysis.pullVolume / total)
            Spacer(modifier = Modifier.height(8.dp))
            BalanceBar(label = "Legs", percentage = legsPct, fraction = analysis.legsVolume / total)

            Spacer(modifier = Modifier.height(12.dp))

            if (analysis.imbalances.isEmpty()) {
                Text(
                    "Your training is well-balanced",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccessibilityTheme.colors.success,
                    fontWeight = FontWeight.Medium
                )
            } else {
                analysis.imbalances.forEach { imbalance ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B), // Amber warning
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            imbalance.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceBar(label: String, percentage: Int, fraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$percentage%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

// ---- Section C: Neglected Exercises ----

@Composable
private fun NeglectedExercisesCard(neglected: List<NeglectedExercise>) {
    InsightCard(title = "Exercise Variety") {
        if (neglected.isEmpty()) {
            PlaceholderText("Great variety! All exercises performed recently.")
        } else {
            neglected.take(5).forEach { exercise ->
                val color = when {
                    exercise.daysSinceLastPerformed > 30 -> Color(0xFFF97316) // Orange
                    else -> Color(0xFFEAB308) // Yellow
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            exercise.exerciseName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            exercise.muscleGroup.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${exercise.daysSinceLastPerformed} days ago",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

// ---- Section D: Plateau Detection ----

@Composable
private fun PlateauDetectionCard(plateaus: List<PlateauDetection>) {
    InsightCard(title = "Plateau Alert") {
        if (plateaus.isEmpty()) {
            PlaceholderText("No plateaus detected. Keep progressing!")
        } else {
            plateaus.forEach { plateau ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            "${plateau.exerciseName} at ${plateau.currentWeightKg}kg",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            plateau.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---- Section E: Time of Day ----

@Composable
private fun TimeOfDayCard(analysis: TimeOfDayAnalysis) {
    InsightCard(title = "Best Training Window") {
        if (analysis.optimalWindow == null) {
            PlaceholderText(
                if (analysis.windowCounts.isEmpty())
                    "Train more to unlock time insights (need 10+ sessions)"
                else
                    analysis.suggestion
            )
        } else {
            val windowLabel = formatWindowName(analysis.optimalWindow)

            Text(
                "You perform best in the",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                windowLabel.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Session count bars per window
            val maxCount = analysis.windowCounts.values.maxOrNull()?.toFloat() ?: 1f
            val allWindows = TimeWindow.entries

            allWindows.forEach { window ->
                val count = analysis.windowCounts[window] ?: 0
                val barFraction = if (maxCount > 0f) count / maxCount else 0f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatWindowShort(window),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(barFraction.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (window == analysis.optimalWindow)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

// ---- Shared Components ----

@Composable
private fun InsightCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatWindowName(window: TimeWindow): String = when (window) {
    TimeWindow.EARLY_MORNING -> "Early Morning"
    TimeWindow.MORNING -> "Morning"
    TimeWindow.AFTERNOON -> "Afternoon"
    TimeWindow.EVENING -> "Evening"
    TimeWindow.NIGHT -> "Night"
}

private fun formatWindowShort(window: TimeWindow): String = when (window) {
    TimeWindow.EARLY_MORNING -> "5-7am"
    TimeWindow.MORNING -> "7-10am"
    TimeWindow.AFTERNOON -> "10am-3pm"
    TimeWindow.EVENING -> "3-8pm"
    TimeWindow.NIGHT -> "8pm-5am"
}
