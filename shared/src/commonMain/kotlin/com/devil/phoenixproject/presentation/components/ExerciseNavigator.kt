package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Exercise Navigator component for routine navigation.
 * Shows progress dots and prev/next buttons to navigate between exercises.
 */
@Composable
fun ExerciseNavigator(
    currentIndex: Int,
    exerciseNames: List<String>,
    skippedIndices: Set<Int>,
    completedIndices: Set<Int>,
    onNavigateToExercise: (Int) -> Unit,
    canGoBack: Boolean,
    canSkipForward: Boolean,
    modifier: Modifier = Modifier
) {
    if (exerciseNames.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
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
            // Current exercise name
            Text(
                text = exerciseNames.getOrNull(currentIndex) ?: "Exercise ${currentIndex + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Progress indicator with dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                exerciseNames.forEachIndexed { index, _ ->
                    ExerciseDot(
                        index = index,
                        isCurrent = index == currentIndex,
                        isCompleted = index in completedIndices,
                        isSkipped = index in skippedIndices,
                        onClick = { onNavigateToExercise(index) }
                    )
                    if (index < exerciseNames.size - 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }

            // Navigation controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous button
                FilledTonalIconButton(
                    onClick = { onNavigateToExercise(currentIndex - 1) },
                    enabled = canGoBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous exercise",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Progress text
                Text(
                    text = "${currentIndex + 1} / ${exerciseNames.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Skip/Next button
                FilledTonalIconButton(
                    onClick = { onNavigateToExercise(currentIndex + 1) },
                    enabled = canSkipForward,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next exercise",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual dot representing an exercise in the routine.
 */
@Composable
private fun ExerciseDot(
    index: Int,
    isCurrent: Boolean,
    isCompleted: Boolean,
    isSkipped: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isCurrent -> MaterialTheme.colorScheme.primary
            isCompleted -> MaterialTheme.colorScheme.tertiary
            isSkipped -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "dotColor"
    )

    val size by animateDpAsState(
        targetValue = if (isCurrent) 16.dp else 12.dp,
        label = "dotSize"
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append("Exercise ${index + 1}")
                    when {
                        isCurrent -> append(", current")
                        isCompleted -> append(", completed")
                        isSkipped -> append(", skipped")
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isCompleted && !isCurrent) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(8.dp)
            )
        }
    }
}

/**
 * Compact inline exercise navigator for tighter spaces.
 * Shows just prev/next buttons with current exercise number.
 */
@Composable
fun CompactExerciseNavigator(
    currentIndex: Int,
    totalExercises: Int,
    currentExerciseName: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoBack: Boolean,
    canSkipForward: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoBack
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous"
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = currentExerciseName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${currentIndex + 1} of $totalExercises",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onNext,
            enabled = canSkipForward
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next"
            )
        }
    }
}
