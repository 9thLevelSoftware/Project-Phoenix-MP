package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.effectiveTotalVolumeKg
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMax
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMaxSource
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.viewmodel.ProfileLoadable
import com.devil.phoenixproject.presentation.viewmodel.ProfilePrHighlights
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*

data class ProfileRecentHistory(
    val sessionsNewestFirst: List<WorkoutSession>,
    val chartPointsOldestFirst: List<VolumePoint>,
)

internal fun buildProfileRecentHistory(
    sessions: List<WorkoutSession>,
    labelForTimestamp: (Long) -> String,
): ProfileRecentHistory {
    val sessionsNewestFirst = sessions
        .sortedWith(
            compareByDescending<WorkoutSession> { it.timestamp }
                .thenByDescending { it.id },
        )
        .take(5)
    val chartPointsOldestFirst = sessionsNewestFirst
        .asReversed()
        .mapNotNull { session ->
            session.effectiveTotalVolumeKg()
                .takeIf { it.isFinite() && it > 0f }
                ?.let { volume ->
                    VolumePoint(
                        label = labelForTimestamp(session.timestamp),
                        volume = volume,
                    )
                }
        }
    return ProfileRecentHistory(sessionsNewestFirst, chartPointsOldestFirst)
}

@Composable
fun ProfileExerciseInsights(
    selectedExercise: Exercise?,
    missingExerciseId: String?,
    selectionFailure: Throwable?,
    currentOneRepMax: ProfileLoadable<CurrentOneRepMax>,
    prHighlights: ProfileLoadable<ProfilePrHighlights>,
    recentSessions: ProfileLoadable<List<WorkoutSession>>,
    weightUnit: WeightUnit,
    onChooseExercise: () -> Unit,
    onViewFullHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chooseLabel = stringResource(Res.string.profile_choose_exercise)
    val exerciseLabel = selectedExercise?.displayName ?: chooseLabel
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.profile_exercise_insights),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onChooseExercise)
                .clearAndSetSemantics {
                    contentDescription = exerciseLabel
                    role = Role.Button
                    onClick(label = exerciseLabel) {
                        onChooseExercise()
                        true
                    }
                },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = exerciseLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            }
        }

        if (selectedExercise == null) {
            val message = when {
                selectionFailure != null -> Res.string.profile_insights_load_failed
                missingExerciseId != null -> Res.string.profile_missing_exercise
                else -> Res.string.profile_no_exercise_history
            }
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            CurrentOneRepMaxCard(currentOneRepMax, weightUnit)
            ProfilePrHighlightsCard(prHighlights, weightUnit)
            ProfileRecentHistoryCard(
                recentSessions = recentSessions,
                selectedExerciseId = selectedExercise.id.orEmpty(),
                weightUnit = weightUnit,
                onViewFullHistory = onViewFullHistory,
            )
        }
    }
}

@Composable
private fun CurrentOneRepMaxCard(
    loadable: ProfileLoadable<CurrentOneRepMax>,
    weightUnit: WeightUnit,
) {
    InsightCard(title = stringResource(Res.string.profile_current_one_rep_max)) {
        when (loadable) {
            ProfileLoadable.Empty -> Text(
                stringResource(Res.string.profile_one_rep_max_source_none),
            )
            ProfileLoadable.Loading -> LoadingIndicator(LoadingIndicatorSize.Medium)
            is ProfileLoadable.Failed -> InsightFailure()
            is ProfileLoadable.Ready -> {
                Text(
                    text = weightValue(loadable.value.perCableKg, weightUnit),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        when (loadable.value.source) {
                            CurrentOneRepMaxSource.VELOCITY ->
                                Res.string.profile_one_rep_max_source_velocity
                            CurrentOneRepMaxSource.ASSESSMENT ->
                                Res.string.profile_one_rep_max_source_assessment
                            CurrentOneRepMaxSource.SESSION ->
                                Res.string.profile_one_rep_max_source_session
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ProfilePrHighlightsCard(
    loadable: ProfileLoadable<ProfilePrHighlights>,
    weightUnit: WeightUnit,
) {
    InsightCard(title = stringResource(Res.string.profile_pr_highlights)) {
        when (loadable) {
            ProfileLoadable.Empty -> PrHighlightsRow(ProfilePrHighlights(null, null, null), weightUnit)
            ProfileLoadable.Loading -> LoadingIndicator(LoadingIndicatorSize.Medium)
            is ProfileLoadable.Failed -> InsightFailure()
            is ProfileLoadable.Ready -> PrHighlightsRow(loadable.value, weightUnit)
        }
    }
}

@Composable
private fun PrHighlightsRow(highlights: ProfilePrHighlights, weightUnit: WeightUnit) {
    val repsLabel = stringResource(Res.string.label_reps)
    val perCableLabel = stringResource(Res.string.label_per_cable)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrCell(
            label = stringResource(Res.string.profile_pr_max_weight),
            value = highlights.maxWeightPerCableKg?.let { weightValue(it, weightUnit) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        PrCell(
            label = stringResource(Res.string.profile_pr_estimated_one_rep_max),
            value = highlights.estimatedOneRepMaxPerCableKg
                ?.let { weightValue(it, weightUnit) }
                ?: "—",
            modifier = Modifier.weight(1f),
        )
        // maxVolumeKg is a legacy-named per-cable kg x reps value; never multiply cable count.
        val maxVolume = highlights.maxVolumeKg?.let {
            "${WeightDisplayFormatter.formatPerCableWeight(it, weightUnit)} " +
                "${unitSuffix(weightUnit)} × $repsLabel · $perCableLabel"
        } ?: "—"
        PrCell(
            label = stringResource(Res.string.profile_pr_max_volume),
            value = maxVolume,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PrCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ProfileRecentHistoryCard(
    recentSessions: ProfileLoadable<List<WorkoutSession>>,
    selectedExerciseId: String,
    weightUnit: WeightUnit,
    onViewFullHistory: (String) -> Unit,
) {
    InsightCard(title = stringResource(Res.string.profile_recent_history)) {
        when (recentSessions) {
            ProfileLoadable.Empty -> Text(stringResource(Res.string.profile_no_exercise_history))
            ProfileLoadable.Loading -> LoadingIndicator(LoadingIndicatorSize.Medium)
            is ProfileLoadable.Failed -> InsightFailure()
            is ProfileLoadable.Ready -> {
                val repsLabel = stringResource(Res.string.label_reps)
                val history = buildProfileRecentHistory(recentSessions.value) { timestamp ->
                    KmpUtils.formatTimestamp(timestamp, "MMM d")
                }
                if (history.sessionsNewestFirst.isEmpty()) {
                    Text(stringResource(Res.string.profile_no_exercise_history))
                } else {
                    if (history.chartPointsOldestFirst.isNotEmpty()) {
                        VolumeHistoryChart(
                            data = history.chartPointsOldestFirst,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp)
                                .clearAndSetSemantics { },
                        )
                    }
                    history.sessionsNewestFirst.forEachIndexed { index, session ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = KmpUtils.formatTimestamp(session.timestamp, "MMM d"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val volume = session.effectiveTotalVolumeKg()
                            Text(
                                text = if (volume.isFinite() && volume > 0f) {
                                    "${formatTotalVolume(volume, weightUnit)} " +
                                        "${unitSuffix(weightUnit)} × $repsLabel"
                                } else {
                                    "—"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (selectedExerciseId.isNotBlank()) {
                        TextButton(onClick = { onViewFullHistory(selectedExerciseId) }) {
                            Text(stringResource(Res.string.profile_view_full_history))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InsightFailure() {
    Text(
        text = stringResource(Res.string.profile_insights_load_failed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun weightValue(perCableKg: Float, weightUnit: WeightUnit): String =
    "${WeightDisplayFormatter.formatPerCableWeight(perCableKg, weightUnit)} ${unitSuffix(weightUnit)}"

private fun formatTotalVolume(totalVolumeKg: Float, weightUnit: WeightUnit): String =
    UnitConverter.formatDecimal(
        when (weightUnit) {
            WeightUnit.KG -> totalVolumeKg
            WeightUnit.LB -> UnitConverter.kgToLb(totalVolumeKg)
        },
    )

@Composable
private fun unitSuffix(weightUnit: WeightUnit): String = when (weightUnit) {
    WeightUnit.KG -> stringResource(Res.string.label_kg)
    WeightUnit.LB -> stringResource(Res.string.label_lbs)
}
