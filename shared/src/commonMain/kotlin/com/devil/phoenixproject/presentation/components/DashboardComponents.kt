package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils

/**
 * Hero Strength Score Card - Primary metric showing overall fitness level
 */
@Composable
fun StrengthScoreCard(
    personalRecords: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>,
    modifier: Modifier = Modifier
) {
    val strengthScore = remember(personalRecords, workoutSessions) {
        calculateStrengthScore(personalRecords, workoutSessions)
    }

    val previousScore = remember(personalRecords, workoutSessions) {
        calculateStrengthScore(
            personalRecords.filter {
                it.timestamp < currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
            },
            workoutSessions.filter {
                it.timestamp < currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
            }
        )
    }

    val scoreDiff = strengthScore - previousScore
    val animatedScore by animateFloatAsState(
        targetValue = strengthScore.toFloat(),
        animationSpec = tween(1000),
        label = "strength score"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Strength Score",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = animatedScore.toInt().toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 72.sp
                )

                if (scoreDiff != 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (scoreDiff > 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = if (scoreDiff > 0) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${if (scoreDiff > 0) "+" else ""}$scoreDiff from last week",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (scoreDiff > 0) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * This Week Stats Card - Shows current week performance
 */
@Composable
fun ThisWeekStatsCard(
    workoutSessions: List<WorkoutSession>,
    personalRecords: List<PersonalRecord>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    val weekStart = remember {
        // Simplified week start calculation for KMP
        val now = currentTimeMillis()
        val daysSinceEpoch = now / (24 * 60 * 60 * 1000)
        val dayOfWeek = (daysSinceEpoch + 4) % 7 // 0 = Monday, 6 = Sunday
        val weekStartDays = daysSinceEpoch - dayOfWeek
        weekStartDays * 24 * 60 * 60 * 1000
    }

    val thisWeekSessions = remember(workoutSessions) {
        workoutSessions.filter { it.timestamp >= weekStart }
    }

    val thisWeekPRs = remember(personalRecords) {
        personalRecords.filter { it.timestamp >= weekStart }
    }

    val thisWeekVolume = remember(thisWeekSessions) {
        thisWeekSessions.sumOf { (it.weightPerCableKg * it.totalReps * 2).toDouble() }.toFloat()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "This Week",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeekStatItem(
                    icon = Icons.Default.FitnessCenter,
                    label = "Workouts",
                    value = thisWeekSessions.size.toString()
                )
                WeekStatItem(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    label = "PRs",
                    value = thisWeekPRs.size.toString()
                )
                WeekStatItem(
                    icon = Icons.Default.MonitorWeight,
                    label = "Volume",
                    value = formatWeight(thisWeekVolume, weightUnit)
                )
            }
        }
    }
}

@Composable
private fun WeekStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Recent PRs Card - Shows last 5 PRs achieved
 */
@Composable
fun RecentPRsCard(
    personalRecords: List<PersonalRecord>,
    exerciseNames: Map<String, String>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    val recentPRs = remember(personalRecords) {
        personalRecords.sortedByDescending { it.timestamp }.take(5)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = Color(0xFFFFD700), // Gold
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recent PRs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (recentPRs.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No PRs yet. Start lifting!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                recentPRs.forEachIndexed { index, pr ->
                    PRListItem(
                        exerciseName = exerciseNames[pr.exerciseId] ?: "Unknown",
                        pr = pr,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        isFirst = index == 0
                    )
                    if (index < recentPRs.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PRListItem(
    exerciseName: String,
    pr: PersonalRecord,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    isFirst: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isFirst)
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${pr.reps} reps • ${KmpUtils.formatTimestamp(pr.timestamp, "MMM dd, yyyy")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatWeight(pr.weightPerCableKg, weightUnit),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Top Exercises Card - Shows top 3 exercises by current PR
 */
@Composable
fun TopExercisesCard(
    personalRecords: List<PersonalRecord>,
    exerciseNames: Map<String, String>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    val topExercises = remember(personalRecords) {
        personalRecords
            .groupBy { it.exerciseId }
            .mapValues { (_, prs) -> prs.maxByOrNull { it.weightPerCableKg } }
            .entries
            .sortedByDescending { it.value?.weightPerCableKg ?: 0f }
            .take(3)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Top Exercises",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (topExercises.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Complete workouts to see your top exercises",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                topExercises.forEachIndexed { index, (exerciseId, pr) ->
                    pr?.let {
                        TopExerciseItem(
                            rank = index + 1,
                            exerciseName = exerciseNames[exerciseId] ?: "Unknown",
                            pr = it,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight
                        )
                        if (index < topExercises.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopExerciseItem(
    rank: Int,
    exerciseName: String,
    pr: PersonalRecord,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    val medalColor = when (rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(medalColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#$rank",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = medalColor
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = exerciseName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "${pr.reps} reps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = formatWeight(pr.weightPerCableKg, weightUnit),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Calculate Strength Score based on PRs and volume
 */
private fun calculateStrengthScore(
    personalRecords: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>
): Int {
    if (personalRecords.isEmpty() && workoutSessions.isEmpty()) return 0

    // PR Score: Sum of top weights per exercise (normalized)
    val prScore = if (personalRecords.isNotEmpty()) {
        personalRecords
            .groupBy { it.exerciseId }
            .mapValues { (_, prs) -> prs.maxOf { it.weightPerCableKg } }
            .values
            .sumOf { it.toDouble() } * 10
    } else {
        0.0
    }

    // Volume Score: Recent volume (last 30 days)
    val thirtyDaysAgo = currentTimeMillis() - (30 * 24 * 60 * 60 * 1000)
    val volumeScore = if (workoutSessions.isNotEmpty()) {
        workoutSessions
            .filter { it.timestamp >= thirtyDaysAgo }
            .sumOf { (it.weightPerCableKg * it.totalReps * 0.5).toDouble() }
    } else {
        0.0
    }

    // Consistency Score: Number of workouts in last 30 days
    val consistencyScore = workoutSessions
        .count { it.timestamp >= thirtyDaysAgo } * 5

    val totalScore = (prScore + volumeScore + consistencyScore).toInt()

    // Return at least 1 if there's any data to avoid showing 0
    return if (totalScore > 0) {
        totalScore
    } else if (personalRecords.isNotEmpty() || workoutSessions.isNotEmpty()) {
        1
    } else {
        0
    }
}
