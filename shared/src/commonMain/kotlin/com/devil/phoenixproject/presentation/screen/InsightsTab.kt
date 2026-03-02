package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.charts.HistoryTimePeriod
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.presentation.components.*
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import kotlinx.datetime.*

/**
 * Wrapper composable that constrains card width on tablets to prevent over-stretching.
 */
@Composable
private fun ResponsiveCardWrapper(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val maxWidth = ResponsiveDimensions.cardMaxWidth()

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (maxWidth != null) {
                Modifier.widthIn(max = maxWidth).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            content()
        }
    }
}

/**
 * Improved Insights Tab - Clear, actionable analytics with proper formatting
 */
@Composable
fun InsightsTab(
    prs: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>,
    exerciseRepository: ExerciseRepository,
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit = WeightUnit.KG,
    formatWeight: (Float, WeightUnit) -> String = { w, u -> "${w.toInt()} ${u.name.lowercase()}" }
) {
    var selectedPeriod by remember { mutableStateOf(HistoryTimePeriod.ALL) }

    // Filter sessions by selected time period
    val filteredSessions = remember(workoutSessions, selectedPeriod) {
        if (selectedPeriod == HistoryTimePeriod.ALL) {
            workoutSessions
        } else {
            val now = Instant.fromEpochMilliseconds(currentTimeMillis())
            val cutoff = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                .let { today ->
                    when (selectedPeriod) {
                        HistoryTimePeriod.DAYS_7 -> today.minus(7, DateTimeUnit.DAY)
                        HistoryTimePeriod.DAYS_14 -> today.minus(14, DateTimeUnit.DAY)
                        HistoryTimePeriod.DAYS_30 -> today.minus(30, DateTimeUnit.DAY)
                        HistoryTimePeriod.ALL -> today // unreachable
                    }
                }
            val cutoffEpoch = cutoff.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            workoutSessions.filter { it.timestamp >= cutoffEpoch }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your training overview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Time period filter chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryTimePeriod.entries.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { selectedPeriod = period },
                        label = {
                            Text(
                                period.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
        }

        // This Week Summary Card - week-over-week comparison
        item {
            ResponsiveCardWrapper {
                ThisWeekSummaryCard(
                    workoutSessions = filteredSessions,
                    personalRecords = prs,
                    weightUnit = weightUnit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 1. Muscle Balance Radar Chart (Replaces linear progress bars)
        if (prs.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    MuscleBalanceRadarCard(
                        personalRecords = prs,
                        exerciseRepository = exerciseRepository,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Workout Consistency Gauge (Replaces circular progress)
        item {
            ResponsiveCardWrapper {
                ConsistencyGaugeCard(
                    workoutSessions = filteredSessions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Volume vs Intensity Combo Chart (New Metric)
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    VolumeVsIntensityCard(
                        workoutSessions = filteredSessions,
                        weightUnit = weightUnit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Total Volume Trend (User Request)
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    TotalVolumeCard(
                        workoutSessions = filteredSessions,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 5. Mode Distribution Donut Chart (New Metric)
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    WorkoutModeDistributionCard(
                        workoutSessions = filteredSessions,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Empty state
        if (prs.isEmpty() && workoutSessions.isEmpty()) {
            item {
                ResponsiveCardWrapper {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Insights,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Insights Yet",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Complete workouts to unlock insights",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
