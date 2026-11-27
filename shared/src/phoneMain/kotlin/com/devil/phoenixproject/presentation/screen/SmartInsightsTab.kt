package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.SmartSuggestionsRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.BalanceAnalysis
import com.devil.phoenixproject.domain.model.NeglectedExercise
import com.devil.phoenixproject.domain.model.PlateauDetection
import com.devil.phoenixproject.domain.model.SessionSummary
import com.devil.phoenixproject.domain.model.TimeOfDayAnalysis
import com.devil.phoenixproject.domain.model.TimeWindow
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WeeklyVolumeReport
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.ReadinessEngine
import com.devil.phoenixproject.domain.premium.SmartSuggestionsEngine
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.InsightContextBlock
import com.devil.phoenixproject.presentation.components.InsightSectionHeader
import com.devil.phoenixproject.presentation.components.ReadinessBriefingCard
import com.devil.phoenixproject.presentation.components.TimeframeBadge
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.SmartInsightsWeightText
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.rethrowIfCancellation
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.great_variety
import projectphoenix.shared.generated.resources.insights_error_message
import projectphoenix.shared.generated.resources.insights_error_title
import projectphoenix.shared.generated.resources.insights_best_window
import projectphoenix.shared.generated.resources.insights_col_muscle_group
import projectphoenix.shared.generated.resources.insights_col_reps
import projectphoenix.shared.generated.resources.insights_col_sets
import projectphoenix.shared.generated.resources.insights_col_total
import projectphoenix.shared.generated.resources.insights_days_ago
import projectphoenix.shared.generated.resources.insights_exercise_variety
import projectphoenix.shared.generated.resources.insights_legs
import projectphoenix.shared.generated.resources.insights_need_more_sessions
import projectphoenix.shared.generated.resources.insights_perform_best_in
import projectphoenix.shared.generated.resources.insights_plateau_alert
import projectphoenix.shared.generated.resources.insights_pull
import projectphoenix.shared.generated.resources.insights_push
import projectphoenix.shared.generated.resources.insights_subtitle
import projectphoenix.shared.generated.resources.insights_title
import projectphoenix.shared.generated.resources.insights_training_balance
import projectphoenix.shared.generated.resources.insights_weekly_volume
import projectphoenix.shared.generated.resources.insights_well_balanced
import projectphoenix.shared.generated.resources.no_balance_data
import projectphoenix.shared.generated.resources.no_plateaus
import projectphoenix.shared.generated.resources.no_workouts_this_week
import projectphoenix.shared.generated.resources.smart_insights_balance_def
import projectphoenix.shared.generated.resources.smart_insights_balance_so_what
import projectphoenix.shared.generated.resources.smart_insights_best_window_def
import projectphoenix.shared.generated.resources.smart_insights_best_window_so_what
import projectphoenix.shared.generated.resources.smart_insights_last_28_days
import projectphoenix.shared.generated.resources.smart_insights_last_7_days
import projectphoenix.shared.generated.resources.smart_insights_plateau_at
import projectphoenix.shared.generated.resources.smart_insights_plateau_def
import projectphoenix.shared.generated.resources.smart_insights_plateau_so_what
import projectphoenix.shared.generated.resources.smart_insights_readiness_def
import projectphoenix.shared.generated.resources.smart_insights_readiness_schedule_subtitle
import projectphoenix.shared.generated.resources.smart_insights_readiness_schedule_title
import projectphoenix.shared.generated.resources.smart_insights_readiness_so_what
import projectphoenix.shared.generated.resources.smart_insights_readiness_time
import projectphoenix.shared.generated.resources.smart_insights_readiness_title
import projectphoenix.shared.generated.resources.smart_insights_recent_sessions
import projectphoenix.shared.generated.resources.smart_insights_recovery_plateaus_subtitle
import projectphoenix.shared.generated.resources.smart_insights_recovery_plateaus_title
import projectphoenix.shared.generated.resources.smart_insights_sets_reps
import projectphoenix.shared.generated.resources.smart_insights_training_balance_subtitle
import projectphoenix.shared.generated.resources.smart_insights_training_balance_title
import projectphoenix.shared.generated.resources.smart_insights_variety_def
import projectphoenix.shared.generated.resources.smart_insights_variety_so_what
import projectphoenix.shared.generated.resources.smart_insights_weekly_volume_def
import projectphoenix.shared.generated.resources.smart_insights_weekly_volume_so_what
import projectphoenix.shared.generated.resources.smart_insights_weekly_volume_subtitle
import projectphoenix.shared.generated.resources.smart_insights_weekly_volume_title
import projectphoenix.shared.generated.resources.smart_insights_window_afternoon
import projectphoenix.shared.generated.resources.smart_insights_window_early_morning
import projectphoenix.shared.generated.resources.smart_insights_window_evening
import projectphoenix.shared.generated.resources.smart_insights_window_morning
import projectphoenix.shared.generated.resources.smart_insights_window_night
import projectphoenix.shared.generated.resources.smart_insights_window_range_afternoon
import projectphoenix.shared.generated.resources.smart_insights_window_range_early_morning
import projectphoenix.shared.generated.resources.smart_insights_window_range_evening
import projectphoenix.shared.generated.resources.smart_insights_window_range_morning
import projectphoenix.shared.generated.resources.smart_insights_window_range_night

/**
 * Smart Insights Tab - training insights powered by SmartSuggestionsEngine and ReadinessEngine.
 *
 * Displays 6 insight sections:
 * 1. Weekly Volume per muscle group (SUGG-01)
 * 2. Push/Pull/Legs Balance Analysis (SUGG-02)
 * 3. Neglected Exercises (SUGG-03)
 * 4. Plateau Detection (SUGG-04)
 * 5. Time-of-Day Optimal Training Window (SUGG-05)
 * 6. Training Readiness / ACWR (ACWR-01)
 */
@Composable
fun SmartInsightsTab(
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    SmartInsightsContent(weightUnit = weightUnit, modifier = modifier)
}

@Composable
private fun SmartInsightsContent(
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    val repository: SmartSuggestionsRepository = koinInject()
    val userProfileRepository: UserProfileRepository = koinInject()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    val profileId = activeProfile?.id ?: "default"

    var insightsAnchorNowMs by remember { mutableStateOf<Long?>(null) }
    val twentyEightDaysMs = 28L * 24 * 60 * 60 * 1000

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var sessionSummaries by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var exerciseLastPerformed by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var weightHistory by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    LaunchedEffect(profileId, retryKey) {
        // Option A: fetch a broad 28-day slice once, then compute every card's time window from
        // the exact same anchor timestamp. This avoids drift where query and computation use
        // slightly different "now" values.
        isLoading = true
        hasError = false
        insightsAnchorNowMs = null
        sessionSummaries = emptyList()
        exerciseLastPerformed = emptyList()
        weightHistory = emptyList()
        val snapshotNowMs = currentTimeMillis()

        try {
            val (fetchedSessions, fetchedLastPerformed, fetchedWeightHistory) =
                withContext(Dispatchers.IO) {
                    Triple(
                        repository.getSessionSummariesSince(snapshotNowMs - twentyEightDaysMs, profileId),
                        repository.getExerciseLastPerformed(profileId),
                        repository.getExerciseWeightHistory(profileId),
                    )
                }

            sessionSummaries = fetchedSessions
            exerciseLastPerformed = fetchedLastPerformed
            weightHistory = fetchedWeightHistory
            insightsAnchorNowMs = snapshotNowMs
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // DB or IO failure — surface error state instead of infinite spinner (lens-state-patterns-3)
            hasError = true
        } finally {
            isLoading = false
        }
    }

    val anchorNowMs = insightsAnchorNowMs

    if (hasError) {
        EmptyState(
            icon = Icons.Default.Warning,
            title = stringResource(Res.string.insights_error_title),
            message = stringResource(Res.string.insights_error_message),
            actionText = stringResource(Res.string.action_retry),
            onAction = { retryKey++ },
            modifier = modifier,
        )
        return
    }

    if (isLoading || anchorNowMs == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(LoadingIndicatorSize.Large)
        }
        return
    }

    // Compute all insights from the same fetch anchor timestamp (Option A).
    val weeklyVolume = remember(sessionSummaries, anchorNowMs) {
        SmartSuggestionsEngine.computeWeeklyVolume(sessionSummaries, anchorNowMs)
    }
    val balanceAnalysis = remember(sessionSummaries, anchorNowMs) {
        SmartSuggestionsEngine.analyzeBalance(sessionSummaries, anchorNowMs)
    }
    val neglectedExercises = remember(exerciseLastPerformed, anchorNowMs) {
        SmartSuggestionsEngine.findNeglectedExercises(exerciseLastPerformed, anchorNowMs)
    }
    val plateaus = remember(weightHistory) {
        SmartSuggestionsEngine.detectPlateaus(weightHistory)
    }
    val timeOfDay = remember(sessionSummaries) {
        SmartSuggestionsEngine.analyzeTimeOfDay(sessionSummaries)
    }

    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val widthModifier = if (contentMaxWidth != null) {
        Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()
    } else {
        Modifier.fillMaxWidth()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
        contentPadding = PaddingValues(
            start = Spacing.medium,
            end = Spacing.medium,
            top = Spacing.medium,
            bottom = 96.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(modifier = widthModifier) {
                Text(
                    text = stringResource(Res.string.insights_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = stringResource(Res.string.insights_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            InsightSectionHeader(
                title = stringResource(Res.string.smart_insights_weekly_volume_title),
                subtitle = stringResource(Res.string.smart_insights_weekly_volume_subtitle),
                modifier = widthModifier,
            )
        }

        // Section A: Weekly Volume (SUGG-01)
        item {
            Box(modifier = widthModifier) {
                WeeklyVolumeCard(weeklyVolume, weightUnit)
            }
        }

        item {
            InsightSectionHeader(
                title = stringResource(Res.string.smart_insights_training_balance_title),
                subtitle = stringResource(Res.string.smart_insights_training_balance_subtitle),
                modifier = widthModifier,
            )
        }

        // Section B: Balance Analysis (SUGG-02)
        item {
            Box(modifier = widthModifier) {
                BalanceAnalysisCard(balanceAnalysis)
            }
        }

        item {
            InsightSectionHeader(
                title = stringResource(Res.string.smart_insights_recovery_plateaus_title),
                subtitle = stringResource(Res.string.smart_insights_recovery_plateaus_subtitle),
                modifier = widthModifier,
            )
        }

        // Section C: Neglected Exercises (SUGG-03)
        item {
            Box(modifier = widthModifier) {
                NeglectedExercisesCard(neglectedExercises)
            }
        }

        // Section D: Plateau Detection (SUGG-04)
        item {
            Box(modifier = widthModifier) {
                PlateauDetectionCard(plateaus, weightUnit)
            }
        }

        item {
            InsightSectionHeader(
                title = stringResource(Res.string.smart_insights_readiness_schedule_title),
                subtitle = stringResource(Res.string.smart_insights_readiness_schedule_subtitle),
                modifier = widthModifier,
            )
        }

        // Section E: Optimal Training Time (SUGG-05)
        item {
            Box(modifier = widthModifier) {
                TimeOfDayCard(timeOfDay)
            }
        }

        // Section F: Training Readiness / ACWR (ACWR-01)
        item {
            Column(
                modifier = widthModifier,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                val readiness = remember(sessionSummaries, anchorNowMs) {
                    ReadinessEngine.computeReadiness(sessionSummaries, anchorNowMs)
                }
                InsightContextBlock(
                    title = stringResource(Res.string.smart_insights_readiness_title),
                    definition = stringResource(Res.string.smart_insights_readiness_def),
                    timeframe = stringResource(Res.string.smart_insights_readiness_time),
                    soWhat = stringResource(Res.string.smart_insights_readiness_so_what),
                )
                ReadinessBriefingCard(
                    readinessResult = readiness,
                    weightUnit = weightUnit,
                )
            }
        }
    }
}

// ---- Section A: Weekly Volume ----

@Composable
private fun WeeklyVolumeCard(report: WeeklyVolumeReport, weightUnit: WeightUnit) {
    InsightCard(
        title = stringResource(Res.string.insights_weekly_volume),
        definition = stringResource(Res.string.smart_insights_weekly_volume_def),
        timeframe = stringResource(Res.string.smart_insights_last_7_days),
        soWhat = stringResource(Res.string.smart_insights_weekly_volume_so_what),
    ) {
        if (report.volumes.isEmpty()) {
            PlaceholderText(stringResource(Res.string.no_workouts_this_week))
        } else if (isCompactAccessibilityLayout()) {
            val unitLabel = SmartInsightsWeightText.unitLabel(weightUnit)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                report.volumes.forEach { vol ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            vol.muscleGroup,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(end = Spacing.small),
                        )
                        Text(
                            "${SmartInsightsWeightText.volumeAmount(vol.totalKg, weightUnit)} $unitLabel",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        stringResource(Res.string.smart_insights_sets_reps, vol.sets, vol.reps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        } else {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.extraSmall),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(Res.string.insights_col_muscle_group),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    stringResource(Res.string.insights_col_sets),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.6f),
                    textAlign = TextAlign.End,
                )
                Text(
                    stringResource(Res.string.insights_col_reps),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.6f),
                    textAlign = TextAlign.End,
                )
                Text(
                    stringResource(Res.string.insights_col_total, SmartInsightsWeightText.unitLabel(weightUnit)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))

            report.volumes.forEach { vol ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        vol.muscleGroup,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.4f),
                    )
                    Text(
                        "${vol.sets}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "${vol.reps}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        SmartInsightsWeightText.volumeAmount(vol.totalKg, weightUnit),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.8f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ---- Section B: Balance Analysis ----

/**
 * Integer labels for the push / pull / legs balance bars.
 *
 * Push and pull are rounded to the nearest percent. Legs is `100 - push - pull`,
 * clamped to 0..100, so the three labels sum to 100 and the last one cannot go
 * negative. A non-positive or non-finite total returns null; the card keeps the empty placeholder.
 */
internal fun balanceSharePercents(
    pushVolume: Float,
    pullVolume: Float,
    legsVolume: Float,
): BalanceSharePercents? {
    val total = pushVolume + pullVolume + legsVolume
    // roundToInt() throws on NaN, and NaN <= 0f is false: treat a non-finite
    // total like an empty one instead of crashing the card.
    if (!total.isFinite() || total <= 0f) return null
    val push = (pushVolume / total * 100f).roundToInt().coerceIn(0, 100)
    // Cap pull at the points left after push so a pair of round-ups cannot
    // force the legs remainder below zero.
    val pull = (pullVolume / total * 100f).roundToInt().coerceIn(0, 100 - push)
    val legs = (100 - push - pull).coerceIn(0, 100)
    return BalanceSharePercents(push = push, pull = pull, legs = legs)
}

internal data class BalanceSharePercents(
    val push: Int,
    val pull: Int,
    val legs: Int,
)

@Composable
private fun BalanceAnalysisCard(analysis: BalanceAnalysis) {
    InsightCard(
        title = stringResource(Res.string.insights_training_balance),
        definition = stringResource(Res.string.smart_insights_balance_def),
        timeframe = stringResource(Res.string.smart_insights_last_28_days),
        soWhat = stringResource(Res.string.smart_insights_balance_so_what),
    ) {
        val total = analysis.pushVolume + analysis.pullVolume + analysis.legsVolume
        val shares = balanceSharePercents(analysis.pushVolume, analysis.pullVolume, analysis.legsVolume)

        if (shares == null) {
            PlaceholderText(stringResource(Res.string.no_balance_data))
        } else {
            BalanceBar(
                label = stringResource(Res.string.insights_push),
                percentage = shares.push,
                fraction =
                    analysis.pushVolume / total,
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            BalanceBar(
                label = stringResource(Res.string.insights_pull),
                percentage = shares.pull,
                fraction =
                    analysis.pullVolume / total,
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            BalanceBar(
                label = stringResource(Res.string.insights_legs),
                percentage = shares.legs,
                fraction =
                    analysis.legsVolume / total,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            if (analysis.imbalances.isEmpty()) {
                Text(
                    stringResource(Res.string.insights_well_balanced),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccessibilityTheme.colors.success,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                analysis.imbalances.forEach { imbalance ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = AccessibilityTheme.colors.warning,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            imbalance.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceBar(label: String, percentage: Int, fraction: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "$percentage%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ---- Section C: Neglected Exercises ----

@Composable
private fun NeglectedExercisesCard(neglected: List<NeglectedExercise>) {
    InsightCard(
        title = stringResource(Res.string.insights_exercise_variety),
        definition = stringResource(Res.string.smart_insights_variety_def),
        timeframe = stringResource(Res.string.smart_insights_last_28_days),
        soWhat = stringResource(Res.string.smart_insights_variety_so_what),
    ) {
        if (neglected.isEmpty()) {
            PlaceholderText(stringResource(Res.string.great_variety))
        } else {
            neglected.take(5).forEach { exercise ->
                val neglectColors = AccessibilityTheme.colors
                val color = when {
                    exercise.daysSinceLastPerformed > 30 -> neglectColors.warning
                    else -> MaterialTheme.colorScheme.secondary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.extraSmall),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            exercise.exerciseName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            exercise.muscleGroup.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            Res.string.insights_days_ago,
                            exercise.daysSinceLastPerformed,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
        }
    }
}

// ---- Section D: Plateau Detection ----

@Composable
private fun PlateauDetectionCard(plateaus: List<PlateauDetection>, weightUnit: WeightUnit) {
    InsightCard(
        title = stringResource(Res.string.insights_plateau_alert),
        definition = stringResource(Res.string.smart_insights_plateau_def),
        timeframe = stringResource(Res.string.smart_insights_recent_sessions),
        soWhat = stringResource(Res.string.smart_insights_plateau_so_what),
    ) {
        if (plateaus.isEmpty()) {
            PlaceholderText(stringResource(Res.string.no_plateaus))
        } else {
            plateaus.forEach { plateau ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.extraSmall),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Column {
                        Text(
                            stringResource(
                                Res.string.smart_insights_plateau_at,
                                plateau.exerciseName,
                                SmartInsightsWeightText.plateauLoad(
                                    plateau.currentWeightKg,
                                    weightUnit,
                                    SmartInsightsWeightText.unitLabel(weightUnit),
                                ),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            plateau.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    InsightCard(
        title = stringResource(Res.string.insights_best_window),
        definition = stringResource(Res.string.smart_insights_best_window_def),
        timeframe = stringResource(Res.string.smart_insights_last_28_days),
        soWhat = stringResource(Res.string.smart_insights_best_window_so_what),
    ) {
        if (analysis.optimalWindow == null) {
            PlaceholderText(
                if (analysis.windowCounts.isEmpty()) {
                    stringResource(Res.string.insights_need_more_sessions)
                } else {
                    analysis.suggestion
                },
            )
        } else {
            val windowLabel = formatWindowName(analysis.optimalWindow)

            Text(
                stringResource(Res.string.insights_perform_best_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                windowLabel.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Session count bars per window
            val maxCount = analysis.windowCounts.values.maxOrNull()?.toFloat() ?: 1f
            val allWindows = TimeWindow.entries

            allWindows.forEach { window ->
                val count = analysis.windowCounts[window] ?: 0
                val barFraction = if (maxCount > 0f) count / maxCount else 0f

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatWindowShort(window),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(barFraction.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (window == analysis.optimalWindow) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun InsightCard(
    title: String,
    definition: String,
    timeframe: String,
    soWhat: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(definition, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            TimeframeBadge(timeframe)
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(soWhat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(Spacing.small))
            content()
        }
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun formatWindowName(window: TimeWindow): String = when (window) {
    TimeWindow.EARLY_MORNING -> stringResource(Res.string.smart_insights_window_early_morning)
    TimeWindow.MORNING -> stringResource(Res.string.smart_insights_window_morning)
    TimeWindow.AFTERNOON -> stringResource(Res.string.smart_insights_window_afternoon)
    TimeWindow.EVENING -> stringResource(Res.string.smart_insights_window_evening)
    TimeWindow.NIGHT -> stringResource(Res.string.smart_insights_window_night)
}

@Composable
private fun formatWindowShort(window: TimeWindow): String = when (window) {
    TimeWindow.EARLY_MORNING -> stringResource(Res.string.smart_insights_window_range_early_morning)
    TimeWindow.MORNING -> stringResource(Res.string.smart_insights_window_range_morning)
    TimeWindow.AFTERNOON -> stringResource(Res.string.smart_insights_window_range_afternoon)
    TimeWindow.EVENING -> stringResource(Res.string.smart_insights_window_range_evening)
    TimeWindow.NIGHT -> stringResource(Res.string.smart_insights_window_range_night)
}
