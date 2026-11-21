package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.presentation.components.ConsistencyGaugeCard
import com.devil.phoenixproject.presentation.components.InsightContextBlock
import com.devil.phoenixproject.presentation.components.InsightSectionHeader
import com.devil.phoenixproject.presentation.components.MuscleBalanceRadarCard
import com.devil.phoenixproject.presentation.components.ThisWeekSummaryCard
import com.devil.phoenixproject.presentation.components.TotalVolumeCard
import com.devil.phoenixproject.presentation.components.VolumeVsIntensityCard
import com.devil.phoenixproject.presentation.components.WorkoutModeDistributionCard
import com.devil.phoenixproject.presentation.components.charts.HistoryTimePeriod
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.insights_consistency_def
import projectphoenix.shared.generated.resources.insights_consistency_so_what
import projectphoenix.shared.generated.resources.insights_consistency_subtitle
import projectphoenix.shared.generated.resources.insights_consistency_time
import projectphoenix.shared.generated.resources.insights_consistency_title
import projectphoenix.shared.generated.resources.insights_dashboard
import projectphoenix.shared.generated.resources.insights_mode_dist_def
import projectphoenix.shared.generated.resources.insights_mode_dist_so_what
import projectphoenix.shared.generated.resources.insights_no_insights_message
import projectphoenix.shared.generated.resources.insights_no_insights_title
import projectphoenix.shared.generated.resources.insights_performance_trends
import projectphoenix.shared.generated.resources.insights_performance_trends_def
import projectphoenix.shared.generated.resources.insights_performance_trends_so_what
import projectphoenix.shared.generated.resources.insights_performance_trends_subtitle
import projectphoenix.shared.generated.resources.insights_performance_trends_time
import projectphoenix.shared.generated.resources.insights_training_load_balance
import projectphoenix.shared.generated.resources.insights_training_load_balance_subtitle
import projectphoenix.shared.generated.resources.insights_training_overview
import projectphoenix.shared.generated.resources.insights_volume_history_def
import projectphoenix.shared.generated.resources.insights_volume_history_so_what
import projectphoenix.shared.generated.resources.insights_weekly_snapshot
import projectphoenix.shared.generated.resources.insights_weekly_snapshot_def
import projectphoenix.shared.generated.resources.insights_weekly_snapshot_so_what
import projectphoenix.shared.generated.resources.insights_weekly_snapshot_subtitle
import projectphoenix.shared.generated.resources.insights_weekly_snapshot_time
import projectphoenix.shared.generated.resources.insights_workload_intensity_def
import projectphoenix.shared.generated.resources.insights_workload_intensity_so_what
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Wrapper composable that constrains content width on tablets to prevent over-stretching.
 */
@Composable
private fun ResponsiveCardWrapper(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val maxWidth = ResponsiveDimensions.contentMaxWidth()

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = if (maxWidth != null) {
                Modifier.widthIn(max = maxWidth).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            },
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
) {
    var selectedPeriod by remember { mutableStateOf(HistoryTimePeriod.ALL) }
    val useCompactAccessibility = isCompactAccessibilityLayout()

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
            val cutoffEpoch = cutoff.atStartOfDayIn(
                TimeZone.currentSystemDefault(),
            ).toEpochMilliseconds()
            workoutSessions.filter { it.timestamp >= cutoffEpoch }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Spacing.medium,
            end = Spacing.medium,
            top = Spacing.medium,
            bottom = 96.dp, // Clear floating Share FAB on AnalyticsScreen
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        item {
            ResponsiveCardWrapper {
                Column {
                    Text(
                        text = stringResource(Res.string.insights_dashboard),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.insights_training_overview),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Time period filter chips — scrollable for Bold Text accessibility
        item {
            ResponsiveCardWrapper {
                val periods = HistoryTimePeriod.entries
                if (useCompactAccessibility) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            periods.take(2).forEach { period ->
                                HistoryPeriodChip(
                                    period = period,
                                    selected = selectedPeriod == period,
                                    onClick = { selectedPeriod = period },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            periods.drop(2).forEach { period ->
                                HistoryPeriodChip(
                                    period = period,
                                    selected = selectedPeriod == period,
                                    onClick = { selectedPeriod = period },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        periods.forEach { period ->
                            HistoryPeriodChip(
                                period = period,
                                selected = selectedPeriod == period,
                                onClick = { selectedPeriod = period },
                            )
                        }
                    }
                }
            }
        }

        item {
            ResponsiveCardWrapper {
                InsightSectionHeader(
                    stringResource(Res.string.insights_weekly_snapshot),
                    stringResource(Res.string.insights_weekly_snapshot_subtitle),
                )
            }
        }

        // This Week Summary Card - week-over-week comparison
        item {
            ResponsiveCardWrapper {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    InsightContextBlock(
                        stringResource(Res.string.insights_weekly_snapshot_def),
                        stringResource(Res.string.insights_weekly_snapshot_time),
                        stringResource(Res.string.insights_weekly_snapshot_so_what),
                    )
                    ThisWeekSummaryCard(
                        workoutSessions = workoutSessions,
                        personalRecords = prs,
                        weightUnit = weightUnit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            ResponsiveCardWrapper {
                InsightSectionHeader(
                    stringResource(Res.string.insights_performance_trends),
                    stringResource(Res.string.insights_performance_trends_subtitle),
                )
            }
        }

        // Muscle Balance Radar Chart
        if (prs.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    InsightContextBlock(
                        stringResource(Res.string.insights_performance_trends_def),
                        stringResource(Res.string.insights_performance_trends_time),
                        stringResource(Res.string.insights_performance_trends_so_what),
                    )
                }
            }
            item {
                ResponsiveCardWrapper {
                    MuscleBalanceRadarCard(
                        personalRecords = prs,
                        exerciseRepository = exerciseRepository,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            ResponsiveCardWrapper {
                InsightSectionHeader(
                    stringResource(Res.string.insights_consistency_title),
                    stringResource(Res.string.insights_consistency_subtitle),
                )
            }
        }

        // Workout Consistency Gauge
        item {
            ResponsiveCardWrapper {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    InsightContextBlock(
                        stringResource(Res.string.insights_consistency_def),
                        stringResource(Res.string.insights_consistency_time),
                        stringResource(Res.string.insights_consistency_so_what),
                    )
                    ConsistencyGaugeCard(
                        workoutSessions = workoutSessions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            ResponsiveCardWrapper {
                InsightSectionHeader(
                    stringResource(Res.string.insights_training_load_balance),
                    stringResource(Res.string.insights_training_load_balance_subtitle),
                )
            }
        }

        // Volume vs Intensity Combo Chart
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    InsightContextBlock(
                        stringResource(Res.string.insights_workload_intensity_def),
                        selectedPeriod.label,
                        stringResource(Res.string.insights_workload_intensity_so_what),
                    )
                }
            }
            item {
                ResponsiveCardWrapper {
                    VolumeVsIntensityCard(
                        workoutSessions = filteredSessions,
                        weightUnit = weightUnit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Total Volume Trend
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    InsightContextBlock(
                        stringResource(Res.string.insights_volume_history_def),
                        selectedPeriod.label,
                        stringResource(Res.string.insights_volume_history_so_what),
                    )
                }
            }
            item {
                ResponsiveCardWrapper {
                    TotalVolumeCard(
                        workoutSessions = filteredSessions,
                        weightUnit = weightUnit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Workout mode breakdown
        if (filteredSessions.isNotEmpty()) {
            item {
                ResponsiveCardWrapper {
                    InsightContextBlock(
                        stringResource(Res.string.insights_mode_dist_def),
                        selectedPeriod.label,
                        stringResource(Res.string.insights_mode_dist_so_what),
                    )
                }
            }
            item {
                ResponsiveCardWrapper {
                    WorkoutModeDistributionCard(
                        workoutSessions = filteredSessions,
                        modifier = Modifier.fillMaxWidth(),
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
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Insights,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(Res.string.insights_no_insights_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(Res.string.insights_no_insights_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPeriodChip(
    period: HistoryTimePeriod,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                period.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
